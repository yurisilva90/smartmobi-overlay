package io.github.yurisilva90.smartmobi

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

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

        val root = rootInActiveWindow ?: return
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

    private fun toast(msg: String) {
        main.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    override fun onInterrupt() {}
}
