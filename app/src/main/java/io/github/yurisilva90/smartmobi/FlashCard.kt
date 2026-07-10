package io.github.yurisilva90.smartmobi

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

// ══════════════════════════════════════════════════════════════════
// MōB Flash — card flutuante de decisão rápida sobre a oferta.
// Aparece por cima do app da 99/Uber. SÓ EXIBE — nunca toca em nada
// (exceto o próprio card, que fecha ao ser tocado).
//
// Até 4 métricas, sempre numa linha só. Largura do card se ajusta à
// quantidade de métricas ativas (2 selecionadas = card mais estreito).
// ══════════════════════════════════════════════════════════════════
class FlashCard(private val context: Context) {

    data class Metric(val label: String, val value: String, val grade: String) // grade: "g"/"a"/"r"

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var container: FrameLayout? = null
    private val hideRunnable = Runnable { hide() }

    // ── Voz por cor: "Aceitar/Analisar/Recusar corrida" ──
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingPhrase: String? = null
    private fun ensureTts() {
        if (tts != null) return
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("pt", "BR")
                ttsReady = true
                // A 1ª oferta da sessão às vezes chegava antes do motor de
                // TTS terminar de iniciar, e a fala era simplesmente
                // descartada. Agora guarda e fala assim que fica pronto.
                pendingPhrase?.let { p ->
                    try { tts?.speak(p, TextToSpeech.QUEUE_FLUSH, null, "mob_flash_grade") } catch (_: Exception) {}
                    pendingPhrase = null
                }
            }
        }
    }
    private fun speakGrade(grade: String) {
        val phrase = when (grade) {
            "g" -> "Aceitar corrida"
            "a" -> "Analisar corrida"
            else -> "Recusar corrida"
        }
        ensureTts()
        if (!ttsReady) { pendingPhrase = phrase; return }
        try { tts?.speak(phrase, TextToSpeech.QUEUE_FLUSH, null, "mob_flash_grade") } catch (_: Exception) {}
    }
    fun shutdownTts() {
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
        tts = null; ttsReady = false; pendingPhrase = null
    }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()
    private fun dpf(v: Int) = (v * context.resources.displayMetrics.density)

    private fun colorOf(grade: String) = when (grade) {
        "g" -> Color.parseColor("#10B981")
        "a" -> Color.parseColor("#F59E0B")
        else -> Color.parseColor("#EF4444")
    }

    // Base fixa (bordas + padding) + um "slot" de largura por métrica ativa.
    // É assim que o card fica mais estreito com 2 KPIs e mais largo com 4.
    private val baseWidthPx = dp(46)
    private val tileWidthPx = dp(74)
    private fun widthFor(n: Int) = baseWidthPx + tileWidthPx * n.coerceIn(1, 4)

    private val params = WindowManager.LayoutParams(
        widthFor(4),
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        // SEM FLAG_NOT_TOUCHABLE: o card responde a toque (fecha ao tocar,
        // igual o Gigu). FLAG_NOT_FOCUSABLE continua — não pode roubar foco
        // de teclado do app por baixo.
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = dp(58) }

    // platform: "99" ou "UBER". overallGrade: pior nota entre as métricas ativas.
    // metrics: até 4, sempre numa linha só. autoHideMs é só rede de segurança —
    // o normal é o TripReaderService chamar hide() sozinho quando a oferta some (~1s).
    fun show(platform: String, overallGrade: String, metrics: List<Metric>, totalMin: Int, totalKm: Double, autoHideMs: Long = 20000L) {
        if (metrics.isEmpty()) return
        handler.removeCallbacks(hideRunnable)
        handler.post {
            val wasHidden = container == null
            container?.let { try { wm.removeView(it) } catch (_: Exception) {} }
            val cardWidthPx = widthFor(metrics.size)
            params.width = cardWidthPx
            container = buildCard(platform, overallGrade, metrics, totalMin, totalKm, cardWidthPx)
            try { wm.addView(container, params) } catch (e: Exception) { e.printStackTrace() }
            handler.postDelayed(hideRunnable, autoHideMs)
            // Só fala quando o card estava escondido — evita repetir a fala
            // toda vez que o OCR refina km/min da mesma oferta já mostrada.
            if (wasHidden) speakGrade(overallGrade)
        }
    }

    fun hide() {
        handler.removeCallbacks(hideRunnable)
        handler.post {
            container?.let { try { wm.removeView(it) } catch (_: Exception) {} }
            container = null
        }
    }

    // "0h04m" se >=60min, senão "4m"
    private fun fmtMin(min: Int): String {
        if (min >= 60) {
            val h = min / 60; val m = min % 60
            return "${h}h${m.toString().padStart(2, '0')}m"
        }
        return "${min}m"
    }

    private fun buildCard(platform: String, overallGrade: String, metrics: List<Metric>, totalMin: Int, totalKm: Double, cardWidthPx: Int): FrameLayout {
        val gradeColor = colorOf(overallGrade)

        val root = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(cardWidthPx, FrameLayout.LayoutParams.WRAP_CONTENT)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpf(16)
                setColor(Color.parseColor("#E60E0E17"))
            }
            elevation = dpf(10)
            isClickable = true
            // Toque em qualquer parte do card fecha ele — igual o Gigu.
            setOnClickListener { hide() }
        }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            // Largura EXPLÍCITA (não MATCH_PARENT) — root já tem largura fixa
            // definida acima, então isso é só espelhar o mesmo valor. Uma
            // janela WRAP_CONTENT com filho MATCH_PARENT colapsa o conteúdo.
            layoutParams = FrameLayout.LayoutParams(cardWidthPx, FrameLayout.LayoutParams.WRAP_CONTENT)
        }

        // Borda lateral mais grossa (8dp) pra ficar bem visível de relance.
        row.addView(sideBar(dp(8), FrameLayout.LayoutParams.MATCH_PARENT, gradeColor, roundLeft = true))

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(dp(11), dp(10), dp(11), dp(9))
        }

        // Métricas numa linha só — números grandes (tamanho Gigu).
        val metricsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        metrics.forEach { m ->
            val tile = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val n = TextView(context).apply {
                text = m.value; textSize = 31f
                setTextColor(colorOf(m.grade)); setTypeface(Typeface.DEFAULT_BOLD)
                gravity = Gravity.CENTER
                maxLines = 1
            }
            val l = TextView(context).apply {
                text = m.label; textSize = 6.8f
                setTextColor(Color.parseColor("#8A8A99")); setTypeface(Typeface.DEFAULT_BOLD)
                gravity = Gravity.CENTER; setPadding(0, dp(3), 0, 0)
                maxLines = 1
            }
            tile.addView(n); tile.addView(l)
            metricsRow.addView(tile)
        }
        content.addView(metricsRow)

        val div = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(9); bottomMargin = dp(7)
            }
            setBackgroundColor(Color.parseColor("#1FFFFFFF"))
        }
        content.addView(div)

        // Linha de baixo: logo + tempo + km juntos. Texto branco, 50% maior
        // que o padrão anterior, sem a palavra "Total".
        val bottomRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val isUber = platform == "UBER"
        val logoBadge = TextView(context).apply {
            text = if (isUber) "UBER" else "99"
            textSize = if (isUber) 9.5f else 10f
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(if (isUber) Color.WHITE else Color.parseColor("#111111"))
            setPadding(dp(7), dp(3), dp(7), dp(3))
            background = GradientDrawable().apply {
                cornerRadius = dpf(5)
                setColor(if (isUber) Color.BLACK else Color.parseColor("#FFC800"))
                if (isUber) { setStroke(dp(1), Color.parseColor("#333333")) }
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
        }
        bottomRow.addView(logoBadge)

        val tTime = TextView(context).apply {
            text = fmtMin(totalMin); textSize = 15.75f
            setTextColor(Color.WHITE); setTypeface(Typeface.DEFAULT_BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tKm = TextView(context).apply {
            text = "%.1f km".format(totalKm); textSize = 15.75f
            setTextColor(Color.WHITE); setTypeface(Typeface.DEFAULT_BOLD)
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        bottomRow.addView(tTime); bottomRow.addView(tKm)
        content.addView(bottomRow)

        row.addView(content)
        row.addView(sideBar(dp(8), FrameLayout.LayoutParams.MATCH_PARENT, gradeColor, roundRight = true))

        root.addView(row)
        return root
    }

    private fun sideBar(w: Int, h: Int, color: Int, roundLeft: Boolean = false, roundRight: Boolean = false): android.view.View {
        return android.view.View(context).apply {
            layoutParams = LinearLayout.LayoutParams(w, h)
            background = GradientDrawable().apply {
                setColor(color)
                val r = dpf(16)
                cornerRadii = if (roundLeft)
                    floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
                else if (roundRight)
                    floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
                else floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
            }
        }
    }
}
