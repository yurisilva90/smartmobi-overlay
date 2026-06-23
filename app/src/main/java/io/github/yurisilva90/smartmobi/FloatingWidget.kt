package io.github.yurisilva90.smartmobi

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

class FloatingWidget(private val context: Context) {

    private val wm      = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var container: LinearLayout? = null
    private var tvTime: TextView? = null
    private var tvKm:   TextView? = null
    private var km = 0.0

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.END; x = 16; y = 180 }

    // Tick a cada segundo — para automaticamente quando pausado
    private val tickRunnable = object : Runnable {
        override fun run() {
            updateDisplay()
            // Se pausado, re-agendar com intervalo maior (economiza bateria)
            val delay = if (GpsService.isPaused) 5000L else 1000L
            handler.postDelayed(this, delay)
        }
    }

    fun show(startTimestamp: Long, currentKm: Double) {
        // Atualiza o km; NÃO reseta estado de pausa (lido direto do GpsService)
        if (startTimestamp > 0) GpsService.startTimeMs = startTimestamp
        km = currentKm
        if (container != null) { updateDisplay(); return }
        handler.post {
            container = buildWidget()
            try { wm.addView(container, params) } catch (e: Exception) { e.printStackTrace() }
            handler.post(tickRunnable)
        }
    }

    fun updateKm(newKm: Double) { km = newKm; updateDisplay() }

    fun updateStatus(status: String) {
        // Apenas atualiza cores/labels — o estado de pausa real está no GpsService
        handler.post {
            val color = when(status) {
                "running" -> "#22C55E"
                "paused"  -> "#94A3B8"
                "stopped" -> "#EF4444"
                else      -> "#22C55E"
            }
            val label = when(status) {
                "running" -> "Online"
                "paused"  -> "Pausado"
                "stopped" -> "Offline"
                else      -> "Online"
            }
            val c = Color.parseColor(color)
            container?.findViewWithTag<TextView>("status_tv")?.apply { text = label; setTextColor(c) }
            container?.findViewWithTag<FrameLayout>("status_dot")?.apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c) }
            }
            tvKm?.setTextColor(c)
            container?.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = (20 * context.resources.displayMetrics.density)
                setColor(Color.parseColor("#0F172A"))
                setStroke((2 * context.resources.displayMetrics.density).toInt(), c)
            }
            updateDisplay()
        }
    }

    fun hide() {
        handler.removeCallbacks(tickRunnable)
        handler.post {
            container?.let { try { wm.removeView(it) } catch (e: Exception) {} }
            container = null
        }
    }

    private fun updateDisplay() {
        // ── FONTE AUTORITATIVA: lê sempre do GpsService companion ──────────
        // Nunca usa cópias locais — assim a bolinha nunca fica dessincronizada
        // com o estado real da jornada, mesmo após recriações do widget.
        val gStart      = GpsService.startTimeMs
        val gPausedMs   = GpsService.pausedMs
        val gIsPaused   = GpsService.isPaused
        val gPauseStart = GpsService.pauseStartMs

        if (gStart <= 0L) { tvTime?.text = "00:00"; tvKm?.text = "0.0 km"; return }

        // Tempo total acumulado em pausa (inclui a pausa atual se ainda ativa)
        val pausedTotal = gPausedMs + (if (gIsPaused) System.currentTimeMillis() - gPauseStart else 0L)

        // Elapsed: se pausado congela no momento em que pausou
        val elapsedMs = if (gIsPaused)
            (gPauseStart - gStart - gPausedMs).coerceAtLeast(0L)
        else
            (System.currentTimeMillis() - gStart - pausedTotal).coerceAtLeast(0L)

        val totalSec = elapsedMs / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60

        tvTime?.text = "%02d:%02d".format(h, m)
        tvKm?.text   = "%.1f km".format(km)
    }

    private fun buildWidget(): LinearLayout {
        val dp = { v: Int -> (v * context.resources.displayMetrics.density).toInt() }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(20).toFloat()
                setColor(Color.parseColor("#0F172A"))
                setStroke(dp(2), Color.parseColor("#22C55E"))
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            elevation = dp(8).toFloat()
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        val statusDot = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(7), dp(7)).apply { rightMargin = dp(5) }
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#22C55E")) }
            tag = "status_dot"
        }
        header.addView(statusDot)
        val statusTv = TextView(context).apply {
            text = "Online"; textSize = 10f
            setTextColor(Color.parseColor("#22C55E"))
            setTypeface(null, Typeface.BOLD); tag = "status_tv"
        }
        header.addView(statusTv)
        card.addView(header)

        tvTime = TextView(context).apply {
            text = "00:00"; textSize = 20f
            setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD)
        }
        card.addView(tvTime)

        tvKm = TextView(context).apply {
            text = "0.0 km"; textSize = 13f
            setTextColor(Color.parseColor("#22C55E")); setTypeface(null, Typeface.BOLD)
        }
        card.addView(tvKm)

        // Touch: arrastar + tap para abrir app
        var dX = 0f; var dY = 0f; var moved = false
        card.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN  -> { dX = params.x - ev.rawX; dY = params.y - ev.rawY; moved = false; true }
                MotionEvent.ACTION_MOVE  -> {
                    val nx = (ev.rawX + dX).toInt(); val ny = (ev.rawY + dY).toInt()
                    if (Math.abs(nx - params.x) > 5 || Math.abs(ny - params.y) > 5) moved = true
                    params.x = nx; params.y = ny
                    try { wm.updateViewLayout(card, params) } catch (e: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP    -> {
                    if (!moved) {
                        context.startActivity(Intent(context, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                            putExtra("open_screen", "jornada")
                        })
                    }
                    true
                }
                else -> false
            }
        }
        return card
    }
}
