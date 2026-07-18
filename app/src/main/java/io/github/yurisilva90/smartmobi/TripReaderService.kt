package io.github.yurisilva90.smartmobi

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.thread

// ══════════════════════════════════════════════════════════════════
// Leitor de tela Uber/99 — só leitura (não toca em nada, não age).
//
// v1.0.17 — diagnóstico + detecção real de oferta:
//  • A tela de oferta da 99 NÃO tem "Recusar" com texto; tem "Aceitar por
//    R$X,XX". Detecção reescrita com base em prints reais.
//  • Eventos deixam de ser filtrados pelo pacote do EVENTO (a oferta pode
//    ser desenhada como janela sobreposta enquanto outro app está na
//    frente). Agora toda varredura olha as JANELAS visíveis.
//  • Captura notificações (TYPE_NOTIFICATION_STATE_CHANGED) da 99/Uber —
//    a oferta pode chegar por aí com o valor no texto.
//  • Loga metadados das janelas (tipo/camada/pacote) pra descobrir por
//    qual caminho a informação da oferta realmente chega. Se o card for
//    desenhado sem nós de texto (Flutter/canvas), saberemos e partimos
//    pra outra estratégia (OCR).
// ══════════════════════════════════════════════════════════════════
class TripReaderService : AccessibilityService() {

    companion object {
        val UBER_PKGS = setOf("com.ubercab.driver", "com.ubercab")
        val NN_PKGS = setOf("com.app99.driver", "com.taxis99.driver")
        val GIGU_PKGS = setOf("co.gigu.app")
        val CAPTURE_PKGS = UBER_PKGS + NN_PKGS + GIGU_PKGS
        const val LOG_FILE = "trip_reader_log.txt"
        const val SUPABASE_URL  = "https://jlsrpptslwfhmkvelaro.supabase.co"
        const val SUPABASE_ANON = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Impsc3JwcHRzbHdmaG1rdmVsYXJvIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzM4NjYxNzIsImV4cCI6MjA4OTQ0MjE3Mn0.4gD4dKx05QaOAAkY1gAx2HuH_CN31Xg3kkDMdvZ4kh0"

        const val KEY_FLASH_CONFIG_JSON = "flash_config_json"

        val KPI_ORDER = listOf(
            "rkm"    to "R$/KM",
            "rhora"  to "R$/HORA",
            "rmin"   to "R$/MIN",
            "margem" to "% LUCRO",
            "lucro"  to "R$ LUCRO"
        )

        private var lastSig = ""
        private var lastLogMs = 0L
        private var lastScanMs = 0L
        private var lastState = "?"
        private var lastWinSig = ""

        // ── Status no widget flutuante: Online/Buscar/Corrida ──────────────
        // Debounce: exige N leituras equivalentes dentro de uma janela das
        // últimas M leituras antes de confirmar a troca (leituras a cada
        // ~600ms via pollForeground).
        // BUG CONFIRMADO EM LOG REAL (14/07/2026): o painel da Uber tem um
        // texto que ALTERNA entre "Buscando" e "Destino definido" a cada
        // ~2s, sempre um ou outro, nunca os dois juntos. Só "Buscando" bate
        // com a regra de Online — "Destino definido" não bate com nenhuma
        // regra, então cai no fallback (mantém o último estado). Com debounce
        // por CONSECUTIVAS, isso reseta o contador pra 1 a cada leitura, e
        // nunca fecha 3 seguidas — o status ficava travado pra sempre mesmo
        // com o sinal certo aparecendo o tempo todo. Trocado pra "maioria
        // dentro de uma janela": com janela ímpar, uma alternação estrita
        // 50/50 sempre garante maioria pro lado que realmente domina.
        private const val TRIP_STATE_DEBOUNCE = 3
        private const val TRIP_STATE_DEBOUNCE_WINDOW = 5
        private val tripSubStateHistory = ArrayDeque<String>()
        private var confirmedTripSubState = "online"
    }

    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val main = Handler(Looper.getMainLooper())
    private val flashCard by lazy { FlashCard(this) }
    private var lastFlashSig = ""
    private var lastFlashSigSetMs = 0L
    // Guard usado tanto no polling (pollForeground) quanto no evento de
    // acessibilidade: só pausa a varredura de status Online/Buscar/Corrida
    // enquanto a oferta é recente o bastante pra ainda ser a mesma decisão
    // rápida que motivou a trava original (mesma janela do auto-hide visual
    // do FlashCard, 15s, +5s de margem).
    // BUG CONFIRMADO EM LOG REAL (14/07/2026): "Radar de Viagens"/"Modo
    // Destino" da Uber mantém uma oferta candidata em tela por MINUTOS
    // enquanto o motorista ainda está buscando — isOfferScreen() continua
    // batendo o tempo todo nesse modo, lastFlashSig nunca zera (só zera
    // quando a tela PARA de parecer oferta), e o status Online/Buscar/
    // Corrida ficava PRESO no último valor confirmado a viagem inteira
    // (visto: travado em "Corrida" por mais de 4 minutos com o motorista
    // genuinamente buscando). Depois da janela abaixo, mesmo que a tela
    // ainda pareça oferta, volta a varrer o status normalmente.
    private val FLASH_STATUS_GUARD_MS = 20_000L
    private fun offerGuardActive(): Boolean =
        lastFlashSig.isNotEmpty() && System.currentTimeMillis() - lastFlashSigSetMs < FLASH_STATUS_GUARD_MS
    private var lastRealState = "?"
    // ── Anti-flicker: oferta chegando DURANTE corrida ativa ─────────────
    // CONFIRMADO EM LOG REAL (13/07/2026, corrida do Raymundo, Uber): a
    // mesma oferta (R$20,68) chegou a mostrar/esconder 7 vezes em 7
    // segundos. Causa: em corrida a oferta é um card por CIMA da tela de
    // navegação — o OCR lê os dois misturados (endereço, "Corrida",
    // cronômetro da corrida atual junto com o texto da oferta nova), então
    // isOfferScreen() bate numa passada e falha na seguinte só por ruído,
    // mesmo com a oferta genuinamente ainda na tela. hideFlashIfActive()
    // rodava na PRIMEIRA falha — agora exige N falhas SEGUIDAS antes de
    // esconder de verdade. A 600ms/leitura, grace=2 ainda esconde em
    // ~1.2s quando a oferta sai de verdade — não é perceptível como atraso.
    private var offerMissStreak = 0
    private val OFFER_HIDE_GRACE = 2
    // INVESTIGAÇÃO (13/07/2026): achado um buraco de 36min sem nenhum card,
    // com ofertas reais e válidas confirmadas no OCR (texto limpo, formato
    // Uber padrão) durante esse tempo. Causa provável: essas duas variáveis
    // eram um valor ÚNICO compartilhado entre Uber e 99, não por plataforma.
    // Rodando os dois apps ao mesmo tempo, uma oferta da 99 com valor por
    // acaso a menos de R$0,02 de uma oferta da Uber (comum, faixa de preço
    // parecida) fazia a trava de "não regredir pernas" comparar contra o
    // valor errado — a oferta Uber podia ser descartada silenciosamente por
    // parecer "regressão" de uma oferta de OUTRA plataforma. Agora é por
    // plataforma, elimina esse cruzamento.
    private val lastOfferValorByPlat = HashMap<String, Double>()
    private val bestLegsForOfferByPlat = HashMap<String, Int>()
    // 99: a tela de navegação compacta (rua/distância/velocidade) é IDÊNTICA
    // indo buscar o passageiro e indo pro destino — só o endereço fixo no
    // topo muda, e não vale a pena comparar endereço pra isso. Em vez disso,
    // usamos esse flag: uma vez confirmado que chegou no embarque (ou que a
    // tela de espera com contagem regressiva apareceu), qualquer navegação
    // compacta depois disso já é "corrida", não "buscar". Reseta ao voltar
    // pra Aguardando/Buscando.
    private var nn99ReachedPickup = false
    // Rastreio de diagnóstico: qual foi o último gatilho que decidiu o
    // valor de nn99ReachedPickup (pedido do Yuri, 18/07/2026 — facilita
    // investigar troca de status depois, sem precisar reconstruir tudo
    // manualmente lendo texto cru).
    private var nn99ReachedPickupReason = "init"

