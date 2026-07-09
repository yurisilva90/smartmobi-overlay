package io.github.yurisilva90.smartmobi

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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
// Três objetivos:
//  1. Inferir o ESTADO da jornada (oferta / aceite / a caminho / a bordo /
//     finalizada / cancelada) a partir dos textos da tela.
//  2. Quando o estado é OFERTA, extrair valor/km/min do PRÓPRIO app
//     (99 ou Uber — nunca do overlay do Gigu) e mostrar o MōB Flash com
//     a nota real da corrida (R$/km, R$/h, margem sobre o custo do
//     veículo configurado em Financeiro).
//  3. Continuar capturando o Gigu lado a lado (fins de estudo / log),
//     sem nunca usar os números dele para decidir a nota do MōB Flash.
//
// Nada é enviado pra lugar nenhum além do Supabase (log) e nenhum gesto
// é executado. Log local: /Android/data/<pkg>/files/trip_reader_log.txt
// ══════════════════════════════════════════════════════════════════
class TripReaderService : AccessibilityService() {

    companion object {
        val UBER_PKGS = setOf(
            "com.ubercab.driver",      // Uber Driver
            "com.ubercab"              // fallback (app único em algumas versões)
        )
        val NN_PKGS = setOf(
            "com.app99.driver",        // 99 Motorista e Entregador (confirmado na Play Store)
            "com.taxis99.driver"       // fallback improvável, inofensivo
        )
        // Também capturamos o Gigu — só pra log/estudo, nunca pra gerar a nota real.
        val GIGU_PKGS = setOf("co.gigu.app")
        val CAPTURE_PKGS = UBER_PKGS + NN_PKGS + GIGU_PKGS
        const val LOG_FILE = "trip_reader_log.txt"
        const val SUPABASE_URL  = "https://jlsrpptslwfhmkvelaro.supabase.co"
        const val SUPABASE_ANON = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Impsc3JwcHRzbHdmaG1rdmVsYXJvIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzM4NjYxNzIsImV4cCI6MjA4OTQ0MjE3Mn0.4gD4dKx05QaOAAkY1gAx2HuH_CN31Xg3kkDMdvZ4kh0"

        // ── Config do MōB Flash — lida do SharedPreferences (escrito pelo JS via bridge) ──
        const val KEY_FLASH_ENABLED    = "flash_enabled"     // Boolean, default true
        const val KEY_FLASH_CUSTO_KM   = "flash_custo_km"    // Float, R$/km de custo do veículo. 0 = não configurado
        const val KEY_FLASH_META_BOA   = "flash_meta_boa"    // Float, % margem pra nota verde. default 40
        const val KEY_FLASH_META_ACEIT = "flash_meta_aceit"  // Float, % margem pra nota amarela. default 15

        private var lastSig = ""
        private var lastLogMs = 0L
        private var lastState = "?"
    }

    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val main = Handler(Looper.getMainLooper())
    private val flashCard by lazy { FlashCard(this) }
    private var lastFlashSig = ""
    private var lastRealState = "?"

    override fun onServiceConnected() {
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                         AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            // Sem filtro de packageNames — precisamos "ver" o overlay do Gigu,
            // que desenha por cima da 99. A GRAVAÇÃO continua restrita a
            // CAPTURE_PKGS (99/Uber/Gigu); os demais apps são descartados.
            notificationTimeout = 300
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        serviceInfo = info
        log("\n═══════════════ SERVIÇO CONECTADO ${fmt.format(Date())} ═══════════════")
        log("   Uber pkgs: $UBER_PKGS")
        log("   99   pkgs: $NN_PKGS")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        if (!CAPTURE_PKGS.contains(pkg)) return

        val now = System.currentTimeMillis()

        // ── Varre TODAS as janelas visíveis, mas guarda o texto SEPARADO por
        // pacote. Isso é o que permite: (a) capturar o overlay do Gigu mesmo
        // quando ele aparece por cima da tela da 99, sem (b) misturar o texto
        // dele com o da 99/Uber na hora de calcular a nota real. ──
        val textsByPkg = LinkedHashMap<String, ArrayList<String>>()
        try {
            for (w in windows) {
                val r = w.root ?: continue
                val wp = r.packageName?.toString() ?: continue
                if (CAPTURE_PKGS.contains(wp)) {
                    val lst = textsByPkg.getOrPut(wp) { ArrayList() }
                    collectTexts(r, lst)
                }
            }
        } catch (_: Exception) {}

        if (textsByPkg.isEmpty()) {
            val root = rootInActiveWindow ?: return
            val rp = root.packageName?.toString() ?: return
            if (rp != pkg) return
            val lst = ArrayList<String>()
            collectTexts(root, lst)
            textsByPkg[rp] = lst
        }
        if (textsByPkg.isEmpty()) return

        // ── Texto do app real (99/Uber) — nunca inclui o Gigu. É esta a
        // fonte usada pro MōB Flash. ──
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
            // Saiu do app real (só sobrou Gigu ou nada) — some com o card.
            hideFlashIfActive()
        }

