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
// Objetivo: descobrir se os textos das telas de oferta/corrida da
// Uber e da 99 são legíveis via AccessibilityService no aparelho real.
// Grava tudo num log local: /Android/data/<pkg>/files/trip_reader_log.txt
// ══════════════════════════════════════════════════════════════════
class TripReaderService : AccessibilityService() {

    companion object {
        // Pacotes das plataformas — confirmar no aparelho real
        val UBER_PKGS = setOf(
            "com.ubercab.driver",      // Uber Driver
            "com.ubercab"              // fallback (app único em algumas versões)
        )
        val NN_PKGS = setOf(
            "com.taxis99.driver",      // 99 (Motorista) — nome provável
            "com.taxis99",
            "br.com.easytaxi"          // legado
        )
        const val LOG_FILE = "trip_reader_log.txt"
        // Anti-flood: só loga se a "assinatura" da tela mudou
        private var lastSig = ""
        private var lastLogMs = 0L
    }

    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val main = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                         AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            // Só escuta os apps que interessam (economiza bateria e evita ruído)
            packageNames = (UBER_PKGS + NN_PKGS).toTypedArray()
            notificationTimeout = 300
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        serviceInfo = info
        toast("MōB leitor ativo (modo teste)")
        log("═══ SERVIÇO CONECTADO ═══")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        val plat = when {
            UBER_PKGS.contains(pkg) -> "UBER"
            NN_PKGS.contains(pkg)   -> "99"
            else -> return
        }

        // Throttle: no máximo 1 dump a cada 800ms
        val now = System.currentTimeMillis()
        if (now - lastLogMs < 800) return

        val root = rootInActiveWindow ?: return
        val texts = ArrayList<String>()
        collectTexts(root, texts)
        if (texts.isEmpty()) return

        // Assinatura da tela = primeiros textos concatenados; evita logar repetido
        val sig = plat + "|" + texts.take(6).joinToString("|")
        if (sig == lastSig) return
        lastSig = sig
        lastLogMs = now

        val sb = StringBuilder()
        sb.append("\n───── [$plat] ${fmt.format(Date())} pkg=$pkg\n")
        sb.append("   window=${event.className}\n")
        texts.forEachIndexed { i, t -> sb.append("   [$i] $t\n") }
        log(sb.toString())
    }

    // Percorre a árvore de nós e coleta todo texto visível + contentDescription
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
