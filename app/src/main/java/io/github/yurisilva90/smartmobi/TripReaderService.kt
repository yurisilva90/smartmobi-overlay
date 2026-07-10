package io.github.yurisilva90.smartmobi

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.content.Context
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
            "nota"   to "NOTA",
            "margem" to "% LUCRO",
            "lucro"  to "R$ LUCRO"
        )

        private var lastSig = ""
        private var lastLogMs = 0L
        private var lastScanMs = 0L
        private var lastState = "?"
        private var lastWinSig = ""
    }

    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val main = Handler(Looper.getMainLooper())
    private val flashCard by lazy { FlashCard(this) }
    private var lastFlashSig = ""
    private var lastRealState = "?"

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
        var fgPlat: String? = null
        try {
            for (w in windows) {
                val wp = w.root?.packageName?.toString() ?: continue
                if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION || !w.isActive) continue
                if (NN_PKGS.contains(wp)) { fgPlat = "99"; break }
                if (UBER_PKGS.contains(wp)) { fgPlat = "UBER"; break }
            }
        } catch (_: Exception) {}
        if (fgPlat != null) requestOcrPass(fgPlat)
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
        val winMeta = ArrayList<String>()
        var nnWindowSeen = false
        var nnIsForeground = false
        var nnNodeCount = 0
        try {
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

        val realPkg = textsByPkg.keys.firstOrNull { UBER_PKGS.contains(it) || NN_PKGS.contains(it) }
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

        // ── Log combinado (throttle + dedup) ──
        if (now - lastLogMs < 700) return
        val plat = when {
            UBER_PKGS.contains(evPkg) -> "UBER"
            NN_PKGS.contains(evPkg)   -> "99"
            GIGU_PKGS.contains(evPkg) -> "GIGU"
            realPlat != null -> realPlat
            else -> "OUTRO"
        }
        val allTexts = ArrayList<String>()
        textsByPkg.values.forEach { allTexts.addAll(it) }
        if (allTexts.isEmpty()) return
        val sig = plat + "|" + allTexts.take(8).joinToString("|")
        if (sig == lastSig) return
        lastSig = sig
        lastLogMs = now

        val joined = allTexts.joinToString("  ")
        val low = joined.lowercase(Locale.getDefault())
        val state = inferState(low)
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
        svc.captureAndRecognize({ lines ->
            if (lines.isEmpty()) {
                if (System.currentTimeMillis() - lastOcrLogMs > 1500) {
                    lastOcrLogMs = System.currentTimeMillis()
                    sendToCloud(plat, "ocr", "OCR_VAZIO", "OCR_SEM_LINHAS", emptyList(), null, null, emptyList())
                }
                return@captureAndRecognize
            }
            val joined = lines.joinToString("  ")
            val low = joined.lowercase(Locale.getDefault())
            val isOffer = isOfferScreen(low)
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
            processRealOffer(plat, lines)
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
    private data class Offer(
        val valor: Double?, val km: Double?, val min: Int?,
        val rkmDirect: Double?, val nota: Double?
    )

    private fun parseOffer(texts: List<String>): Offer {
        val joined = texts.joinToString("  ")
        val low = joined.lowercase(Locale.getDefault())

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
        fun addLeg(minsStr: String, distStr: String, unit: String) {
            val mins = minsStr.toIntOrNull() ?: return
            val dist = distStr.replace(",", ".").toDoubleOrNull() ?: return
            val km2 = if (unit == "m") dist / 1000.0 else dist
            if (mins in 1..90 && km2 in 0.05..80.0) { totMin += mins; totKm += km2; legs++ }
        }
        Regex("""(\d{1,3})\s*min(?:utos)?\s*\((\d+(?:[.,]\d+)?)\s*(m|km)\)""").findAll(low)
            .forEach { addLeg(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }
        Regex("""\(\s*(\d{1,3})\s*min(?:utos)?\s+(\d+(?:[.,]\d+)?)\s*(m|km)\s*\)""").findAll(low)
            .forEach { addLeg(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }

        var km: Double? = if (legs > 0 && totKm > 0) totKm else null
        val min: Int? = if (legs > 0 && totMin > 0) totMin else extractMin(low)?.toIntOrNull()

        // fallback de valor: se não achou "aceitar por", pega o R$ que for
        // consistente com rkm direto × km (evita pegar ganhos do dia) — é
        // esse caminho que resolve o tipo B (99 "Escolher") e a Uber
        // ("Selecionar"), que não têm o texto "aceitar por"
        if (valor == null) {
            val monies = extractMoney(joined).mapNotNull { moneyToDouble(it) }
            valor = if (rkmDirect != null && km != null) {
                monies.firstOrNull { it > 0 && kotlin.math.abs(it / km!! - rkmDirect) / rkmDirect < 0.35 }
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
        var nota: Double? = null
        for (line in texts) {
            val l = line.lowercase(Locale.getDefault())
            if (l.contains("corrid") || l.contains("corda") || Regex("""[1-5][.,]\d{2}\s*\(\d+\)""").containsMatchIn(l)) {
                Regex("""([1-5][.,]\d{2})""").find(line)?.let {
                    nota = it.groupValues[1].replace(",", ".").toDoubleOrNull()
                }
                if (nota != null) break
            }
        }

        return Offer(valor, km, min, rkmDirect, nota)
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
    private fun processRealOffer(plat: String, texts: List<String>) {
        val cfg = loadFlashConfig()
        if (!cfg.optBoolean("enabled", true)) { hideFlashIfActive(); return }

        val joined = texts.joinToString("  ")
        val low = joined.lowercase(Locale.getDefault())

        if (!isOfferScreen(low)) {
            // Esconde na hora — não espera reconhecer qual é o próximo estado.
            // É isso que garante o card sumir rápido quando a oferta sai da tela.
            if (lastFlashSig.isNotEmpty()) hideFlashIfActive()
            val state = inferState(low)
            if (state != "?") lastRealState = state
            return
        }
        lastRealState = "OFERTA"

        val offer = parseOffer(texts)
        val valor = offer.valor
        val km = offer.km
        val min = offer.min

        if (valor == null || valor <= 0.0 || valor > 2000.0 || km == null || km <= 0.0 || km > 150.0) return

        val sig = "$plat|$valor|$km|$min|${offer.nota}"
        if (sig == lastFlashSig) return
        lastFlashSig = sig

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
        val grades = ArrayList<String>()
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
            grades.add(grade)
            val fmtVal = when (key) {
                "margem" -> "${v.toInt()}%"
                "lucro"  -> "R$${fmtBr(v)}"
                else     -> fmtBr(v)
            }
            metrics.add(FlashCard.Metric(label, fmtVal, grade))
        }
        if (metrics.isEmpty()) return

        val overallGrade = when {
            grades.contains("r") -> "r"
            grades.contains("a") -> "a"
            else -> "g"
        }

        // Loga a oferta detectada (pra auditoria da precisão do parser)
        sendToCloud(plat, "offer-parser", "OFERTA_DETECTADA v=$valor km=$km min=$min rkm=$rkm nota=${offer.nota}",
            "OFERTA", extractMoney(joined), km.toString(), min?.toString(), texts)

        main.post {
            flashCard.show(
                platform = plat,
                overallGrade = overallGrade,
                metrics = metrics,
                totalMin = min ?: 0,
                totalKm = km,
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
            put("kpis", JSONObject().apply {
                put("rkm", JSONObject().apply { put("enabled", true); put("red", 1.2); put("green", 2.0) })
                put("rhora", JSONObject().apply { put("enabled", true); put("red", 25.0); put("green", 45.0) })
                put("rmin", JSONObject().apply { put("enabled", false); put("red", 0.6); put("green", 1.0) })
                put("nota", JSONObject().apply { put("enabled", false); put("red", 4.5); put("green", 4.8) })
                put("margem", JSONObject().apply { put("enabled", true); put("red", 15.0); put("green", 40.0) })
                put("lucro", JSONObject().apply { put("enabled", true); put("red", 5.0); put("green", 15.0) })
            })
        }
    }

    private fun hideFlashIfActive() {
        if (lastFlashSig.isNotEmpty()) {
            lastFlashSig = ""
            main.post { flashCard.hide() }
        }
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