        // ── Log combinado (todos os pacotes visíveis) — só pra estudo do
        // Gigu / depuração. Não influencia a nota do MōB Flash. ──
        if (now - lastLogMs < 700) return
        val plat = when {
            UBER_PKGS.contains(pkg) -> "UBER"
            NN_PKGS.contains(pkg)   -> "99"
            GIGU_PKGS.contains(pkg) -> "GIGU"
            else -> return
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
        val stateChanged = state != lastState
        if (state != "?") lastState = state

        val sb = StringBuilder()
        sb.append("\n───── [$plat] ${fmt.format(Date())} ")
        sb.append("estado=$state")
        if (stateChanged && state != "?") sb.append("  ⟵ MUDOU")
        sb.append("\n")
        if (money.isNotEmpty()) sb.append("   R\$: ${money.joinToString(", ")}\n")
        if (km != null)  sb.append("   km: $km\n")
        if (min != null) sb.append("   min: $min\n")
        sb.append("   window=${event.className}\n")
        allTexts.forEachIndexed { i, t -> sb.append("   [$i] $t\n") }
        log(sb.toString())

        val winTag = "${event.className ?: ""} :: seen=${textsByPkg.keys.sorted().joinToString("+")}"
        sendToCloud(plat, pkg, winTag, state, money, km, min, allTexts)
    }

    // ── MōB Flash real: só usa o texto do próprio app (99/Uber) ──
    private fun processRealOffer(plat: String, texts: List<String>) {
        val prefs = getSharedPreferences(GpsService.PREFS_NAME, Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean(KEY_FLASH_ENABLED, true)
        if (!enabled) { hideFlashIfActive(); return }

        val joined = texts.joinToString("  ")
        val low = joined.lowercase(Locale.getDefault())
        val state = inferState(low)

        if (state != "OFERTA") {
            if (lastRealState == "OFERTA") hideFlashIfActive()
            lastRealState = state
            return
        }
        lastRealState = state

        val moneyRaw = extractMoney(joined)
        val kmRaw = extractKm(low)
        val minRaw = extractMin(low)
        val valor = moneyRaw.firstOrNull()?.let { moneyToDouble(it) }
        val km = kmRaw?.let { kmToDouble(it) }
        val min = minRaw?.toIntOrNull()

        // Sem valor OU sem km não dá pra montar uma nota confiável —
        // não mostra card pela metade (era essa a origem do card "vazio").
        if (valor == null || valor <= 0.0 || km == null || km <= 0.0) return

        val sig = "$plat|$valor|$km|$min"
        if (sig == lastFlashSig) return
        lastFlashSig = sig

        val custoPorKm = prefs.getFloat(KEY_FLASH_CUSTO_KM, 0f).toDouble()
        val metaBoa = prefs.getFloat(KEY_FLASH_META_BOA, 40f).toDouble()
        val metaAceit = prefs.getFloat(KEY_FLASH_META_ACEIT, 15f).toDouble()

        val rkm = valor / km
        val rhora = if (min != null && min > 0) valor / (min / 60.0) else null
        val rmin = if (min != null && min > 0) valor / min else null

        val custoConfigurado = custoPorKm > 0.0
        val grade: String
        val margemPct: Double?
        if (custoConfigurado) {
            val lucro = valor - custoPorKm * km
            margemPct = (lucro / valor) * 100.0
            grade = when {
                margemPct >= metaBoa   -> "g"
                margemPct >= metaAceit -> "a"
                else                   -> "r"
            }
        } else {
            // Sem custo configurado no Financeiro — usa faixa fixa de R$/km
            // como aproximação (assumido; ajustável quando o custo for
            // cadastrado no assistente de custo do veículo).
            margemPct = null
            grade = when {
                rkm >= 2.0 -> "g"
                rkm >= 1.2 -> "a"
                else       -> "r"
            }
        }

        val metrics = ArrayList<FlashCard.Metric>()
        metrics.add(FlashCard.Metric("R$/KM", fmtBr(rkm), grade))
        if (rhora != null) metrics.add(FlashCard.Metric("R$/HORA", fmtBr(rhora), grade))
        if (margemPct != null) metrics.add(FlashCard.Metric("MARGEM", "${margemPct.toInt()}%", grade))
        else if (rmin != null) metrics.add(FlashCard.Metric("R$/MIN", fmtBr(rmin), grade))

        main.post {
            flashCard.show(
                platform = plat,
                overallGrade = grade,
                metrics = metrics,
                totalMin = min ?: 0,
                totalKm = km,
                autoHideMs = 15000L
            )
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

    private fun kmToDouble(raw: String): Double? = raw.replace(",", ".").toDoubleOrNull()

    // ── Máquina de estados (heurística por palavras-chave) ──
    private fun inferState(low: String): String {
        return when {
            (low.contains("aceitar") || low.contains("accept")) &&
            (low.contains("recusar") || low.contains("ignorar") || low.contains("decline") ||
             low.contains("dispensar")) -> "OFERTA"

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
}
