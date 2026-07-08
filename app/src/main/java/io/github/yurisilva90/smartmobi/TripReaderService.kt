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
// PROVA DE CONCEITO — SÓ LEITURA (não toca em nada, não age)
//
// Dois objetivos:
//  1. Confirmar se os textos das telas de oferta/corrida da Uber e da 99
//     são legíveis via AccessibilityService no aparelho real.
//  2. Inferir o ESTADO da jornada (oferta / aceite / recusa / a caminho /
//     a bordo / finalizada / cancelada) a partir desses textos, e logar
//     o estado detectado junto com os valores extraídos (R$, km, min).
//
// Nada é enviado pra lugar nenhum e nenhum gesto é executado. Tudo vai
// pra um log local: /Android/data/<pkg>/files/trip_reader_log.txt
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
        const val LOG_FILE = "trip_reader_log.txt"
        const val SUPABASE_URL  = "https://jlsrpptslwfhmkvelaro.supabase.co"
        const val SUPABASE_ANON = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Impsc3JwcHRzbHdmaG1rdmVsYXJvIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzM4NjYxNzIsImV4cCI6MjA4OTQ0MjE3Mn0.4gD4dKx05QaOAAkY1gAx2HuH_CN31Xg3kkDMdvZ4kh0"
        private var lastSig = ""
        private var lastLogMs = 0L
        private var lastState = "?"
    }

    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val main = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                         AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            packageNames = (UBER_PKGS + NN_PKGS).toTypedArray()
            notificationTimeout = 300
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        serviceInfo = info
        toast("MōB leitor ativo (modo teste)")
        log("\n═══════════════ SERVIÇO CONECTADO ${fmt.format(Date())} ═══════════════")
        log("   Uber pkgs: $UBER_PKGS")
        log("   99   pkgs: $NN_PKGS")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        val plat = when {
            UBER_PKGS.contains(pkg) -> "UBER"
            NN_PKGS.contains(pkg)   -> "99"
            else -> return
        }

        val now = System.currentTimeMillis()
        if (now - lastLogMs < 700) return

        // Tela INTEIRA (não só o widget que mudou) — event.source em eventos
        // de "conteúdo mudou" costuma trazer só o nó específico que mudou
        // (ex.: um contador piscando), o que esvazia a captura. Usamos
        // rootInActiveWindow pra pegar a árvore completa, mas validamos que
        // o pacote da janela ativa bate com o do evento — é isso que evita
        // ler o app errado numa troca rápida (o bug que a 1.0.9 tentou corrigir).
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != pkg) return   // trocou de tela no meio do caminho — descarta

        val texts = ArrayList<String>()
        collectTexts(root, texts)
        if (texts.isEmpty()) return

        val sig = plat + "|" + texts.take(8).joinToString("|")
        if (sig == lastSig) return
        lastSig = sig
        lastLogMs = now

        // ── Inferência de estado + extração de valores ──
        val joined = texts.joinToString("  ")
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
        texts.forEachIndexed { i, t -> sb.append("   [$i] $t\n") }
        log(sb.toString())

        // Espelha no Supabase pra validação remota (não bloqueia a thread principal)
        sendToCloud(plat, pkg, event.className?.toString() ?: "",
                    state, money, km, min, texts)
    }

    // ── Máquina de estados (heurística por palavras-chave) ──
    // No aparelho real vamos ajustar essas chaves com base no que o log mostrar.
    private fun inferState(low: String): String {
        return when {
            // Oferta chegando: mostra ganho + distância + botões aceitar/recusar
            (low.contains("aceitar") || low.contains("accept")) &&
            (low.contains("recusar") || low.contains("ignorar") || low.contains("decline") ||
             low.contains("dispensar")) -> "OFERTA"

            // Indo buscar o passageiro
            low.contains("a caminho") || low.contains("buscar passageiro") ||
            low.contains("indo até") || low.contains("navegar até o ponto de embarque") ||
            low.contains("chegar ao local") || low.contains("pickup") -> "A_CAMINHO"

            // Aguardando embarque / iniciar viagem
            low.contains("iniciar viagem") || low.contains("iniciar corrida") ||
            low.contains("aguardando passageiro") || low.contains("cheguei") ||
            low.contains("start trip") -> "AGUARDANDO_EMBARQUE"

            // Viagem em curso
            low.contains("finalizar viagem") || low.contains("finalizar corrida") ||
            low.contains("em viagem") || low.contains("a bordo") ||
            low.contains("end trip") || low.contains("deixar passageiro") -> "EM_VIAGEM"

            // Viagem concluída / resumo de ganho
            low.contains("viagem finalizada") || low.contains("corrida finalizada") ||
            low.contains("você ganhou") || low.contains("ganho da viagem") ||
            low.contains("resumo da viagem") || low.contains("trip complete") ||
            low.contains("avaliar passageiro") || low.contains("avaliar o passageiro") -> "FINALIZADA"

            // Cancelamento
            low.contains("viagem cancelada") || low.contains("corrida cancelada") ||
            low.contains("cancelada pelo passageiro") || low.contains("foi cancelada") ||
            low.contains("cancelled") -> "CANCELADA"

            // Ocioso / mapa esperando
            low.contains("você está on-line") || low.contains("está online") ||
            low.contains("procurando corridas") || low.contains("aguardando solicitações") ||
            low.contains("you're online") -> "OCIOSO"

            else -> "?"
        }
    }

    // Extrai valores em R$ (ex.: "R$ 12,50", "R$12.5", "12,50")
    private fun extractMoney(s: String): List<String> {
        val re = Regex("""R\$\s?\d{1,4}(?:[.,]\d{2})?""")
        return re.findAll(s).map { it.value }.distinct().toList()
    }

    // Extrai distância (ex.: "3,4 km", "12 km")
    private fun extractKm(low: String): String? {
        val re = Regex("""(\d{1,3}(?:[.,]\d{1,2})?)\s?km""")
        return re.find(low)?.groupValues?.get(1)
    }

    // Extrai duração (ex.: "8 min", "12min")
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

    // Envia cada captura pra tabela trip_reader_log no Supabase.
    // A tabela só aceita INSERT anônimo; leitura é bloqueada (só eu leio pelo painel).
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
                conn.responseCode   // dispara o envio
                conn.disconnect()
            } catch (_: Exception) {
                // silencioso — o log local é o backup; falha de rede não pode travar nada
            }
        }
    }

    private fun toast(msg: String) {
        main.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    override fun onInterrupt() {}
}
