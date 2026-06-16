package io.github.yurisilva90.smartmobi.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import io.github.yurisilva90.smartmobi.MainActivity
import io.github.yurisilva90.smartmobi.model.TripData
import io.github.yurisilva90.smartmobi.storage.TripStorage

class OverlayView(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var container: LinearLayout? = null
    private val handler = Handler(Looper.getMainLooper())
    private var autoHideRunnable: Runnable? = null

    // Flags: touchable (sem NOT_TOUCH_MODAL) para os botões funcionarem
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        android.graphics.PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.END
        x = 16; y = 100
    }

    fun show(trip: TripData, ctx: Context) {
        handler.post {
            dismiss()
            val todayTotal = TripStorage.getTodayTotal(ctx)
            val todayCount = TripStorage.getTodayCount(ctx)
            val todayKm    = TripStorage.getTodayKm(ctx)
            container = buildCard(trip, todayTotal, todayCount, todayKm)
            try { wm.addView(container, params) } catch (e: Exception) { e.printStackTrace() }
            // Auto-dismiss após 30s se não tocar em nada
            autoHideRunnable = Runnable { dismiss() }
            handler.postDelayed(autoHideRunnable!!, 30_000L)
        }
    }

    fun dismiss() {
        autoHideRunnable?.let { handler.removeCallbacks(it) }
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
        val d = context.resources.displayMetrics.density

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(d,16), dp(d,14), dp(d,16), dp(d,14))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(d,20).toFloat()
                setColor(Color.parseColor("#F0FDF4"))
                setStroke(dp(d,1), Color.parseColor("#22C55E"))
            }
            elevation = dp(d,10).toFloat()
            minimumWidth = dp(d,210)
        }

        // Valor da corrida
        card.addView(TextView(context).apply {
            text = "✓  ${trip.formatValue()}"
            textSize = 22f
            setTextColor(Color.parseColor("#16A34A"))
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, dp(d,4))
        })

        // R$/km e R$/hora
        card.addView(buildRow2(d,
            trip.formatRpmKm(), "R$/km",
            trip.formatRpmHour(), "R$/h",
            "#0F172A"))

        // Km e duração
        card.addView(buildRow2(d,
            trip.formatKm(), "distância",
            trip.formatDuration(), "duração",
            "#64748B"))

        // Divider
        card.addView(android.view.View(context).apply {
            setBackgroundColor(Color.parseColor("#E2E8F0"))
            layoutParams = LinearLayout.LayoutParams(-1, dp(d,1)).apply { setMargins(0,dp(d,8),0,dp(d,8)) }
        })

        // Total do dia
        card.addView(TextView(context).apply {
            text = "Hoje: ${TripData.formatBRL(todayTotal)} · $todayCount corr · ${String.format("%.1f",todayKm)}km"
            textSize = 11f
            setTextColor(Color.parseColor("#64748B"))
            setPadding(0, 0, 0, dp(d,10))
        })

        // Botões: Ignorar | Salvar
        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }

        // Botão Ignorar
        btnRow.addView(Button(context).apply {
            text = "Ignorar"
            textSize = 13f
            isAllCaps = false
            setTextColor(Color.parseColor("#64748B"))
            setTypeface(null, Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(d,14).toFloat()
                setColor(Color.parseColor("#F1F5F9"))
            }
            layoutParams = LinearLayout.LayoutParams(0, dp(d,44), 1f).apply { rightMargin = dp(d,8) }
            setOnClickListener { dismiss() }
        })

        // Botão Salvar
        btnRow.addView(Button(context).apply {
            text = "✓ Salvar"
            textSize = 13f
            isAllCaps = false
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(d,14).toFloat()
                setColor(Color.parseColor("#22C55E"))
            }
            layoutParams = LinearLayout.LayoutParams(0, dp(d,44), 1f)
            setOnClickListener {
                TripStorage.saveTrip(context, trip)
                injectIntoSmartMobi(trip)
                dismiss()
            }
        })

        card.addView(btnRow)
        return card
    }

    private fun injectIntoSmartMobi(trip: TripData) {
        val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        val time = fmt.format(java.util.Date(trip.timestamp))
        val json = """{"id":"${trip.id}","platform":"${trip.platform}","time":"$time","duration":${trip.duration},"origin":"${trip.origin}","dest":"${trip.dest}","value":${trip.value},"km":${trip.km},"category":"${trip.category}","surge":${trip.surge},"airport":${trip.airport},"_source":"overlay"}"""
        MainActivity.instance?.injectTrip(json)
    }

    private fun buildRow2(d: Float, l: String, ll: String, r: String, rl: String, color: String): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(d,2), 0, dp(d,2))
            addView(metricBox(d, l, ll, color))
            addView(android.view.View(context).apply { layoutParams = LinearLayout.LayoutParams(dp(d,20), 1) })
            addView(metricBox(d, r, rl, color))
        }
    }

    private fun metricBox(d: Float, value: String, label: String, color: String): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = value; textSize = 14f
                setTextColor(Color.parseColor(color))
                setTypeface(null, Typeface.BOLD)
            })
            addView(TextView(context).apply {
                text = label; textSize = 10f
                setTextColor(Color.parseColor("#94A3B8"))
            })
        }
    }

    private fun dp(density: Float, v: Int) = (v * density).toInt()
}
