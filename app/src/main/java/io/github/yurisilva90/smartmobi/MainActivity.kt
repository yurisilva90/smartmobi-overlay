package io.github.yurisilva90.smartmobi

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import io.github.yurisilva90.smartmobi.storage.TripStorage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUI()
    }

    override fun onResume() {
        super.onResume()
        buildUI()
    }

    private fun buildUI() {
        val dp = resources.displayMetrics.density
        val hasOverlay = Settings.canDrawOverlays(this)
        val hasA11y    = isAccessibilityEnabled()

        val scroll = ScrollView(this)
        val root   = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24).toInt(), dp(48).toInt(), dp(24).toInt(), dp(24).toInt())
        }

        // Logo + Title
        root.addView(text("SmartMobi", 28f, "#0F172A", bold = true))
        root.addView(text("Overlay para motoristas", 16f, "#64748B"))
        root.addView(spacer(dp(32).toInt()))

        // Status
        root.addView(text("STATUS", 11f, "#94A3B8", bold = true, letterSpacing = true))
        root.addView(spacer(dp(10).toInt()))
        root.addView(statusCard(
            "1. Sobreposição sobre apps",
            if (hasOverlay) "✓ Ativado" else "✗ Pendente",
            if (hasOverlay) "#16A34A" else "#EF4444"
        ))
        root.addView(spacer(dp(8).toInt()))
        root.addView(statusCard(
            "2. Serviço de Acessibilidade",
            if (hasA11y) "✓ Ativo" else "✗ Pendente",
            if (hasA11y) "#16A34A" else "#EF4444"
        ))
        root.addView(spacer(dp(24).toInt()))

        // Action buttons
        if (!hasOverlay) {
            root.addView(btn("Permitir sobreposição", "#0EA5E9") {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")))
            })
            root.addView(spacer(dp(10).toInt()))
        }
        if (!hasA11y) {
            root.addView(btn("Ativar acessibilidade", "#22C55E") {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            })
            root.addView(spacer(dp(10).toInt()))
        }

        // Open SmartMobi web
        root.addView(btn("Abrir SmartMobi", "#0F172A") {
            try {
                startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://yurisilva90.github.io/smartmobi/")))
            } catch (e: Exception) {}
        })

        // Today stats
        root.addView(spacer(dp(32).toInt()))
        root.addView(text("HOJE", 11f, "#94A3B8", bold = true, letterSpacing = true))
        root.addView(spacer(dp(10).toInt()))

        val total = TripStorage.getTodayTotal(this)
        val count = TripStorage.getTodayCount(this)
        val km    = TripStorage.getTodayKm(this)

        root.addView(statsCard(total, count, km))

        if (hasOverlay && hasA11y) {
            root.addView(spacer(dp(16).toInt()))
            root.addView(text("✓ Tudo configurado! Abra o Uber ou 99 Motorista e o overlay aparecerá automaticamente ao final de cada corrida.", 14f, "#16A34A"))
        }

        scroll.addView(root)
        setContentView(scroll)
    }

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        val services = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)
        return services.any { it.resolveInfo.serviceInfo.packageName == packageName }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density)

    private fun text(t: String, size: Float, color: String, bold: Boolean = false, letterSpacing: Boolean = false): TextView {
        return TextView(this).apply {
            text = t
            textSize = size
            setTextColor(android.graphics.Color.parseColor(color))
            if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
            if (letterSpacing) this.letterSpacing = 0.1f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(4).toInt() }
        }
    }

    private fun spacer(h: Int): android.view.View {
        return android.view.View(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, h)
        }
    }

    private fun statusCard(title: String, status: String, color: String): LinearLayout {
        val dp = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp * 16
                setColor(android.graphics.Color.parseColor("#F8FAFC"))
                setStroke((dp).toInt(), android.graphics.Color.parseColor("#E2E8F0"))
            }
            setPadding((dp*14).toInt(), (dp*12).toInt(), (dp*14).toInt(), (dp*12).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

            addView(text(title, 14f, "#344054", bold = true).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(text(status, 13f, color, bold = true))
        }
    }

    private fun statsCard(total: Double, count: Int, km: Double): LinearLayout {
        val dp = resources.displayMetrics.density
        val fmt = java.text.NumberFormat.getCurrencyInstance(Locale("pt","BR"))
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp * 20
                setColor(android.graphics.Color.parseColor("#F0FDF4"))
                setStroke((dp).toInt(), android.graphics.Color.parseColor("#22C55E"))
            }
            setPadding((dp*16).toInt(), (dp*16).toInt(), (dp*16).toInt(), (dp*16).toInt())

            addView(text(fmt.format(total), 32f, "#16A34A", bold = true))
            addView(text("$count corridas · ${String.format("%.1f", km)} km", 13f, "#64748B"))
            addView(text(SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date()), 11f, "#94A3B8"))
        }
    }

    private fun btn(label: String, color: String, onClick: () -> Unit): Button {
        val dp = resources.displayMetrics.density
        return Button(this).apply {
            text = label
            textSize = 15f
            setTextColor(android.graphics.Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp * 18
                setColor(android.graphics.Color.parseColor(color))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (dp*54).toInt())
            setOnClickListener { onClick() }
            isAllCaps = false
        }
    }
}
