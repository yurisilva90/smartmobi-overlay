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
//  1. Inferir o ESTADO da jornada a partir dos textos da tela.
//  2. Quando o estado é OFERTA, extrair valor/km/min do PRÓPRIO app
//     (99 ou Uber — nunca do overlay do Gigu) e mostrar o MōB Flash com
//     as métricas que o usuário escolheu na tela de configuração,
//     cada uma com sua própria faixa vermelho/amarelo/verde.
//  3. Continuar capturando o Gigu lado a lado (fins de estudo / log),
//     sem nunca usar os números dele pra decidir a nota do MōB Flash.
//
// Log local: /Android/data/<pkg>/files/trip_reader_log.txt
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

        // Config do MōB Flash — um único JSON no SharedPreferences, escrito
        // pelo JS via bridge (saveFlashConfig). Formato:
        // {"enabled":true,"custoPorKm":1.84,"kpis":{
        //   "rkm":{"enabled":true,"red":1.2,"green":2.0}, "rhora":{...},
        //   "rmin":{...}, "nota":{...}, "margem":{...}, "lucro":{...}}}
        const val KEY_FLASH_CONFIG_JSON = "flash_config_json"

        // Ordem/labels fixos de exibição no card
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
            notificationTimeout = 300
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        serviceInfo = info
        log("\n═══════════════ SERVIÇO CONECTADO ${fmt.format(Date())} ═══════════════")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        if (!CAPTURE_PKGS.contains(pkg)) return

        val now = System.currentTimeMillis()

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

        // ── Log combinado — só pra estudo do Gigu / depuração ──
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
        sb.append("\n───── [$plat] ${fmt.format(Date())} estado=$state")
        if (stateChanged && state != "?") sb.append("  ⟵ MUDOU")
        sb.append("\n")
        if (money.isNotEmpty()) sb.append("   R\$: ${money.joinToString(", ")}\n")
        if (km != null)  sb.append("   km: $km\n")
        if (min != null) sb.append("   min: $min\n")
        allTexts.forEachIndexed { i, t -> sb.append("   [$i] $t\n") }
        log(sb.toString())

        val winTag = "${event.className ?: ""} :: seen=${textsByPkg.keys.sorted().joinToString("+")}"
        sendToCloud(plat, pkg, winTag, state, money, km, min, allTexts)
    }

    // ── MōB Flash real: só usa o texto do próprio app (99/Uber) ──
    private fun processRealOffer(plat: String, texts: List<String>) {
        val cfg = loadFlashConfig()
        if (!cfg.optBoolean("enabled", true)) { hideFlashIfActive(); return }

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
        val nota = extractNota(texts)

        // Sem valor OU sem km não dá pra montar nenhuma métrica confiável —
        // não mostra card pela metade.
        if (valor == null || valor <= 0.0 || km == null || km <= 0.0) return

        val sig = "$plat|$valor|$km|$min|$nota"
        if (sig == lastFlashSig) return
        lastFlashSig = sig

        val custoPorKm = cfg.optDouble("custoPorKm", 0.0)
        val kpisCfg = cfg.optJSONObject("kpis") ?: JSONObject()

        val rkm = valor / km
        val rhora = if (min != null && min > 0) valor / (min / 60.0) else null
        val rmin = if (min != null && min > 0) valor / min else null
        val custoConfigurado = custoPorKm > 0.0
        val lucro = if (custoConfigurado) valor - custoPorKm * km else null
        val margemPct = if (custoConfigurado && valor > 0) (lucro!! / valor) * 100.0 else null

        val values = mapOf(
            "rkm" to rkm,
            "rhora" to rhora,
            "rmin" to rmin,
            "nota" to nota,
            "margem" to margemPct,
            "lucro" to lucro
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
                v < red   -> "r"
                v >= green -> "g"
                else       -> "a"
            }
            grades.add(grade)
            val fmtVal = when (key) {
                "margem" -> "${v.toInt()}%"
                "lucro"  -> "R$${v.toInt()}"
                "rhora"  -> v.toInt().toString()
                "nota"   -> fmtBr(v)
                else     -> fmtBr(v)
            }
            metrics.add(FlashCard.Metric(label, fmtVal, grade))
        }

        if (metrics.isEmpty()) return // nenhum KPI configurado tinha dado suficiente

        val overallGrade = when {
            grades.contains("r") -> "r"
            grades.contains("a") -> "a"
            else -> "g"
        }

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

    private fun kmToDouble(raw: String): Double? = raw.replace(",", ".").toDoubleOrNull()

    // Nota do passageiro — EXPERIMENTAL. Procura um número tipo "4,92"/"4.92"
    // (1 a 5, 2 casas) que não faça parte de um valor em R$, km ou min.
    private fun extractNota(texts: List<String>): Double? {
        val re = Regex("""^[1-5][.,]\d{2}$""")
        for (t in texts) {
            val tt = t.trim()
            if (re.matches(tt)) {
                return tt.replace(",", ".").toDoubleOrNull()
            }
        }
        return null
    }

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