    override fun onServiceConnected() {
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                         AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                         AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100   // o mais rápido possível — oferta expira em segundos
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        serviceInfo = info
        log("\n═══════════════ SERVIÇO CONECTADO v21 ${fmt.format(Date())} ═══════════════")
        // Carrega o cache local das regras de detecção na hora (instantâneo)
        // e já dispara um fetch forçado em background pra pegar qualquer
        // ajuste feito no Supabase desde a última vez que o app abriu —
        // sem isso, um ajuste de regra só valeria depois de até 10min.
        RuleEngine.ensureLoaded(this)
        RuleEngine.refreshIfDue(this, force = true)
        // Leitura por TEMPO, não só por evento — a tela de oferta pode ser
        // canvas puro e não disparar TYPE_WINDOW_CONTENT_CHANGED nenhum.
        // É assim (por polling) que apps como o Gigu conseguem ler rápido.
        main.post(pollRunnable)
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            try { pollForeground() } catch (_: Exception) {}
            main.postDelayed(this, 600)
        }
    }

    private fun pollForeground() {
        // BUG CONFIRMADO EM ANÁLISE REAL (13/07/2026): pegar a primeira janela
        // do loop (ordem de z-order) fazia uma PiP pequena da 99/Uber — que
        // fica no topo do z-order por natureza, mesmo ocupando um cantinho
        // da tela — vencer por engano sobre o app que realmente domina a
        // tela visualmente. Resultado: fgPlat marcado como "99" enquanto o
        // OCR (que fotografa a tela inteira) lia texto quase todo da Uber,
        // gerando cards e logs com platform errado. Agora escolhe pela MAIOR
        // área visível entre as janelas candidatas, não pela primeira.
        var fgPlat: String? = null
        var bestArea = -1
        try {
            val rect = Rect()
            for (w in windows) {
                val wp = w.root?.packageName?.toString() ?: continue
                if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION || !w.isActive) continue
                val cand = when {
                    NN_PKGS.contains(wp)   -> "99"
                    UBER_PKGS.contains(wp) -> "UBER"
                    else -> null
                } ?: continue
                w.getBoundsInScreen(rect)
                val area = rect.width().coerceAtLeast(0) * rect.height().coerceAtLeast(0)
                if (area > bestArea) { bestArea = area; fgPlat = cand }
            }
        } catch (_: Exception) {}
        if (fgPlat != null) requestOcrPass(fgPlat)

        // ── Status Online/Buscar/Corrida: por TEMPO, não só por evento ──────
        // Mesmo motivo do requestOcrPass já ser por polling: a tela de
        // navegação com mapa pode passar bastante tempo sem disparar
        // TYPE_WINDOW_CONTENT_CHANGED (o mapa em si não é uma view de texto
        // mudando), então depender só de onAccessibilityEvent deixava o
        // card preso em "Online" numa corrida real mesmo com o texto certo
        // já na tela. Roda a cada 600ms, igual o resto do Flash.
        //
        // MAS: enquanto tiver oferta ativa na tela (FlashCard mostrando —
        // caso real de corrida em andamento + nova oferta chegando antes de
        // encerrar a atual), essa varredura PARA por completo. Dois motivos:
        // 1) o texto do card de oferta pode ser lido junto com o da tela de
        //    navegação por baixo, fazendo o status oscilar (Corrida→Online→
        //    Corrida) sem debounce nunca fechar de verdade.
        // 2) rodar collectTexts() (varredura recursiva da árvore inteira)
        //    no mesmo tick de 600ms que o OCR do Flash sobrecarrega a thread
        //    principal e é o que tava causando o FlashCard piscar. Status do
        //    botão flutuante nunca deve competir com o Flash pela UI thread.
        if (offerGuardActive()) return
        when (fgPlat) {
            "UBER" -> scanUberTripState()
            "99"   -> scanNN99TripState()
        }
    }

    // Varre só as janelas da 99, mesmo princípio do scanUberTripState.
    private fun scanNN99TripState() {
        val texts = ArrayList<String>()
        try {
            for (w in windows) {
                val r = w.root ?: continue
                val wp = r.packageName?.toString() ?: continue
                if (!NN_PKGS.contains(wp)) continue
                collectTexts(r, texts)
            }
        } catch (_: Exception) {}
        if (texts.isNotEmpty()) detectAndApply99TripSubState(texts)
    }

    // Varre só as janelas da Uber (independente de evento) e roda a mesma
    // detecção com debounce de sempre — dá pra chamar em loop sem medo,
    // detectAndApplyTripSubState já é seguro pra repetição. Nunca chamado
    // com oferta ativa (ver guard em pollForeground).
    private fun scanUberTripState() {
        val texts = ArrayList<String>()
        try {
            for (w in windows) {
                val r = w.root ?: continue
                val wp = r.packageName?.toString() ?: continue
                if (!UBER_PKGS.contains(wp)) continue
                collectTexts(r, texts)
            }
        } catch (_: Exception) {}
        if (texts.isNotEmpty()) detectAndApplyTripSubState(texts)
    }


    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val evPkg = event.packageName?.toString() ?: ""

        // ── Notificações da 99/Uber: a oferta pode chegar por aqui com valor ──
        if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            if (CAPTURE_PKGS.contains(evPkg) && !GIGU_PKGS.contains(evPkg)) {
                handleNotification(evPkg, event)
            }
            return
        }

        val now = System.currentTimeMillis()
        // Se o evento veio de um dos apps capturados, varre sempre.
        // Se veio de QUALQUER outro app, ainda varre (a oferta pode ser janela
        // da 99 desenhada por cima de outro app) — mas no máx. 1x por segundo.
        val fromCaptured = CAPTURE_PKGS.contains(evPkg)
        if (!fromCaptured && now - lastScanMs < 1000) return
        lastScanMs = now

        // ── Varre TODAS as janelas visíveis, texto separado por pacote,
        //    e coleta metadados das janelas pra diagnóstico ──
        val textsByPkg = LinkedHashMap<String, ArrayList<String>>()
        val pkgArea = HashMap<String, Int>()
        val winMeta = ArrayList<String>()
        var nnWindowSeen = false
        var nnIsForeground = false
        var nnNodeCount = 0
        try {
            val rect = Rect()
            for (w in windows) {
                val r = w.root
                val wp = r?.packageName?.toString()
                val meta = "type=${winTypeName(w.type)} layer=${w.layer} pkg=${wp ?: "null"} active=${w.isActive} focus=${w.isFocused}"
                winMeta.add(meta)
                if (r == null || wp == null) continue
                if (CAPTURE_PKGS.contains(wp)) {
                    val lst = textsByPkg.getOrPut(wp) { ArrayList() }
                    val before = lst.size
                    collectTexts(r, lst)
                    w.getBoundsInScreen(rect)
                    val area = rect.width().coerceAtLeast(0) * rect.height().coerceAtLeast(0)
                    if (area > (pkgArea[wp] ?: 0)) pkgArea[wp] = area
                    if (NN_PKGS.contains(wp)) {
                        nnWindowSeen = true; nnNodeCount += (lst.size - before)
                        if (w.type == AccessibilityWindowInfo.TYPE_APPLICATION && w.isActive) nnIsForeground = true
                    }
                }
            }
        } catch (_: Exception) {}

        if (textsByPkg.isEmpty() && fromCaptured) {
            val root = rootInActiveWindow
            val rp = root?.packageName?.toString()
            if (root != null && rp != null && CAPTURE_PKGS.contains(rp)) {
                val lst = ArrayList<String>()
                collectTexts(root, lst)
                textsByPkg[rp] = lst
            }
        }
        if (textsByPkg.isEmpty()) return

        // Mesma correção do fgPlat em pollForeground: escolhe pela MAIOR área
        // visível entre Uber/99, não pela primeira encontrada (que favorecia
        // uma PiP pequena por causa do z-order, mesmo com a Uber ocupando
        // quase toda a tela por baixo).
        val realPkg = textsByPkg.keys
            .filter { UBER_PKGS.contains(it) || NN_PKGS.contains(it) }
            .maxByOrNull { pkgArea[it] ?: 0 }
        val realPlat = when {
            realPkg != null && UBER_PKGS.contains(realPkg) -> "UBER"
            realPkg != null && NN_PKGS.contains(realPkg)   -> "99"
            else -> null
        }
        val realTexts = realPkg?.let { textsByPkg[it] } ?: ArrayList()

        if (realPlat != null && realTexts.isNotEmpty()) {
            processRealOffer(realPlat, realTexts)
        } else if (realPlat == null) {
            hideFlashIfActive()
        }

        // ── Status Online/Buscar/Corrida no widget flutuante ────────────────
        // Uber e 99 já confirmados em log real (ver detectAndApplyTripSubState
        // e detectAndApply99TripSubState). Quando não estamos lendo texto de
        // nenhuma das duas (app em 2º plano, ex: motorista foi pro Waze durante
        // a corrida), simplesmente não chama nada — o último estado confirmado
        // permanece, porque a corrida continua valendo mesmo com o app fora da
        // tela.
        //
        // Guard igual ao do pollForeground: com oferta ativa (lastFlashSig
        // setado pelo processRealOffer logo acima), o texto da tela pode vir
        // misturado com o card de oferta — não mexe no status nesse momento.
        if (!offerGuardActive()) {
            if (realPlat == "UBER" && realTexts.isNotEmpty()) {
                detectAndApplyTripSubState(realTexts)
            } else if (realPlat == "99" && realTexts.isNotEmpty()) {
                detectAndApply99TripSubState(realTexts)
            }
        }


        // ── Log combinado (throttle + dedup) ──
        if (now - lastLogMs < 700) return
        val plat = when {
            UBER_PKGS.contains(evPkg) -> "UBER"
            NN_PKGS.contains(evPkg)   -> "99"
            GIGU_PKGS.contains(evPkg) -> "GIGU"
            realPlat != null -> realPlat
            else -> "OUTRO"
        }
        // BUG CONFIRMADO EM ANÁLISE REAL (13/07/2026): antes disso, o texto
        // gravado era a MISTURA de todos os pacotes visíveis
        // (textsByPkg.values.forEach — Uber E 99 juntos, quando o motorista
        // roda os dois apps ao mesmo tempo, um deles em PiP/miniatura), mas
        // a linha era marcada só com a plataforma do evento que disparou.
        // Resultado: linhas (e flash_card_snapshots — os cards reais
        // exibidos na tela) marcadas platform=99 com conteúdo 100% Uber,
        // e vice-versa. Agora escopa o texto ao MESMO pacote que definiu
        // 'plat', nunca mistura dois apps na mesma linha.
        val platPkg = when (plat) {
            "UBER" -> UBER_PKGS.firstOrNull { textsByPkg.containsKey(it) } ?: evPkg
            "99"   -> NN_PKGS.firstOrNull { textsByPkg.containsKey(it) } ?: evPkg
            else   -> evPkg
        }
        val allTexts = ArrayList(textsByPkg[platPkg] ?: emptyList())
        if (allTexts.isEmpty()) return
        val sig = plat + "|" + allTexts.take(8).joinToString("|")
        if (sig == lastSig) return
        lastSig = sig
        lastLogMs = now

        val joined = allTexts.joinToString("  ")
        val low = joined.lowercase(Locale.getDefault())
        // Diagnóstico temporário (17/07/2026, ver nn99Debug* perto de
        // detectAndApply99TripSubState): substitui o "state" cosmético por
        // um snapshot do caminho de decisão real, só pra 99. Reaproveita
        // esse mesmo log já throttled — não grava linha extra nenhuma.
        val state = if (plat == "99") {
            "RPantes=$nn99DebugRPBefore RPdepois=$nn99DebugRPAfter addrMudou=$nn99DebugAddrChanged online=$nn99DebugCurrentlyOnline regra=$nn99DebugMatchedRule gatilhoRP=$nn99ReachedPickupReason raw=$nn99DebugRaw"
        } else inferState(low)
        val money = extractMoney(joined)
        val km = extractKm(low)
        val min = extractMin(low)
        if (state != "?") lastState = state

        val sb = StringBuilder()
        sb.append("\n───── [$plat] ${fmt.format(Date())} estado=$state ev=$evPkg\n")
        if (money.isNotEmpty()) sb.append("   R\$: ${money.joinToString(", ")}\n")
        winMeta.forEach { sb.append("   WIN $it\n") }
        allTexts.forEachIndexed { i, t -> sb.append("   [$i] $t\n") }
        log(sb.toString())

        val winTag = "${event.className ?: ""} :: ev=$evPkg :: wins=${winMeta.joinToString(" || ")}"
        sendToCloud(plat, evPkg, winTag, state, money, km, min, allTexts)
    }

    private fun winTypeName(t: Int) = when (t) {
        AccessibilityWindowInfo.TYPE_APPLICATION -> "APP"
        AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "IME"
        AccessibilityWindowInfo.TYPE_SYSTEM -> "SYS"
        AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "A11Y_OVR"
        AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "SPLIT"
        else -> "T$t"
    }

    // ── OCR: a 99 desenha a oferta como imagem. Captura a tela (se o
    //    usuário autorizou a gravação) e roda o mesmo parser sobre as
    //    linhas reconhecidas. Throttle de 900ms, e descartado se um OCR
    //    já está rodando (velocidade > completude). ──
    private var lastOcrMs = 0L
    private var lastOcrLogMs = 0L
    private var lastOcrMissMs = 0L
    private fun requestOcrPass(plat: String) {
        val svc = ScreenOcrService.instance
        if (svc == null || !ScreenOcrService.isActive) {
            // Sem isso não dá pra saber se o problema é permissão ou parser —
            // loga no máx a cada 5s pra não inundar.
            val now = System.currentTimeMillis()
            if (now - lastOcrMissMs > 5000) {
                lastOcrMissMs = now
                sendToCloud(plat, "ocr", "OCR_INATIVO", "OCR_SEM_PERMISSAO", emptyList(), null, null, emptyList())
            }
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastOcrMs < 600) return
        lastOcrMs = now
        svc.captureAndRecognize({ lines, bmp ->
            if (lines.isEmpty()) {
                bmp?.recycle()
                if (System.currentTimeMillis() - lastOcrLogMs > 1500) {
                    lastOcrLogMs = System.currentTimeMillis()
                    sendToCloud(plat, "ocr", "OCR_VAZIO", "OCR_SEM_LINHAS", emptyList(), null, null, emptyList())
                }
                return@captureAndRecognize
            }
            val joined = lines.joinToString("  ")
            val low = joined.lowercase(Locale.getDefault())
            val isOffer = isOfferScreen(low)
            // Ponte OCR -> status (só 99): ver checkNn99OcrStatusBridge() pra
            // explicação completa. Roda em toda passada, independente de ser
            // tela de oferta ou não.
            checkNn99OcrStatusBridge(plat, joined)
            // Modo diagnóstico: loga TODA passada de OCR, achando oferta ou
            // não — é a única forma de descobrir o padrão real da tela sem
            // continuar chutando. Throttle 1.5s pra não inundar o Supabase.
            if (System.currentTimeMillis() - lastOcrLogMs > 1500) {
                lastOcrLogMs = System.currentTimeMillis()
                sendToCloud(plat, "ocr", if (isOffer) "OCR_OFERTA" else "OCR_TELA_NORMAL",
                    if (isOffer) "OFERTA_OCR" else "OCR_SEM_OFERTA",
                    extractMoney(joined), extractKm(low), extractMin(low), lines)
            }
            // Chama sempre (não só quando isOffer==true): é a própria função
            // que decide mostrar OU esconder o card, e precisa rodar em toda
            // leitura pra esconder rápido quando a oferta some da tela.
            // O bitmap só é usado (e reciclado) dentro de processRealOffer,
            // exatamente no momento em que um card é lançado — nunca antes.
            processRealOffer(plat, lines, bmp)
        }, { err ->
            if (System.currentTimeMillis() - lastOcrLogMs > 1500) {
                lastOcrLogMs = System.currentTimeMillis()
                sendToCloud(plat, "ocr", "OCR_ERRO: $err", "OCR_ERRO", emptyList(), null, null, emptyList())
            }
        })
    }

    // ── Notificação da 99/Uber: extrai título/texto e tenta oferta ──
    private fun handleNotification(pkg: String, event: AccessibilityEvent) {
        val texts = ArrayList<String>()
        try { event.text?.forEach { if (!it.isNullOrBlank()) texts.add(it.toString()) } } catch (_: Exception) {}
        try {
            val n = event.parcelableData as? Notification
            if (n != null) {
                n.extras?.getCharSequence(Notification.EXTRA_TITLE)?.let { texts.add("(title) $it") }
                n.extras?.getCharSequence(Notification.EXTRA_TEXT)?.let { texts.add("(text) $it") }
                n.extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.let { texts.add("(big) $it") }
            }
        } catch (_: Exception) {}
        if (texts.isEmpty()) return
        val plat = if (UBER_PKGS.contains(pkg)) "UBER" else "99"
        log("\n───── [NOTIF $plat] ${fmt.format(Date())}\n" + texts.joinToString("\n") { "   $it" })
        sendToCloud(plat, pkg, "NOTIFICATION", "NOTIF", extractMoney(texts.joinToString("  ")), null, null, texts)
        // Se a notificação tiver dado suficiente, tenta o card por ela também
        processRealOffer(plat, texts)
    }

    // ── Parser de oferta com base nos prints reais da 99 ──
    // kmPickup/kmTrip/minPickup/minTrip: pernas SEPARADAS (não somadas) —
    // confirmado em print real da Uber (15/07/2026): "7 min (2.5 km)" até
    // buscar + "3 minutos (0.9 km)" até o destino, sempre nessa ordem (1ª
    // perna lida = buscar, 2ª = corrida). km/min continuam sendo a SOMA das
    // duas, usados só pro card visual (Flash).
    private data class Offer(
        val valor: Double?, val km: Double?, val min: Int?,
        val rkmDirect: Double?, val nota: Double?,
        val origem: String? = null, val destino: String? = null,
        val legsFound: Int = 0,
        val kmPickup: Double? = null, val kmTrip: Double? = null,
        val minPickup: Int? = null, val minTrip: Int? = null,
        val dinamico: Double = 0.0,
        val corridas: Int? = null, val paradas: Int = 0
    )

    private fun parseOffer(texts: List<String>): Offer {
        val joined = texts.joinToString("  ")
        // O OCR troca "1" por "l" (L minúsculo) ou "I" com frequência —
        // foi essa confusão que fazia pernas como "5 min (1.4 km)" virarem
        // "5 min (l.4 km)" numa leitura e sumirem (regex exige dígito),
        // causando o km/tempo oscilar entre "com 1 perna" e "com 2 pernas"
        // na MESMA oferta. Corrige antes de qualquer outra extração.
        val low = joined.lowercase(Locale.getDefault())
            .replace(Regex("""\b[lI](?=[.,]\d)"""), "1")   // "l.4" / "I,4" → "1.4"
            .replace(Regex("""(?<=\d[.,])[lI]\b"""), "1")  // "4.l" / "4,I" → "4.1"

        // valor: "Aceitar por R$12,06" é a âncora mais confiável
        var valor: Double? = null
        Regex("""aceitar por\s*r\$\s*([\d.]+,\d{2})""").find(low)?.let {
            valor = moneyToDouble(it.groupValues[1])
        }

        // R$/km direto: "R$3,55/km"
        val rkmDirect = Regex("""r\$\s*([\d.]+,\d{2})\s*/\s*km""").find(low)?.let {
            moneyToDouble(it.groupValues[1])
        }

        // pernas — 3 formatos confirmados em prints reais:
        //  99 tipo A ("Aceitar por"):    "8min (2,6km)"      — min fora, dist dentro dos parênteses
        //  99 tipo B ("Escolher"):       "(10 min 3,1 km)"   — min E dist dentro dos mesmos parênteses
        //  Uber ("Selecionar"):          "4 min (1.2 km)" / "5 minutos (1.6 km)" — "minutos" por extenso, ponto decimal
        var totMin = 0; var totKm = 0.0; var legs = 0
        // Ordem de leitura preservada: legsList[0] = 1ª perna (buscar),
        // legsList[1] = 2ª perna (corrida) — é assim que a tela mostra,
        // sempre buscar primeiro, corrida embaixo.
        val legsList = ArrayList<Pair<Int, Double>>()
        fun addLeg(minsStr: String, distStr: String, unit: String) {
            val mins = minsStr.toIntOrNull() ?: return
            val dist = distStr.replace(",", ".").toDoubleOrNull() ?: return
            val km2 = if (unit == "m") dist / 1000.0 else dist
            if (mins in 1..90 && km2 in 0.05..80.0) { totMin += mins; totKm += km2; legs++; legsList.add(mins to km2) }
        }
        Regex("""(\d{1,3})\s*min(?:utos)?\s*\((\d+(?:[.,]\d+)?)\s*(m|km)\)""").findAll(low)
            .forEach { addLeg(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }
        Regex("""\(\s*(\d{1,3})\s*min(?:utos)?\s+(\d+(?:[.,]\d+)?)\s*(m|km)\s*\)""").findAll(low)
            .forEach { addLeg(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }

        var km: Double? = if (legs > 0 && totKm > 0) totKm else null
        val min: Int? = if (legs > 0 && totMin > 0) totMin else extractMin(low)?.toIntOrNull()
        val kmPickup = legsList.getOrNull(0)?.second
        val kmTrip = legsList.getOrNull(1)?.second
        val minPickup = legsList.getOrNull(0)?.first
        val minTrip = legsList.getOrNull(1)?.first

        // dinâmico: CONFIRMADO EM PRINT REAL (13/07/2026) — notificação com
        // "Origem: S R$1,45 Tarifa base dinâmica incl." (ver looksLikeAddress
        // abaixo, mesmo caso que ensinou a rejeitar essa linha como endereço).
        val dinamico = Regex("""r\$\s*([\d.]+,\d{2})\s*tarifa base din[aâ]mica""").find(low)?.let {
            moneyToDouble(it.groupValues[1])
        } ?: 0.0

        // fallback de valor: se não achou "aceitar por", pega o R$ que for
        // consistente com rkm direto × km (evita pegar ganhos do dia) — é
        // esse caminho que resolve o tipo B (99 "Escolher") e a Uber
        // ("Selecionar"), que não têm o texto "aceitar por"
        if (valor == null) {
            val monies = extractMoney(joined).mapNotNull { moneyToDouble(it) }.filter { it > 0 }
            valor = if (rkmDirect != null && km != null) {
                monies.firstOrNull { kotlin.math.abs(it / km!! - rkmDirect) / rkmDirect < 0.35 }
            } else if (km != null && km > 0) {
                // BUG CONFIRMADO EM LOG REAL (13/07/2026, corrida do Raymundo):
                // sem R$/km direto, o antigo "pega o primeiro R$ que aparecer"
                // pegava o GANHO TOTAL DO DIA (ex: "R$ 54,01", fixo no topo da
                // tela de navegação) como se fosse o valor da oferta nova que
                // chegou por cima — só acontece em corrida, onde a tela de
                // fundo tem esse valor sempre visível junto do card. Faixa
                // generosa de R$/km (cobre de promoção barata a dinâmica alta)
                // descarta esse tipo de valor de fundo antes de aceitar.
                monies.firstOrNull { (it / km!!) in 0.4..12.0 } ?: monies.firstOrNull()
            } else monies.firstOrNull()
        }

        // fallback de km: valor / rkm direto
        if (km == null && valor != null && rkmDirect != null && rkmDirect > 0) {
            km = valor!! / rkmDirect
        }

        // nota: procura o número 1..5 com 2 casas NA MESMA LINHA que menciona
        // "corrida(s)" — não no texto inteiro (senão pega R$/km por engano,
        // que também cai na faixa 1..5, tipo "R$1,31/km"). Uber também usa
        // formato "4,90 (1985)" — número + parênteses com contagem.
        // corridas: o total de corridas do passageiro vem NA MESMA LINHA da
        // nota (99: "4,80 630 corridas" — número solto antes da palavra;
        // Uber: "4,90 (1985)" — número dentro dos parênteses). Lê os dois
        // juntos, já que sempre aparecem colados na mesma linha.
        var nota: Double? = null
        var corridas: Int? = null
        for (line in texts) {
            val l = line.lowercase(Locale.getDefault())
            if (l.contains("corrid") || l.contains("corda") || Regex("""[1-5][.,]\d{2}\s*\(\d+\)""").containsMatchIn(l)) {
                Regex("""([1-5][.,]\d{2})""").find(line)?.let {
                    nota = it.groupValues[1].replace(",", ".").toDoubleOrNull()
                }
                if (nota != null) {
                    Regex("""(\d+)\s*corrid""").find(l)?.let { corridas = it.groupValues[1].toIntOrNull() }
                    if (corridas == null) Regex("""\(\s*(\d+)\s*\)""").find(line)?.let {
                        corridas = it.groupValues[1].toIntOrNull()
                    }
                    break
                }
            }
        }

        // paradas: exige um dígito colado antes de "parada(s)" — evita bater
        // em nome de lugar tipo "Parada Modelo" que não tem número na frente.
        // Só um sinal booleano por enquanto (não distingue 1 de 2+ paradas).
        var paradas = 0
        for (line in texts) {
            Regex("""\b(\d{1,2})\s*paradas?\b""", RegexOption.IGNORE_CASE).find(line)?.let {
                paradas = it.groupValues[1].toIntOrNull() ?: 0
            }
        }

        // endereços: a linha logo depois de uma "perna" (tempo+distância)
        // costuma ser o endereço por extenso — 1ª perna = origem, 2ª = destino.
        // Pula linhas curtas/genéricas ("1 parada", "X Não afeta a TA" etc.)
        // que às vezes aparecem entre a perna e o endereço de verdade —
        // foi uma delas ("1 parada", às vezes lida "l parada") que virava
        // origem por engano na notificação.
        fun looksLikeAddress(s: String): Boolean {
            val sl = s.lowercase(Locale.getDefault()).trim()
            if (sl.length < 6) return false
            if (sl.contains("parada")) return false
            if (sl.contains("não afeta") || sl.contains("nao afeta")) return false
            // BUG CONFIRMADO EM PRINT REAL (notificação "Para: 5,3 km"): o
            // regex antigo só rejeitava km/min INTEIROS ("5 km"), não decimais
            // ("5,3 km") — deixava passar distância solta como se fosse
            // endereço. Adicionado ([.,]\d+)? pra cobrir a parte decimal.
            if (Regex("""^\(?\d{1,3}([.,]\d+)?\s*(min|km|m)\b""").containsMatchIn(sl)) return false // outra perna/distância solta
            if (Regex("""^[x%\d.,\s]+$""").containsMatchIn(sl)) return false // só símbolo/número solto
            // BUG CONFIRMADO EM PRINTS REAIS (13/07/2026): a notificação saiu
            // com "Origem: S R$1,45 Tarifa base dinâmica incl." e "Destino:
            // Online" — texto de bônus/dinâmico e de status sendo lido como
            // se fosse endereço. Endereço de verdade nunca tem "R$" nem essas
            // palavras — rejeita explicitamente.
            if (sl.contains("r$")) return false // endereço nunca tem valor em R$
            if (sl.contains("tarifa") || sl.contains("incl.")) return false
            if (sl.contains("taxa de espera") || sl.contains("espera longa")) return false
            if (sl == "online" || sl == "buscando" || sl == "offline" || sl == "conectar") return false
            if (sl.contains("perfil essencial") || sl.contains("perfil premium") || sl.contains("perfil prata")) return false
            if (sl.contains("pgto. no app") || sl == "dinheiro" || sl == "negocia" || sl == "qr code") return false
            return true
        }
        val legLineRe = Regex("""^\(?\s*\d{1,3}\s*min(?:utos)?""", RegexOption.IGNORE_CASE)
        val addrCandidates = ArrayList<String>()
        for (i in texts.indices) {
            if (legLineRe.containsMatchIn(texts[i])) {
                for (j in (i + 1)..minOf(i + 3, texts.size - 1)) {
                    val cand = texts.getOrNull(j)?.trim() ?: break
                    val nl = cand.lowercase(Locale.getDefault())
                    if (legLineRe.containsMatchIn(cand)) break // já é a próxima perna, para de procurar
                    if (nl.contains("aceitar") || nl.contains("selecionar") || nl.contains("escolher")) break
                    if (looksLikeAddress(cand)) { addrCandidates.add(cand); break }
                }
            }
        }
        val origem = addrCandidates.getOrNull(0)
        val destino = addrCandidates.getOrNull(1)

        return Offer(valor, km, min, rkmDirect, nota, origem, destino, legs, kmPickup, kmTrip, minPickup, minTrip, dinamico, corridas, paradas)
    }

    private fun isOfferScreen(low: String): Boolean {
        // 99 tipo A: "Aceitar por R$..." — o mais confiável
        if (low.contains("aceitar por")) return true
        if (low.contains("taxa de serviço") && Regex("""\d+\s*min\s*\(""").containsMatchIn(low)) return true
        // 99 tipo B: tela "Solicitações" com botão "Escolher" (sem valor de aceite explícito)
        if (low.contains("escolher") && Regex("""r\$\s*[\d.]+,\d{2}""").containsMatchIn(low) &&
            Regex("""\(\s*\d{1,3}\s*min""").containsMatchIn(low)) return true
        // Uber: botão "Selecionar", com valor e R$/km — não usa "aceitar"/"recusar"
        if (low.contains("selecionar") && Regex("""r\$\s*[\d.]+,\d{2}""").containsMatchIn(low) &&
            Regex("""\d{1,3}\s*min""").containsMatchIn(low)) return true
        // 99 tipo C: card SEM botão nenhum (toca o card inteiro pra aceitar
        // — segundo o usuário, esse é o formato mais comum). Sem palavra de
        // ação pra ancorar, então detecta pela estrutura inteira da tela:
        // valor + R$/km explícito + nota-com-corridas + pelo menos 1 perna.
        if (Regex("""r\$\s*[\d.]+,\d{2}\s*/\s*km""").containsMatchIn(low) &&
            Regex("""[1-5][.,]\d{2}\s*[·(]|\bcorrid""").containsMatchIn(low) &&
            Regex("""\d{1,3}\s*min""").containsMatchIn(low)) return true
        // Uber tipo B: botão "Aceitar" sozinho (sem "por R$", sem R$/km
        // visível na tela) — âncora na nota com contagem entre parênteses,
        // que é a assinatura de layout da Uber ("4,94 (1910)")
        if (low.contains("aceitar") && !low.contains("aceitar por") &&
            Regex("""r\$\s*[\d.]+,\d{2}""").containsMatchIn(low) &&
            Regex("""[1-5][.,]\d{2}\s*\(\d+\)""").containsMatchIn(low) &&
            Regex("""\d{1,3}\s*min""").containsMatchIn(low)) return true
        // Fallback genérico antigo (mantido por segurança)
        if (low.contains("aceitar") && (low.contains("recusar") || low.contains("combinar") || low.contains("dispensar"))) return true
        return false
    }

    // ── MōB Flash real: só usa o texto do próprio app (99/Uber) ──
    private fun processRealOffer(plat: String, texts: List<String>, bmp: Bitmap? = null) {
        // PEDIDO (13/07/2026): Flash só deve ficar ativo com a jornada
        // iniciada — antes disso ele rodava independente, mesmo sem GPS
        // ligado. Isso também cria um lembrete natural: se o motorista
        // esquecer de iniciar a jornada e não ver o card numa oferta, ele
        // vai lembrar de ligar a jornada primeiro. GpsService.isRunning é
        // o mesmo flag nativo que isGpsRunning() expõe pro JS.
        if (!GpsService.isRunning) { bmp?.recycle(); hideFlashIfActive(); return }
        val cfg = loadFlashConfig()
        if (!cfg.optBoolean("enabled", true)) { bmp?.recycle(); hideFlashIfActive(); return }

        val joined = texts.joinToString("  ")
        val low = joined.lowercase(Locale.getDefault())

        if (!isOfferScreen(low)) {
            // Não esconde na primeira falha — ver comentário de offerMissStreak
            // na declaração. Só esconde depois de OFFER_HIDE_GRACE falhas
            // seguidas, o que ainda é rápido (~1.2s) mas tolera o ruído da
            // navegação por baixo durante corrida ativa.
            bmp?.recycle()
            offerMissStreak++
            if (lastFlashSig.isNotEmpty() && offerMissStreak >= OFFER_HIDE_GRACE) hideFlashIfActive()
            val state = inferState(low)
            if (state != "?") lastRealState = state
            return
        }
        offerMissStreak = 0
        lastRealState = "OFERTA"

        val offer = parseOffer(texts)
        val valor = offer.valor
        val km = offer.km
        val min = offer.min

        if (valor == null || valor <= 0.0 || valor > 2000.0 || km == null || km <= 0.0 || km > 150.0) {
            bmp?.recycle(); return
        }

        // Alimenta o buffer de captura automática com TODA leitura válida —
        // independente da lógica de estabilidade do card visual abaixo (essa
        // é só pra não "piscar" na tela; a captura quer o dado mais completo,
        // mesmo que só apareça numa releitura que o card visual descartou).
        AutoTripCapture.onOfferSeen(plat, AutoTripCapture.OfferSnapshot(
            value = valor, dinamico = offer.dinamico,
            kmPickup = offer.kmPickup, kmTrip = offer.kmTrip,
            durPickupSec = offer.minPickup?.let { it * 60 }, durTripSec = offer.minTrip?.let { it * 60 },
            origin = offer.origem, dest = offer.destino
        ))

        // A mesma oferta (mesmo valor, MESMA PLATAFORMA) não pode "regredir"
        // pra uma leitura com menos pernas do que uma que já conseguimos ler
        // certo — foi exatamente essa oscilação (1 perna ↔ 2 pernas) que
        // fazia o card piscar e trocar de km/tempo o tempo todo numa mesma
        // oferta. Escopado por plataforma (ver comentário na declaração das
        // variáveis) pra não cruzar Uber com 99 quando os dois rodam juntos.
        val prevValor = lastOfferValorByPlat[plat]
        if (prevValor != null && kotlin.math.abs(valor - prevValor) < 0.02) {
            val bestLegs = bestLegsForOfferByPlat[plat] ?: 0
            if (offer.legsFound < bestLegs) { bmp?.recycle(); return }
            if (offer.legsFound > bestLegs) bestLegsForOfferByPlat[plat] = offer.legsFound
        } else {
            lastOfferValorByPlat[plat] = valor
            bestLegsForOfferByPlat[plat] = offer.legsFound
        }

        val sig = "$plat|$valor|$km|$min|${offer.nota}"
        if (sig == lastFlashSig) { bmp?.recycle(); return }
        lastFlashSig = sig
        lastFlashSigSetMs = System.currentTimeMillis()

        val custoPorKm = cfg.optDouble("custoPorKm", 0.0)
        val kpisCfg = cfg.optJSONObject("kpis") ?: JSONObject()

        val rkm = offer.rkmDirect ?: (valor / km)
        val rhora = if (min != null && min > 0) valor / (min / 60.0) else null
        val rmin = if (min != null && min > 0) valor / min else null
        val custoConfigurado = custoPorKm > 0.0
        val lucro = if (custoConfigurado) valor - custoPorKm * km else null
        val margemPct = if (custoConfigurado && valor > 0) (lucro!! / valor) * 100.0 else null

        val values = mapOf(
            "rkm" to rkm, "rhora" to rhora, "rmin" to rmin,
            "nota" to offer.nota, "margem" to margemPct, "lucro" to lucro
        )

        val metrics = ArrayList<FlashCard.Metric>()
        val gradesForOverall = ArrayList<String>()
        for ((key, label) in KPI_ORDER) {
            val kCfg = kpisCfg.optJSONObject(key) ?: continue
            if (!kCfg.optBoolean("enabled", false)) continue
            val v = values[key] ?: continue
            val red = kCfg.optDouble("red", Double.NaN)
            val green = kCfg.optDouble("green", Double.NaN)
            if (red.isNaN() || green.isNaN()) continue
            val grade = when {
                v < red    -> "r"
                v >= green -> "g"
                else       -> "a"
            }
            // A "nota" (do passageiro) entra no card, mas NÃO conta pra cor
            // geral das bordas — só os KPIs financeiros decidem isso.
            if (key != "nota") gradesForOverall.add(grade)
            // PEDIDO (13/07/2026): % Lucro só mostrava o número, sem o
            // símbolo — bem fácil de confundir com outro KPI de relance.
            // Agora o "%" vai junto no valor grande, não só no rótulo
            // pequeno embaixo.
            val fmtVal = when (key) {
                "margem" -> "${v.toInt()}%"
                "lucro"  -> fmtBr(v)
                else     -> fmtBr(v)
            }
            metrics.add(FlashCard.Metric(label, fmtVal, grade))
        }

        val kpiGrade = when {
            gradesForOverall.contains("r") -> "r"
            gradesForOverall.contains("a") -> "a"
            else -> "g"
        }

        // PEDIDO (16-17/07/2026): grupo "Recusas" — recusa a corrida mesmo com
        // Indicadores financeiros bons. Todas podem valer ao mesmo tempo
        // (motivo junta todas as que bateram, separadas por " · ").
        // "corridas"/"nota" só bloqueiam quando o dado realmente veio (!= null)
        // — sem confirmação positiva, não recusa à toa por falha de OCR.
        val recusarComParada = cfg.optBoolean("recusarComParada", false)
        val recusas = cfg.optJSONObject("recusas") ?: JSONObject()
        val rNota = recusas.optJSONObject("nota")
        val rPassageiroNovo = recusas.optJSONObject("passageiroNovo")
        val rDistancia = recusas.optJSONObject("distancia")
        val rEndereco = recusas.optJSONObject("endereco")

        val motivos = ArrayList<String>()
        if (recusarComParada && offer.paradas > 0) motivos.add("Tem parada")
        if (rNota != null && rNota.optBoolean("enabled", false) && offer.nota != null
            && offer.nota < rNota.optDouble("value", 4.5)) motivos.add("Nota baixa")
        if (rPassageiroNovo != null && rPassageiroNovo.optBoolean("enabled", false) && offer.corridas != null
            && offer.corridas < rPassageiroNovo.optInt("value", 5)) motivos.add("Passageiro novo")
        if (rDistancia != null && rDistancia.optBoolean("enabled", false) && offer.kmPickup != null
            && offer.kmPickup > rDistancia.optDouble("value", 3.0)) motivos.add("Buscar longe")
        if (rEndereco != null && rEndereco.optBoolean("enabled", false)) {
            val bloqueados = rEndereco.optJSONArray("list")
            if (bloqueados != null && bloqueados.length() > 0) {
                val origemL = offer.origem?.lowercase(Locale.getDefault()) ?: ""
                val destinoL = offer.destino?.lowercase(Locale.getDefault()) ?: ""
                for (i in 0 until bloqueados.length()) {
                    // PEDIDO (17/07/2026): cada item agora é {label, type} (o app
                    // web já monta o label com rua OU bairro na frente, conforme o
                    // tipo). Compatibilidade com config antiga: se ainda vier como
                    // string solta, usa direto. Compara só o PRIMEIRO pedaço (antes
                    // da vírgula) — cidade/estado no rótulo são só contexto pra
                    // pessoa reconhecer na lista, não fazem parte da comparação,
                    // porque o Uber/99 nunca mostram endereço completo formatado
                    // assim na tela da oferta.
                    val rawLabel = bloqueados.optJSONObject(i)?.optString("label")
                        ?: bloqueados.optString(i, "")
                    val item = rawLabel.lowercase(Locale.getDefault()).trim()
                        .substringBefore(",").trim()
                    if (item.isEmpty()) continue
                    // PEDIDO (17/07/2026): "Avenida X" cadastrado não batia com
                    // "Av. X" na tela (e vice-versa) — tira o prefixo comum (de
                    // rua OU de bairro) e compara só o nome próprio que sobra,
                    // que não muda com a abreviação. Só usa o nome sem prefixo se
                    // sobrar coisa suficiente (>=4 letras) — "Rua A" virando só
                    // "a" bloquearia corrida à toa.
                    val bare = addrPrefixRe.replace(item, "").trim()
                    val matchKey = if (bare.length >= 4) bare else item
                    // Embarque OU destino — pedido explícito (16/07/2026): antes só
                    // olhava o embarque, mas o motorista quer bloquear pra onde a
                    // corrida VAI também, não só de onde ela sai.
                    if ((origemL.isNotEmpty() && origemL.contains(matchKey)) ||
                        (destinoL.isNotEmpty() && destinoL.contains(matchKey))) {
                        motivos.add("Endereço bloqueado"); break
                    }
                }
            }
        }
        // PEDIDO (17/07/2026): fonte do motivo no card subiu pro tamanho do
        // tempo/km (15.75sp — antes 9.5sp) pra ficar fácil de ler de relance.
        // Nesse tamanho, a frase completa não cabe mais — usa uma versão
        // curta só no CARD; o áudio continua com a frase inteira (não tem
        // limite de espaço nenhum ali).
        val motivosAbrev = mapOf(
            "Tem parada" to "PARADA",
            "Nota baixa" to "NOTA",
            "Passageiro novo" to "NOVO",
            "Buscar longe" to "LONGE",
            "Endereço bloqueado" to "LOCAL"
        )
        val motivosDistintos = motivos.distinct()
        val declineReason = if (motivosDistintos.isNotEmpty()) motivosDistintos.joinToString(" · ") else null
        val declineReasonShort = if (motivosDistintos.isNotEmpty())
            motivosDistintos.joinToString(" · ") { motivosAbrev[it] ?: it } else null
        val overallGrade = if (declineReason != null) "r" else kpiGrade
        // Move o corte de "sem métrica nenhuma pra mostrar" pra DEPOIS das
        // recusas (bug real corrigido 17/07/2026): antes esse corte vinha
        // ANTES do bloco de recusas — se o motorista desligasse todos os
        // Indicadores, o corte cortava a leitura inteira e as recusas nunca
        // rodavam, mesmo estando ligadas.
        if (metrics.isEmpty() && declineReason == null) { bmp?.recycle(); return }

        // Loga a oferta detectada (pra auditoria da precisão do parser)
        sendToCloud(plat, "offer-parser", "OFERTA_DETECTADA v=$valor km=$km min=$min rkm=$rkm nota=${offer.nota} corridas=${offer.corridas} paradas=${offer.paradas} kmPickup=${offer.kmPickup} motivo=$declineReason",
            "OFERTA", extractMoney(joined), km.toString(), min?.toString(), texts)

        // Print da tela + dados do card — EXATAMENTE no momento em que o card
        // é lançado (não quando a oferta chega). É esse par (imagem + números)
        // que permite auditar as inconsistências (perna faltando, valor
        // dobrando etc.) depois, comparando o que a tela mostrava com o que
        // o app calculou naquele instante.
        saveSnapshot(plat, bmp, overallGrade, metrics, min ?: 0, km, offer, texts)
        showRouteNotification(plat, offer)

        main.post {
            flashCard.show(
                platform = plat,
                overallGrade = overallGrade,
                metrics = metrics,
                totalMin = min ?: 0,
                totalKm = km,
                declineReason = declineReason,
                declineReasonShort = declineReasonShort,
                autoHideMs = 15000L
            )
        }
    }

    private fun loadFlashConfig(): JSONObject {
        val prefs = getSharedPreferences(GpsService.PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_FLASH_CONFIG_JSON, null) ?: return defaultFlashConfig()
        return try { JSONObject(raw) } catch (_: Exception) { defaultFlashConfig() }
    }

    private fun defaultFlashConfig(): JSONObject {
        return JSONObject().apply {
            put("enabled", true)
            put("custoPorKm", 0.0)
            // Parada extra continua fora do grupo "recusas" (pedido de
            // 16/07/2026, antes do agrupamento existir) — só um bool solto.
            put("recusarComParada", true)
            // PEDIDO (17/07/2026): "Indicadores" (métricas no card) e
            // "Recusas" (regras de bloqueio automático) viraram grupos
            // separados na tela do app. Nota do passageiro saiu dos
            // indicadores — não é mais um número colorido no card, agora só
            // funciona como recusa (nota abaixo do valor configurado).
            put("kpis", JSONObject().apply {
                put("rkm", JSONObject().apply { put("enabled", true); put("red", 1.2); put("green", 2.0) })
                put("rhora", JSONObject().apply { put("enabled", true); put("red", 25.0); put("green", 45.0) })
                put("rmin", JSONObject().apply { put("enabled", false); put("red", 0.6); put("green", 1.0) })
                put("margem", JSONObject().apply { put("enabled", true); put("red", 15.0); put("green", 40.0) })
                put("lucro", JSONObject().apply { put("enabled", true); put("red", 5.0); put("green", 15.0) })
            })
            put("recusas", JSONObject().apply {
                put("nota", JSONObject().apply { put("enabled", false); put("value", 4.5) })
                put("passageiroNovo", JSONObject().apply { put("enabled", true); put("value", 5) })
                put("distancia", JSONObject().apply { put("enabled", false); put("value", 3.0) })
                put("endereco", JSONObject().apply { put("enabled", true); put("list", org.json.JSONArray()) })
            })
        }
    }

    private fun hideFlashIfActive() {
        if (lastFlashSig.isNotEmpty()) {
            lastFlashSig = ""
            offerMissStreak = 0
            lastOfferValorByPlat.clear()
            bestLegsForOfferByPlat.clear()
            main.post { flashCard.hide() }
        }
    }

    // ── Detecta Online/Buscar/Corrida — Uber ──────────────────────────
    // Sinal original (11/07/2026): texto do botão de ação.
    //   "Iniciar {carro}"  → ainda não pegou o passageiro → Buscar
    //   "Encerrar {carro}" → já com passageiro             → Corrida
    //
    // BUG CONFIRMADO em log real (corrida da Thainara, mesmo dia): esse
    // botão some da leitura em duas variantes de tela reais — a versão
    // condensada/miniatura (troca de app no meio da corrida) e a versão
    // com menu/drawer aberto (mostra "Coletar pagamento" como item de
    // lista, sem o texto "Encerrar" em lugar nenhum). Isso fazia o status
    // cair pra "online" por engano no meio de uma corrida real (300+
    // leituras em 13min oscilando). "Destino de {nome}" é o único texto
    // confirmado presente em TODAS as variações — vira o sinal principal.
    // Os estados de cauda (perto do fim: troca pra "Coletar pagamento",
    // depois "Pagamento realizado", depois "Como foi a viagem?") também
    // contam como "corrida" — sem isso, o status voltaria pra "online"
    // antes da corrida realmente terminar. "Não é possível ficar offline"
    // só aparece com corrida ativa, serve de reforço.
    // "Avaliando oferta" (card de oferta na tela) NÃO vira estado à parte
    // de propósito — continua mostrando Online, pra não poluir o motorista
    // num momento de decisão rápida.
    // Os padrões de texto (encerrar/iniciar/destino de/etc), a ordem de
    // prioridade entre eles e o resultado de cada um NÃO estão mais fixos
    // aqui — moraram pra tabela `state_detection_rules` no Supabase (ver
    // RuleEngine.kt), plataforma 'uber'. Isso permite ajustar um padrão de
    // texto sem precisar gerar APK novo. O que continua aqui (estrutural,
    // não é um padrão de texto isolado) é só o filtro de vazamento do
    // próprio widget logo abaixo.

    // BUG CONFIRMADO EM ANÁLISE REAL (últimas 5 corridas Uber, 12-13/07/2026):
    // de 1556 leituras classificadas "online", 93,5% (1455) eram na verdade
    // o texto do NOSSO PRÓPRIO botão flutuante vazando pro OCR — não texto
    // da Uber. O botão é um overlay de sistema desenhado por cima de tudo;
    // a captura de tela pro OCR é baseada em pixel, não em árvore de
    // acessibilidade, então não distingue "isso é da Uber" de "isso é do
    // nosso próprio widget". Quando a leitura vem só com o rótulo do nosso
    // status + timer + km (ex: ["Buscar", "01:36", "31,7 km"]), sem nenhum
    // texto real da Uber junto, isso não deve nem contar pro debounce —
    // só ignora e espera a próxima leitura.
    private val widgetLeakLabelRe = Regex("""^(Online|Buscar|Corrida)$""", RegexOption.IGNORE_CASE)
    private val widgetLeakTimerRe = Regex("""^\d{1,2}:\d{2}$""")
    private val widgetLeakKmRe = Regex("""^\d+([.,]\d+)?\s*km$""", RegexOption.IGNORE_CASE)
    private fun looksLikeOwnWidgetLeak(texts: List<String>): Boolean {
        if (texts.isEmpty() || texts.size > 4) return false
        val hasLabel = texts.any { widgetLeakLabelRe.matches(it.trim()) }
        val hasTimerOrKm = texts.any { widgetLeakTimerRe.matches(it.trim()) || widgetLeakKmRe.matches(it.trim()) }
        return hasLabel && hasTimerOrKm
    }

    private fun detectAndApplyTripSubState(texts: List<String>) {
        if (looksLikeOwnWidgetLeak(texts)) return
        RuleEngine.ensureLoaded(this)
        RuleEngine.refreshIfDue(this)
        val ev = RuleEngine.evaluate("uber", texts, reachedPickup = false)
        // Uber não usa o flag reachedPickup (Destino de/Encerrar já
        // distinguem os estados sozinhos) — só o resultado importa aqui.
        val raw = if (ev.matched) ev.state else "online"
        applyTripSubStateDebounced(raw, "UBER")

        // ── Captura automática: nome do passageiro + endereço, Uber ──────
        // "Destino de {nome}" é o único texto confirmado presente em TODAS
        // as variações de tela da corrida (ver comentário acima) — mesma
        // âncora usada pro estado "corrida" serve pro nome, one-shot.
        val joined = texts.joinToString(" ")
        uberDestinoDeRe.find(joined)?.groupValues?.getOrNull(1)?.trim()?.let {
            if (it.isNotEmpty()) AutoTripCapture.setPassengerNameIfEmpty("UBER", it)
        }
        // Endereço mais completo visto na tela de navegação — funde com o
        // que já tinha da oferta (mais completo vence, ver AutoTripCapture).
        extractBestAddressCandidate(texts)?.let { addr ->
            when (raw) {
                "buscar"  -> AutoTripCapture.updateAddresses("UBER", addr, null)
                "corrida" -> AutoTripCapture.updateAddresses("UBER", null, addr)
            }
        }
        // Dinheiro: CONFIRMADO em corrida real — "Quanto você recebeu em
        // dinheiro?" só aparece como pergunta final, logo antes de
        // "Pagamento realizado". Nunca usa "O cliente pagará em dinheiro"
        // (aparece durante a navegação, não confirma que foi pago assim de
        // verdade) nem o rótulo da aba "Dinheiro" do Coletar pagamento.
        if (joined.contains("Quanto você recebeu em dinheiro", ignoreCase = true)) {
            AutoTripCapture.markCash("UBER")
        }
    }

    // ── Detecta Online/Buscar/Corrida — 99 ────────────────────────────
    // Confirmado em log real (várias corridas completas, 12-13/07/2026). Ao
    // contrário da Uber, a 99 tem uma tela de navegação compacta
    // (rua + distância + "Ir" + km/h) que é IDÊNTICA indo buscar o
    // passageiro e indo pro destino — só o endereço fixo no topo muda,
    // e comparar endereço é caro/frágil pra fazer aqui. Por isso usamos
    // nn99ReachedPickup: uma vez visto a tela de espera com contagem
    // regressiva ("Passaremos a cobrar uma taxa de espera..." + botão
    // "Iniciar corrida"), qualquer navegação compacta subsequente já
    // conta como corrida, não buscar.
    // Os padrões de texto (Cheguei no embarque, Chegue antes de, Buscando,
    // km/h, etc), a prioridade entre eles e se cada um liga o flag
    // nn99ReachedPickup NÃO estão mais fixos aqui — moraram pra tabela
    // `state_detection_rules` no Supabase (plataforma '99'), via
    // RuleEngine.kt. O que continua em Kotlin (estrutural, precisa de
    // estado entre leituras, não é um padrão de texto isolado):
    //   • o heurístico de troca de endereço abaixo
    //   • a rede de segurança por timeout (o número em si é configurável
    //     via `state_detection_config`, mas a lógica de "há quanto tempo
    //     sem sinal" fica aqui)
    private var nn99KnownDestAddr: String? = null
    private var nn99LastActiveSignalMs = 0L

    // ── Diagnóstico temporário (17/07/2026) ──────────────────────────────
    // Pra investigar o bug de sobreposição (Corrida virando Buscar sem
    // motivo aparente no texto) — grava o CAMINHO DE DECISÃO completo, não
    // só o resultado final. Reaproveita o log já existente (throttled a
    // 700ms), não aumenta volume. Remover depois que o bug for resolvido.
    private var nn99DebugRPBefore = false
    private var nn99DebugRPAfter = false
    private var nn99DebugAddrChanged = false
    private var nn99DebugCurrentlyOnline = false
    private var nn99DebugMatchedRule = "nenhuma"
    private var nn99DebugRaw = "?"

    // ── 99: ponte OCR -> status quando a corrida termina ────────────────
    // CONFIRMADO EM LOG REAL (15/07/2026, corrida da Jessica): a tela de
    // avaliação ("Como foi sua corrida"/"Avaliar como anônimo") só chega
    // pela leitura de OCR — nunca pela acessibilidade, que fica muda nesse
    // trecho. "Buscando" também só chega por OCR.
    // AJUSTE (15/07/2026): a rede de segurança por tempo foi REMOVIDA
    // (ver detectAndApply99TripSubState). Motivo: reduzida pra 30s a
    // pedido, ela passou a disparar durante a espera legítima pelo
    // passageiro (tela de chat/"Passaremos a cobrar taxa de espera" não
    // bate nenhuma regra pela via limpa, então >30s ali é normal, não
    // corrida encerrada) — confirmado em log real, motorista ainda
    // buscando, status caiu pra Online sozinho.
    // Hoje a saída de Buscar/Corrida pra Online funciona assim:
    //   • Buscar -> Online: fica de olho no "Buscando" o tempo todo,
    //     sem gatilho nenhum (cobre cancelamento antes de pegar o
    //     passageiro, que nunca passaria pela avaliação)
    //   • Corrida -> Online: só depois de ver a avaliação (arma a escuta
    //     do "Buscando") — cobre o fim normal da corrida (~93% dos casos)
    private var nn99WaitingBuscandoViaOcr = false
    private var nn99WaitingBuscandoSinceMs = 0L
    private val NN99_WAITING_BUSCANDO_TIMEOUT_MS = 5 * 60_000L
    private val nn99AvaliacaoOcrRe = Regex(
        """Como foi sua corrida|Avaliar como anônimo""", RegexOption.IGNORE_CASE
    )
    private val nn99BuscandoOcrRe = Regex("""Buscando""", RegexOption.IGNORE_CASE)
    private val nn99CobrarPagamentoRe = Regex("""Cobrar pagamento""", RegexOption.IGNORE_CASE)
    private val nn99FinalizarCorridaRe = Regex("""Finalizar corrida""", RegexOption.IGNORE_CASE)

    // Liga a escuta de "Buscando" via OCR — hoje só chamado pelo gatilho
    // da avaliação. (Gatilho por notificação de "corrida cancelada" foi
    // testado e removido a pedido em 15/07/2026 — cobria o ~7% de corridas
    // que não passam pela avaliação, mas foi tirado por decisão do Yuri.)
    private fun nn99ArmBuscandoListener() {
        if (nn99WaitingBuscandoViaOcr) return
        nn99WaitingBuscandoViaOcr = true
        nn99WaitingBuscandoSinceMs = System.currentTimeMillis()
    }

    private val nn99ChegueAntesOcrRe = Regex("""Chegue antes de\s*\d{1,2}:\d{2}""", RegexOption.IGNORE_CASE)

    private fun checkNn99OcrStatusBridge(plat: String, joinedOcrText: String) {
        if (plat != "99") return

        // AJUSTE (16/07/2026, a pedido): enquanto o status confirmado for
        // "buscar", fica de olho no "Buscando" o tempo todo, sem precisar de
        // nenhum gatilho armar antes — cobre cancelamento ANTES de pegar o
        // passageiro (nesse caso a corrida nunca chega na tela de avaliação,
        // então o gatilho de avaliação abaixo nunca dispararia). Custo baixo
        // de propósito: só mais uma comparação de texto na leitura de OCR
        // que já roda de qualquer forma a cada ~600ms — não gera leitura,
        // tela ou gravação extra nenhuma.
        if (confirmedTripSubState == "buscar" && nn99BuscandoOcrRe.containsMatchIn(joinedOcrText)) {
            nn99ReachedPickup = false
            nn99ReachedPickupReason = "ocr:buscando"
            nn99KnownDestAddr = null
            nn99LastActiveSignalMs = System.currentTimeMillis()
            applyTripSubStateDebounced("online", "99")
        }

        // BUG CONFIRMADO EM CORRIDA REAL (18/07/2026, corrida da Stephanie):
        // "Finalizar corrida" apareceu em UMA leitura de OCR no meio de
        // nomes de rua/POI do mapa — falso positivo — e forçou Corrida
        // mesmo com "Chegue antes de 23:48" bem visível na tela (print real
        // confirmado). GATE OBRIGATÓRIO: nenhum reforço de OCR abaixo pode
        // forçar Corrida se "Chegue antes de HH:MM" estiver no mesmo texto
        // — essa frase SEMPRE significa Buscar, sem exceção (regra do
        // Yuri), então ela sempre vence sobre qualquer reforço.
        val temChegueAntes = nn99ChegueAntesOcrRe.containsMatchIn(joinedOcrText)

        // AJUSTE (17/07/2026, a pedido, confirmado com print real): "Cobrar
        // pagamento" só aparece perto do fim da corrida, com passageiro a
        // bordo — é um sinal forte e só existe por OCR (confirmado antes:
        // 868 ocorrências no histórico, 0 pela acessibilidade). Serve de
        // reforço/rede de segurança: se o status ainda não é Corrida por
        // qualquer motivo (inclusive o bug de sobreposição que ainda tamos
        // investigando), esse texto corrige sozinho. Mesmo padrão do
        // Buscando acima: continua "votando" a cada leitura até o debounce
        // confirmar, nunca desiste na primeira.
        if (confirmedTripSubState != "corrida" && !temChegueAntes && nn99CobrarPagamentoRe.containsMatchIn(joinedOcrText)) {
            nn99ReachedPickup = true
            nn99ReachedPickupReason = "ocr:cobrar_pagamento"
            nn99LastActiveSignalMs = System.currentTimeMillis()
            applyTripSubStateDebounced("corrida", "99")
        }

        // AJUSTE (18/07/2026, a pedido): mesmo reforço, mas pro botão
        // "Finalizar corrida" — cobre o MEIO da corrida (Cobrar pagamento
        // só aparece pertinho do fim). Tela de navegação pode estar
        // minimizada (barra inferior só) sem esse texto — nesse caso não
        // reforça nada, mas também não atrapalha (só fica de olho).
        if (confirmedTripSubState != "corrida" && !temChegueAntes && nn99FinalizarCorridaRe.containsMatchIn(joinedOcrText)) {
            nn99ReachedPickup = true
            nn99ReachedPickupReason = "ocr:finalizar_corrida"
            nn99LastActiveSignalMs = System.currentTimeMillis()
            applyTripSubStateDebounced("corrida", "99")
        }

        if (!nn99WaitingBuscandoViaOcr) {
            if (nn99AvaliacaoOcrRe.containsMatchIn(joinedOcrText)) {
                nn99ArmBuscandoListener()
            }
            return
        }
        // já esperando — desiste depois do timeout (5min é generoso o
        // bastante pra não bater em espera real nenhuma; se estourar,
        // fica no último estado confirmado até algum sinal novo chegar).
        if (System.currentTimeMillis() - nn99WaitingBuscandoSinceMs > NN99_WAITING_BUSCANDO_TIMEOUT_MS) {
            nn99WaitingBuscandoViaOcr = false
            return
        }
        if (nn99BuscandoOcrRe.containsMatchIn(joinedOcrText)) {
            nn99ReachedPickup = false
            nn99ReachedPickupReason = "ocr:buscando_armado"
            nn99KnownDestAddr = null
            nn99LastActiveSignalMs = System.currentTimeMillis()
            applyTripSubStateDebounced("online", "99")
            // Só desliga quando o debounce CONFIRMOU de verdade — continua
            // votando a cada leitura enquanto "Buscando" aparecer (bug do
            // v1.0.54 corrigido no v1.0.55: desligava no primeiro voto).
            if (confirmedTripSubState == "online") {
                nn99WaitingBuscandoViaOcr = false
            }
        }
    }
    private fun detectAndApply99TripSubState(texts: List<String>) {
        if (looksLikeOwnWidgetLeak(texts)) return
        RuleEngine.ensureLoaded(this)
        RuleEngine.refreshIfDue(this)

        // BUG CONFIRMADO EM CORRIDA REAL (13/07/2026): o motorista foi pro
        // chat com o passageiro logo que chegou, e o app trocou o endereço
        // de navegação pro destino final SEM NUNCA mostrar "Cheguei no
        // embarque" nem a tela de espera — nn99ReachedPickup nunca ligava,
        // o status ficava preso em "buscar" a viagem inteira. Sinal de
        // reforço: se a linha de endereço completo (rua+número+bairro, tem
        // vírgula, é longa) mudar de verdade, trata como "acabou de pegar
        // o passageiro" mesmo sem ver a tela de espera.
        //
        // RESTAURADO (18/07/2026, a pedido, com a causa raiz corrigida):
        // essa heurística tinha sido removida no dia 17 achando que era só
        // "mais uma" fonte de sinal — só que na real ela é o MECANISMO
        // PRINCIPAL de "buscar"→"corrida" pra corridas normais (sem
        // sobreposição). Prova, confirmada por screen_class no banco: os
        // textos "Iniciar corrida" / "Passaremos a cobrar taxa de espera"
        // só existem via OCR (screen_class=OCR_TELA_NORMAL), NUNCA chegam
        // na leitura de acessibilidade — a regra nn99_chegou_espera que
        // dependia deles praticamente nunca disparava pelo caminho
        // principal. Sem essa heurística de endereço, a única coisa que
        // ainda flipava reachedPickup era o reforço de OCR (Cobrar
        // pagamento/Finalizar corrida), só perto do fim — daí o "preso em
        // Buscar" que o Yuri viu na corrida do Leandro (7 min presa).
        //
        // Causa raiz do bug de oscilação original (corrida Yhara→tania,
        // 17/07): comparação de texto CRU, sensível a ruído de 1 caractere
        // (ex: "•" grudado na frente deslocava tudo). Corrigido agora
        // normalizando (tira espaço/pontuação solta do início) antes de
        // comparar — mantém o mecanismo, tira só a fragilidade.
        val addrLine = texts.firstOrNull { it.length >= 20 && it.contains(",") && !it.contains("R$") }
        var addressChanged = false
        if (addrLine != null) {
            val normalized = addrLine.trimStart(' ', '•', '-', '*', '·', '.', ',').trim()
            val known = nn99KnownDestAddr
            if (known == null) {
                nn99KnownDestAddr = addrLine
            } else {
                val knownNorm = known.trimStart(' ', '•', '-', '*', '·', '.', ',').trim()
                if (!normalized.take(18).equals(knownNorm.take(18), ignoreCase = true)) {
                    addressChanged = true
                    nn99ReachedPickup = true
                    nn99ReachedPickupReason = "addr_changed"
                    nn99KnownDestAddr = addrLine
                }
            }
        }

        val rpBefore = nn99ReachedPickup
        val ev = RuleEngine.evaluate("99", texts, nn99ReachedPickup, addrChanged = addressChanged, currentlyOnline = confirmedTripSubState == "online")
        val raw: String
        if (ev.matched) {
            nn99ReachedPickup = ev.newReachedPickup
            nn99ReachedPickupReason = "rule:${ev.matchedRuleKey ?: "?"}"
            if (ev.resetKnownAddr) nn99KnownDestAddr = null
            nn99LastActiveSignalMs = System.currentTimeMillis()
            raw = ev.state
        } else {
            // REMOVIDO (15/07/2026): a rede de segurança por tempo aqui.
            // CONFIRMADO EM LOG REAL: com 30s ela disparava durante a
            // espera legítima pelo passageiro (chat/"taxa de espera" não
            // batem regra nenhuma pela via limpa — >30s ali é normal, não
            // corrida encerrada). A única saída de Buscar/Corrida pra
            // Online agora é a ponte de OCR acima (checkNn99OcrStatusBridge),
            // armada pela avaliação ou pela notificação de cancelamento —
            // nunca mais "chuta" online só por falta de sinal.
            raw = confirmedTripSubState
        }
        // Diagnóstico (ver bloco de comentário acima) — snapshot completo
        // do caminho de decisão dessa leitura específica. gatilho = o que
        // decidiu por último o valor de reachedPickup (regra, endereço ou
        // reforço de OCR) — pedido do Yuri (18/07/2026) pra facilitar
        // investigação de troca de status sem precisar reconstruir tudo
        // lendo texto cru.
        nn99DebugRPBefore = rpBefore
        nn99DebugRPAfter = nn99ReachedPickup
        nn99DebugAddrChanged = addressChanged
        nn99DebugCurrentlyOnline = confirmedTripSubState == "online"
        nn99DebugMatchedRule = ev.matchedRuleKey ?: "nenhuma"
        nn99DebugRaw = raw
        applyTripSubStateDebounced(raw, "99")

        // ── Captura automática — 99: endereço mais completo visto no
        // cabeçalho de navegação — funde com o que já tinha da oferta
        // (mais completo vence, ver AutoTripCapture). Buscar = endereço
        // de origem/embarque; corrida = endereço de destino.
        if (addrLine != null) {
            when (raw) {
                "buscar"  -> AutoTripCapture.updateAddresses("99", addrLine, null)
                "corrida" -> AutoTripCapture.updateAddresses("99", null, addrLine)
            }
        }

        // ── Captura automática — 99: dinheiro NÃO confirmado ainda ───────
        // Diferente da Uber, ainda não temos um texto validado em dado real
        // pra "recebi em dinheiro" na 99 (ver princípio: nunca hardcodar
        // padrão de tela sem confirmação em trip_reader_log). Fica como
        // dinheiro=false até validar — não é pra chutar aqui.
    }

    // Debounce compartilhado por Uber e 99 — exige N leituras seguidas e
    // consistentes antes de confirmar a troca (evita "piscar" por ruído).
    private fun applyTripSubStateDebounced(raw: String, plat: String) {
        val windowSize = RuleEngine.config("trip_state_debounce_window", TRIP_STATE_DEBOUNCE_WINDOW.toDouble()).toInt().coerceAtLeast(1)
        val required = RuleEngine.config("trip_state_debounce", TRIP_STATE_DEBOUNCE.toDouble()).toInt().coerceAtLeast(1)

        tripSubStateHistory.addLast(raw)
        while (tripSubStateHistory.size > windowSize) tripSubStateHistory.removeFirst()

        val counts = tripSubStateHistory.groupingBy { it }.eachCount()
        val best = counts.maxByOrNull { it.value } ?: return
        if (best.value >= required && confirmedTripSubState != best.key) {
            val prev = confirmedTripSubState
            confirmedTripSubState = best.key
            MainActivity.floatingWidget?.updateTripState(best.key)
            // Captura automática: só reage a transição CONFIRMADA (pós-debounce),
            // nunca a leituras cruas — evita abrir/fechar registro por ruído.
            AutoTripCapture.onStateTransition(this, plat, prev, best.key)
        }
    }

    // ── Captura automática: endereço mais completo visto em tela (Uber) ──
    // Reaproveita o mesmo espírito de looksLikeAddress() do parser de oferta
    // (rejeita R$, status solto, distância/tempo solto), simplificado pra
    // texto de navegação corrida. Pega o candidato mais longo da leitura.
    private val uberDestinoDeRe = Regex("""Destino de\s+([A-ZÀ-Ý][\wÀ-ÿ'’-]*(?:\s+[A-ZÀ-Ý][\wÀ-ÿ'’-]*){0,2})""")

    // PEDIDO (17/07/2026): prefixo comum de rua e de bairro, forma completa OU
    // abreviada — "Avenida"/"Av."/"Av", "Rua"/"R.", "Jardim"/"Jd.", etc. Usado
    // pra tirar o prefixo antes de comparar endereço bloqueado, já que o
    // Uber/99 podem abreviar diferente do que ficou salvo.
    private val addrPrefixRe = Regex(
        """^(rua|r\.|avenida|av\.?|travessa|trav\.?|estrada|est\.?|alameda|al\.?|rodovia|rod\.?|""" +
        """pra[cç]a|p[cç]a\.?|largo|jardim|jd\.?|parque|pq\.?|vila|vl\.?|conjunto|cj\.?|""" +
        """loteamento|residencial|res\.?)\s+""",
        RegexOption.IGNORE_CASE
    )
    private fun extractBestAddressCandidate(texts: List<String>): String? {
        return texts.asSequence()
            .map { it.trim() }
            .filter { s ->
                s.length in 12..90 && s.contains(",") && !s.contains("R$") &&
                !Regex("""^\d""").containsMatchIn(s) &&
                !s.contains("tarifa", ignoreCase = true) &&
                !s.contains("destino de", ignoreCase = true)
            }
            .maxByOrNull { it.length }
    }

    private fun fmtBr(v: Double): String {
        val s = String.format(Locale.US, "%.2f", v)
        return s.replace(".", ",")
    }

    private fun moneyToDouble(raw: String): Double? {
        val clean = raw.replace("R$", "").trim().replace(".", "").replace(",", ".")
        return clean.toDoubleOrNull()
    }

    private fun inferState(low: String): String {
        return when {
            isOfferScreen(low) -> "OFERTA"

            low.contains("a caminho") || low.contains("buscar passageiro") ||
            low.contains("indo até") || low.contains("navegar até o ponto de embarque") ||
            low.contains("chegar ao local") || low.contains("pickup") -> "A_CAMINHO"

            low.contains("iniciar viagem") || low.contains("iniciar corrida") ||
            low.contains("aguardando passageiro") || low.contains("cheguei") ||
            low.contains("start trip") -> "AGUARDANDO_EMBARQUE"

            low.contains("finalizar viagem") || low.contains("finalizar corrida") ||
            low.contains("em viagem") || low.contains("a bordo") ||
            low.contains("end trip") || low.contains("deixar passageiro") -> "EM_VIAGEM"

            low.contains("viagem finalizada") || low.contains("corrida finalizada") ||
            low.contains("você ganhou") || low.contains("ganho da viagem") ||
            low.contains("resumo da viagem") || low.contains("trip complete") ||
            low.contains("avaliar passageiro") || low.contains("avaliar o passageiro") -> "FINALIZADA"

            low.contains("viagem cancelada") || low.contains("corrida cancelada") ||
            low.contains("cancelada pelo passageiro") || low.contains("foi cancelada") ||
            low.contains("cancelled") -> "CANCELADA"

            low.contains("você está on-line") || low.contains("está online") ||
            low.contains("procurando corridas") || low.contains("aguardando solicitações") ||
            low.contains("you're online") -> "OCIOSO"

            else -> "?"
        }
    }

    private fun extractMoney(s: String): List<String> {
        val re = Regex("""R\$\s?\d{1,4}(?:[.,]\d{2})?""")
        return re.findAll(s).map { it.value }.distinct().toList()
    }

    private fun extractKm(low: String): String? {
        val re = Regex("""(\d{1,3}(?:[.,]\d{1,2})?)\s?km""")
        return re.find(low)?.groupValues?.get(1)
    }

    private fun extractMin(low: String): String? {
        val re = Regex("""(\d{1,3})\s?min""")
        return re.find(low)?.groupValues?.get(1)
    }

    private fun collectTexts(node: AccessibilityNodeInfo?, out: ArrayList<String>) {
        node ?: return
        val t = node.text?.toString()?.trim()
        val d = node.contentDescription?.toString()?.trim()
        if (!t.isNullOrEmpty()) out.add(t)
        else if (!d.isNullOrEmpty()) out.add("(desc) $d")
        for (i in 0 until node.childCount) {
            collectTexts(node.getChild(i), out)
        }
    }

    private fun log(msg: String) {
        try {
            val f = File(getExternalFilesDir(null), LOG_FILE)
            f.appendText(msg + "\n")
        } catch (_: Exception) {}
    }

    // Salva o print da tela + os dados do card no exato momento em que o
    // card é lançado (chamado só a partir de processRealOffer, nunca a
    // partir de um frame que não virou card). Recicla o bitmap ao final.
    private fun saveSnapshot(
        plat: String, bmp: Bitmap?, overallGrade: String, metrics: List<FlashCard.Metric>,
        totalMin: Int, totalKm: Double, offer: Offer, texts: List<String>
    ) {
        if (bmp == null) return
        thread(isDaemon = true) {
            try {
                val baos = java.io.ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 55, baos)
                bmp.recycle()
                val bytes = baos.toByteArray()
                val fileName = "snap_${System.currentTimeMillis()}_${System.nanoTime() % 100000}.jpg"

                val prefs = getSharedPreferences(GpsService.PREFS_NAME, Context.MODE_PRIVATE)
                val userId = prefs.getString(GpsService.KEY_USER_ID, null)
                val deviceId = try {
                    Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
                } catch (_: Exception) { "unknown" }

                // 1) sobe a imagem pro Storage
                val storageUrl = URL("$SUPABASE_URL/storage/v1/object/flash-snapshots/$fileName")
                val conn = storageUrl.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 10000; conn.readTimeout = 10000
                conn.setRequestProperty("Content-Type", "image/jpeg")
                conn.setRequestProperty("apikey", SUPABASE_ANON)
                conn.setRequestProperty("Authorization", "Bearer $SUPABASE_ANON")
                conn.outputStream.use { it.write(bytes) }
                val code1 = conn.responseCode
                conn.disconnect()
                if (code1 !in 200..299) return@thread

                // 2) registra os dados do card junto (pra comparar depois)
                val body = JSONObject().apply {
                    put("device_id", deviceId)
                    if (userId != null) put("user_id", userId)
                    put("platform", plat)
                    put("overall_grade", overallGrade)
                    put("total_min", totalMin)
                    put("total_km", totalKm)
                    put("valor", offer.valor ?: JSONObject.NULL)
                    put("km_lido", offer.km ?: JSONObject.NULL)
                    put("min_lido", offer.min ?: JSONObject.NULL)
                    put("metrics", JSONArray(metrics.map { m ->
                        JSONObject().apply { put("label", m.label); put("value", m.value); put("grade", m.grade) }
                    }))
                    put("raw_texts", JSONArray(texts))
                    put("screenshot_path", fileName)
                }
                val url2 = URL("$SUPABASE_URL/rest/v1/flash_card_snapshots")
                val conn2 = url2.openConnection() as HttpURLConnection
                conn2.requestMethod = "POST"
                conn2.doOutput = true
                conn2.connectTimeout = 8000; conn2.readTimeout = 8000
                conn2.setRequestProperty("Content-Type", "application/json")
                conn2.setRequestProperty("apikey", SUPABASE_ANON)
                conn2.setRequestProperty("Authorization", "Bearer $SUPABASE_ANON")
                conn2.setRequestProperty("Prefer", "return=minimal")
                conn2.outputStream.use { it.write(body.toString().toByteArray()) }
                conn2.responseCode
                conn2.disconnect()
            } catch (_: Exception) {}
        }
    }

    // Notificação com endereço de origem/destino + 2 botões que abrem o
    // mapa do celular. IMPORTANCE_LOW de propósito: nunca aparece flutuando
    // por cima da tela (heads-up) nem faz som/vibra — só fica disponível
    // quando o motorista arrasta a central de notificações pra baixo, do
    // jeito que foi pedido, pra não atrapalhar enquanto dirige.
    private val NOTIF_CHANNEL_ROUTE = "mob_flash_route"
    private var lastNotifKey = ""
    private fun showRouteNotification(plat: String, offer: Offer) {
        val origem = offer.origem
        val destino = offer.destino
        if (origem == null && destino == null) return
        val key = "$plat|$origem|$destino"
        if (key == lastNotifKey) return
        lastNotifKey = key

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val existing = nm.getNotificationChannel(NOTIF_CHANNEL_ROUTE)
            if (existing == null) {
                val ch = NotificationChannel(
                    NOTIF_CHANNEL_ROUTE, "MōB Flash — endereços da corrida",
                    NotificationManager.IMPORTANCE_LOW
                )
                ch.setSound(null, null)
                ch.enableVibration(false)
                nm.createNotificationChannel(ch)
            }
        }

        // Prioriza abrir direto em modo navegação turn-by-turn (google.navigation:),
        // não só soltar um pin (geo:) — atalho já carregando o endereço pronto
        // pra seguir, sem passo extra de "iniciar rota". Cai pra geo: se o Maps
        // não resolver o scheme de navegação, e pro browser como último fallback.
        fun mapIntent(addr: String): PendingIntent {
            val navUri = Uri.parse("google.navigation:q=" + Uri.encode(addr))
            val navIntent = Intent(Intent.ACTION_VIEW, navUri).apply { setPackage("com.google.android.apps.maps") }
            val geoUri = Uri.parse("geo:0,0?q=" + Uri.encode(addr))
            val geoIntent = Intent(Intent.ACTION_VIEW, geoUri).apply { setPackage("com.google.android.apps.maps") }
            val browserIntent = Intent(Intent.ACTION_VIEW, geoUri)
            val real = when {
                navIntent.resolveActivity(packageManager) != null -> navIntent
                geoIntent.resolveActivity(packageManager) != null -> geoIntent
                else -> browserIntent
            }
            return PendingIntent.getActivity(
                this, addr.hashCode(), real,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val valorTxt = offer.valor?.let { "R$ ${fmtBr(it)}" }
        val minTxt = offer.min?.let { "$it min" }
        val kmTxt = offer.km?.let { "${fmtBr(it)} km" }
        val meta = listOfNotNull(valorTxt, minTxt, kmTxt).joinToString(" · ")
        val titulo = if (meta.isNotEmpty()) "$plat · $meta" else plat

        val resumo = listOfNotNull(
            origem?.let { "Origem: $it" },
            destino?.let { "Destino: $it" }
        ).joinToString("\n")

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIF_CHANNEL_ROUTE)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        builder.setContentTitle(titulo)
            .setStyle(Notification.BigTextStyle().bigText(resumo))
            .setContentText(resumo.lines().firstOrNull() ?: "")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(Notification.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)

        if (origem != null) {
            builder.addAction(Notification.Action.Builder(null, "Navegar Origem", mapIntent(origem)).build())
        }
        if (destino != null) {
            builder.addAction(Notification.Action.Builder(null, "Navegar Destino", mapIntent(destino)).build())
        }

        try { nm.notify(4103, builder.build()) } catch (_: Exception) {}
    }

    private fun sendToCloud(
        plat: String, pkg: String, screenClass: String,
        state: String, money: List<String>, km: String?, min: String?,
        texts: List<String>
    ) {
        thread(isDaemon = true) {
            try {
                val prefs = getSharedPreferences(GpsService.PREFS_NAME, Context.MODE_PRIVATE)
                val userId = prefs.getString(GpsService.KEY_USER_ID, null)
                val deviceId = try {
                    Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
                } catch (_: Exception) { "unknown" }

                val body = JSONObject().apply {
                    put("device_id", deviceId)
                    if (userId != null) put("user_id", userId)
                    put("platform", plat)
                    put("package", pkg)
                    put("screen_class", screenClass)
                    put("texts", JSONObject().apply {
                        put("state", state)
                        put("money", JSONArray(money))
                        put("km", km ?: JSONObject.NULL)
                        put("min", min ?: JSONObject.NULL)
                        put("raw", JSONArray(texts))
                    })
                }

                val url = URL("$SUPABASE_URL/rest/v1/trip_reader_log")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("apikey", SUPABASE_ANON)
                conn.setRequestProperty("Authorization", "Bearer $SUPABASE_ANON")
                conn.setRequestProperty("Prefer", "return=minimal")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                conn.responseCode
                conn.disconnect()
            } catch (_: Exception) {}
        }
    }

    private fun toast(msg: String) {
        main.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        main.removeCallbacks(pollRunnable)
        flashCard.shutdownTts()
        super.onDestroy()
    }
}
