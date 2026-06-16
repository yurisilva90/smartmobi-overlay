package io.github.yurisilva90.smartmobi.accessibility

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import io.github.yurisilva90.smartmobi.overlay.OverlayView
import io.github.yurisilva90.smartmobi.parser.ScreenParser

class SmartMobiAccessibility : AccessibilityService() {

    companion object {
        const val TAG    = "SmartMobiA11y"
        const val PKG_UBER = "com.ubercab.driver"
        const val PKG_99   = "com.app99.driver"
    }

    private lateinit var overlayView: OverlayView
    private var lastTripValue: Double = 0.0
    private var lastTripTimestamp: Long = 0L
    private val DEBOUNCE_MS = 15_000L   // 15s entre capturas

    override fun onServiceConnected() {
        overlayView = OverlayView(applicationContext)
        Log.d(TAG, "SmartMobi Accessibility conectado — monitorando Uber e 99")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (pkg != PKG_UBER && pkg != PKG_99) return

        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return

        val root  = rootInActiveWindow ?: return
        val texts = ScreenParser.collectAllText(root)

        val completed = when (pkg) {
            PKG_UBER -> ScreenParser.isUberTripCompleted(texts)
            PKG_99   -> ScreenParser.is99TripCompleted(texts)
            else     -> false
        }
        if (!completed) return

        val now = System.currentTimeMillis()
        if (now - lastTripTimestamp < DEBOUNCE_MS) return

        val platform = if (pkg == PKG_UBER) "uber" else "99"
        val trip = ScreenParser.extractTripData(texts, platform) ?: return

        // Dedup por valor igual em janela de 30s
        if (Math.abs(trip.value - lastTripValue) < 0.02 &&
            now - lastTripTimestamp < 30_000L) return

        lastTripValue     = trip.value
        lastTripTimestamp = now

        Log.d(TAG, "Corrida detectada: ${trip.formatValue()} · ${trip.formatKm()} · $platform")

        // Mostra overlay com botões Salvar / Ignorar
        // O salvamento só acontece se o motorista tocar em "Salvar"
        overlayView.show(trip, applicationContext)
    }

    override fun onInterrupt() {
        if (::overlayView.isInitialized) overlayView.dismiss()
    }

    override fun onDestroy() {
        if (::overlayView.isInitialized) overlayView.dismiss()
        super.onDestroy()
    }
}
