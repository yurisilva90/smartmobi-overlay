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

    private val wm     = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var container: LinearLayout? = null
    private var tvTime: TextView? = null
    private var tvKm:   TextView? = null
    private var startMs = 0L
    private var km      = 0.0
    private var isExpanded = false

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.END; x = 16; y = 180 }

    private val tickRunnable = object : Runnable {
        override fun run() {
            updateDisplay()
            handler.postDelayed(this, 1000)
        }
    }

    fun show(startTimestamp: Long, currentKm: Double) {
        startMs = startTimestamp
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
        handler.post {
            val color = when(status) {
                "running" -> "#22C55E"
                "paused"  -> "#94A3B8"
                "stopped" -> "#EF4444"
                else      -> "#22C55E"
            }
            val statusChar = when(status) {
                "running" -> "● SM"
                "paused"  -> "⏸ SM"
                "stopped" -> "■ SM"
                else      -> "● SM"
            }
            container?.findViewWithTag<android.widget.TextView>("status_tv")?.apply {
                text = statusChar
                setTextColor(android.graphics.Color.parseColor(color))
            }
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
        val elapsed = if (startMs > 0) (System.currentTimeMillis() - startMs) / 1000 else 0L
        val h = elapsed / 3600; val m = (elapsed % 3600) / 60
        tvTime?.text = "${"%02d".format(h)}:${"%02d".format(m)}"
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

        // Dot verde + "SM"
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val statusTv = TextView(context).apply {
            text = "● SM"; textSize = 10f
            setTextColor(Color.parseColor("#22C55E"))
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, dp(6), 0)
            tag = "status_tv"
        }
        header.addView(statusTv)
        card.addView(header)

        // Cronômetro
        tvTime = TextView(context).apply {
            text = "00:00"; textSize = 20f
            setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD)
        }
        card.addView(tvTime)

        // KM
        tvKm = TextView(context).apply {
            text = "0.0 km"; textSize = 13f
            setTextColor(Color.parseColor("#22C55E")); setTypeface(null, Typeface.BOLD)
        }
        card.addView(tvKm)

        // Touch: arrastar + tap para abrir app
        var dX = 0f; var dY = 0f; var moved = false
        card.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> { dX = params.x - ev.rawX; dY = params.y - ev.rawY; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val nx = (ev.rawX + dX).toInt(); val ny = (ev.rawY + dY).toInt()
                    if (Math.abs(nx - params.x) > 5 || Math.abs(ny - params.y) > 5) moved = true
                    params.x = nx; params.y = ny
                    try { wm.updateViewLayout(card, params) } catch (e: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        // Tap: abre o SmartMobi
                        val intent = Intent(context, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        }
                        context.startActivity(intent)
                    }
                    true
                }
                else -> false
            }
        }
        return card
    }
}
