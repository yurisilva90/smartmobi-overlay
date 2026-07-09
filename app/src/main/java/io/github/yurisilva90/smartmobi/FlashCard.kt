package io.github.yurisilva90.smartmobi

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

// ══════════════════════════════════════════════════════════════════
// MōB Flash — card flutuante de decisão rápida sobre a oferta.
// Aparece por cima do app da 99/Uber. SÓ EXIBE — nunca toca em nada.
//
// TESTE DE VISUAL: por enquanto é populado com dados AMOSTRA (fixos),
// não com valores reais extraídos da oferta — isso ainda depende do
// pipeline de OCR que está em teste separado. O objetivo aqui é
// validar posição, legibilidade e cores no aparelho real.
// ══════════════════════════════════════════════════════════════════
class FlashCard(private val context: Context) {

    data class Metric(val label: String, val value: String, val grade: String) // grade: "g"/"a"/"r"

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var container: FrameLayout? = null
    private val hideRunnable = Runnable { hide() }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()
    private fun dpf(v: Int) = (v * context.resources.displayMetrics.density)

    private fun colorOf(grade: String) = when (grade) {
        "g" -> Color.parseColor("#10B981")
        "a" -> Color.parseColor("#F59E0B")
        else -> Color.parseColor("#EF4444")
    }

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = dp(58) }

    // platform: "99" ou "UBER". overallGrade: "g"/"a"/"r" — cor da borda dupla.
    // metrics: até 4 (R$/km, R$/hora, R$/min, Nota), cada uma com sua própria cor.
    // totalMin/totalKm: tempo e km somando deslocamento até o passageiro + a corrida.
    fun show(platform: String, overallGrade: String, metrics: List<Metric>, totalMin: Int, totalKm: Double, autoHideMs: Long = 8000L) {
        handler.removeCallbacks(hideRunnable)
        handler.post {
            container?.let { try { wm.removeView(it) } catch (_: Exception) {} }
            container = buildCard(platform, overallGrade, metrics, totalMin, totalKm)
            try { wm.addView(container, params) } catch (e: Exception) { e.printStackTrace() }
            handler.postDelayed(hideRunnable, autoHideMs)
        }
    }

    fun hide() {
        handler.removeCallbacks(hideRunnable)
        handler.post {
            container?.let { try { wm.removeView(it) } catch (_: Exception) {} }
            container = null
        }
    }

    private fun buildCard(platform: String, overallGrade: String, metrics: List<Metric>, totalMin: Int, totalKm: Double): FrameLayout {
        val gradeColor = colorOf(overallGrade)
        val cardWidth = dp(272)

        // ── raiz: linha horizontal [barra esquerda] [conteúdo] [barra direita] ──
        val root = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(cardWidth, FrameLayout.LayoutParams.WRAP_CONTENT)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpf(16)
                setColor(Color.parseColor("#E60E0E17"))  // dark chrome, quase opaco
            }
            elevation = dpf(10)
        }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        }

        // barra colorida esquerda ("borda dos dois lados")
        row.addView(sideBar(dp(5), FrameLayout.LayoutParams.MATCH_PARENT, gradeColor, roundLeft = true))

        // conteúdo central
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        // topo: logo da plataforma
        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val logoRes = if (platform == "UBER") R.drawable.ic_platform_uber else R.drawable.ic_platform_99
        val logoIv = ImageView(context).apply {
            setImageResource(logoRes)
            val h = dp(14)
            val w = if (platform == "UBER") dp(40) else dp(14)
            layoutParams = LinearLayout.LayoutParams(w, h)
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = GradientDrawable().apply { cornerRadius = dpf(3); setColor(Color.WHITE) }
            val pad = dp(2); setPadding(pad, pad, pad, pad)
        }
        topRow.addView(logoIv)
        content.addView(topRow)

        val spacer1 = TextView(context).apply { layoutParams = LinearLayout.LayoutParams(0, dp(6)) }
        content.addView(spacer1)

        // grade de métricas — todas do MESMO tamanho, lado a lado
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
                text = m.value; textSize = 15f
                setTextColor(colorOf(m.grade)); setTypeface(Typeface.DEFAULT_BOLD)
                gravity = Gravity.CENTER
            }
            val l = TextView(context).apply {
                text = m.label; textSize = 8f
                setTextColor(Color.parseColor("#8A8A99")); setTypeface(Typeface.DEFAULT_BOLD)
                gravity = Gravity.CENTER; setPadding(0, dp(2), 0, 0)
            }
            tile.addView(n); tile.addView(l)
            metricsRow.addView(tile)
        }
        content.addView(metricsRow)

        // divisor
        val div = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(9); bottomMargin = dp(7)
            }
            setBackgroundColor(Color.parseColor("#1FFFFFFF"))
        }
        content.addView(div)

        // rodapé: tempo total + km total (deslocamento + corrida)
        val bottomRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val tTime = TextView(context).apply {
            text = "Total: ${totalMin} min"; textSize = 10.5f
            setTextColor(Color.parseColor("#8A8A99")); setTypeface(Typeface.DEFAULT_BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tKm = TextView(context).apply {
            text = "%.1f km".format(totalKm); textSize = 10.5f
            setTextColor(Color.WHITE); setTypeface(Typeface.DEFAULT_BOLD)
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        bottomRow.addView(tTime); bottomRow.addView(tKm)
        content.addView(bottomRow)

        row.addView(content)

        // barra colorida direita
        row.addView(sideBar(dp(5), FrameLayout.LayoutParams.MATCH_PARENT, gradeColor, roundRight = true))

        root.addView(row)
        return root
    }

    // View sólida colorida usada como as duas barras laterais
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
