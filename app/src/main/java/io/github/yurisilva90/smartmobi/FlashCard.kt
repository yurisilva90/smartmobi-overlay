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
// Suporta N métricas (o usuário escolhe quais na tela de configuração),
// quebrando em várias linhas de até 3 quando precisa.
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

    // Largura FIXA em pixels — não pode ser WRAP_CONTENT aqui. A janela do
    // WindowManager é o "root" da hierarquia; se ela for WRAP_CONTENT e o
    // conteúdo interno pedir MATCH_PARENT, o Android não consegue resolver
    // o tamanho e o conteúdo colapsa pra ~0 (foi a causa do card aparecer
    // como uma tarja fina sem números — só as bordas coloridas sobravam).
    private val cardWidthPx = dp(296)

    private val params = WindowManager.LayoutParams(
        cardWidthPx,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = dp(58) }

    // platform: "99" ou "UBER". overallGrade: pior nota entre as métricas ativas.
    // metrics: quantas o usuário tiver selecionado — quebra em linhas de até 3.
    fun show(platform: String, overallGrade: String, metrics: List<Metric>, totalMin: Int, totalKm: Double, autoHideMs: Long = 15000L) {
        if (metrics.isEmpty()) return
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
        val maxPerRow = 3

        val root = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(cardWidthPx, FrameLayout.LayoutParams.WRAP_CONTENT)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpf(16)
                setColor(Color.parseColor("#E60E0E17"))
            }
            elevation = dpf(10)
        }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            // Largura EXPLÍCITA (não MATCH_PARENT) — root já tem largura fixa
            // definida acima, então isso é só espelhar o mesmo valor.
            layoutParams = FrameLayout.LayoutParams(cardWidthPx, FrameLayout.LayoutParams.WRAP_CONTENT)
        }

        row.addView(sideBar(dp(5), FrameLayout.LayoutParams.MATCH_PARENT, gradeColor, roundLeft = true))

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(dp(13), dp(11), dp(13), dp(11))
        }

        // topo: logo da plataforma — SEM fundo branco, só a imagem
        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val logoRes = if (platform == "UBER") R.drawable.ic_platform_uber else R.drawable.ic_platform_99
        val logoIv = ImageView(context).apply {
            setImageResource(logoRes)
            val h = dp(16)
            val w = if (platform == "UBER") dp(46) else dp(16)
            layoutParams = LinearLayout.LayoutParams(w, h)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        topRow.addView(logoIv)
        content.addView(topRow)

        val spacer1 = TextView(context).apply { layoutParams = LinearLayout.LayoutParams(0, dp(7)) }
        content.addView(spacer1)

        // métricas em linhas de até 3, com valores maiores
        metrics.chunked(maxPerRow).forEachIndexed { idx, chunk ->
            if (idx > 0) content.addView(TextView(context).apply { layoutParams = LinearLayout.LayoutParams(0, dp(6)) })
            val metricsRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            chunk.forEach { m ->
                val tile = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val n = TextView(context).apply {
                    text = m.value; textSize = 22f
                    setTextColor(colorOf(m.grade)); setTypeface(Typeface.DEFAULT_BOLD)
                    gravity = Gravity.CENTER
                    maxLines = 1
                }
                val l = TextView(context).apply {
                    text = m.label; textSize = 8.5f
                    setTextColor(Color.parseColor("#8A8A99")); setTypeface(Typeface.DEFAULT_BOLD)
                    gravity = Gravity.CENTER; setPadding(0, dp(3), 0, 0)
                }
                tile.addView(n); tile.addView(l)
                metricsRow.addView(tile)
            }
            content.addView(metricsRow)
        }

        val div = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(10); bottomMargin = dp(8)
            }
            setBackgroundColor(Color.parseColor("#1FFFFFFF"))
        }
        content.addView(div)

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
        row.addView(sideBar(dp(5), FrameLayout.LayoutParams.MATCH_PARENT, gradeColor, roundRight = true))

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
