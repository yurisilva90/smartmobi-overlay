package io.github.yurisilva90.smartmobi.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import io.github.yurisilva90.smartmobi.model.TripData
import io.github.yurisilva90.smartmobi.storage.TripStorage

class OverlayView(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var container: LinearLayout? = null
    private val handler = Handler(Looper.getMainLooper())
    private var hideRunnable: Runnable? = null

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
        android.graphics.PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.END
        x = 16
        y = 120
    }

    fun show(trip: TripData, context: Context) {
        handler.post {
            dismiss()

            val todayTotal = TripStorage.getTodayTotal(context)
            val todayCount = TripStorage.getTodayCount(context)
            val todayKm    = TripStorage.getTodayKm(context)

            container = buildCard(trip, todayTotal, todayCount, todayKm)
            try {
                wm.addView(container, params)
            } catch (e: Exception) { e.printStackTrace() }

            // Auto-dismiss after 8 seconds
            hideRunnable = Runnable { dismiss() }
            handler.postDelayed(hideRunnable!!, 8000L)
        }
    }

    fun dismiss() {
        hideRunnable?.let { handler.removeCallbacks(it) }
        container?.let {
            try { wm.removeView(it) } catch (e: Exception) {}
            container = null
        }
    }

    private fun buildCard(
        trip: TripData,
        todayTotal: Double,
        todayCount: Int,
        todayKm: Double
    ): LinearLayout {
        val dp = context.resources.displayMetrics.density

        // Card background
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(20).toFloat()
                setColor(Color.parseColor("#F0FDF4"))
                setStroke(dp(1), Color.parseColor("#22C55E"))
            }
            elevation = dp(8).toFloat()
            minimumWidth = dp(200)
        }

        // Trip value (large)
        card.addView(row(
            label = "✓  ${trip.formatValue()}",
            labelSize = 22f,
            labelColor = "#16A34A",
            labelBold = true
        ))

        // R$/km and R$/hora
        card.addView(row2(
            left = trip.formatRpmKm(),
            leftLabel = "R$/km",
            right = trip.formatRpmHour(),
            rightLabel = "R$/h",
            color = "#0F172A"
        ))

        // Divider
        card.addView(divider())

        // Daily totals
        card.addView(row2(
            left = TripData.formatBRL(todayTotal),
            leftLabel = "Hoje ($todayCount corridas)",
            right = String.format("%.1f km", todayKm),
            rightLabel = "km total",
            color = "#64748B"
        ))

        return card
    }

    private fun row(label: String, labelSize: Float, labelColor: String, labelBold: Boolean): TextView {
        val dp = context.resources.displayMetrics.density
        return TextView(context).apply {
            text = label
            textSize = labelSize
            setTextColor(Color.parseColor(labelColor))
            if (labelBold) setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(4))
        }
    }

    private fun row2(left: String, leftLabel: String, right: String, rightLabel: String, color: String): LinearLayout {
        val dp = context.resources.displayMetrics.density
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(2), 0, dp(2))
        }
        row.addView(metricBox(left, leftLabel, color, dp))
        row.addView(LinearLayout(context).apply { layoutParams = LinearLayout.LayoutParams(dp(16), 1) })
        row.addView(metricBox(right, rightLabel, color, dp))
        return row
    }

    private fun metricBox(value: String, label: String, color: String, dp: Float): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = value
                textSize = 14f
                setTextColor(Color.parseColor(color))
                setTypeface(null, android.graphics.Typeface.BOLD)
            })
            addView(TextView(context).apply {
                text = label
                textSize = 10f
                setTextColor(Color.parseColor("#94A3B8"))
            })
        }
    }

    private fun divider(): android.view.View {
        val dp = context.resources.displayMetrics.density
        return android.view.View(context).apply {
            setBackgroundColor(Color.parseColor("#E2E8F0"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
            ).apply { setMargins(0, dp(8), 0, dp(8)) }
        }
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
