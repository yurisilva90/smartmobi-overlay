package io.github.yurisilva90.smartmobi.accessibility

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.google.gson.Gson
import io.github.yurisilva90.smartmobi.MainActivity
import io.github.yurisilva90.smartmobi.model.TripData
import io.github.yurisilva90.smartmobi.overlay.OverlayView
import io.github.yurisilva90.smartmobi.parser.ScreenParser
import io.github.yurisilva90.smartmobi.storage.TripStorage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SmartMobiAccessibility : AccessibilityService() {

    companion object {
        const val TAG = "SmartMobiA11y"
        const val PKG_UBER = "com.ubercab.driver"
        const val PKG_99   = "com.app99.driver"
    }

    private lateinit var overlayView: OverlayView
    private val gson = Gson()
    private var lastTripValue: Double = 0.0
    private var lastTripTimestamp: Long = 0L
    private val DEBOUNCE_MS = 10_000L

    override fun onServiceConnected() {
        overlayView = OverlayView(applicationContext)
        Log.d(TAG, "SmartMobi Accessibility Service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (pkg != PKG_UBER && pkg != PKG_99) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return

        val root = rootInActiveWindow ?: return
        val texts = ScreenParser.collectAllText(root)

        val isTripDone = when (pkg) {
            PKG_UBER -> ScreenParser.isUberTripCompleted(texts)
            PKG_99   -> ScreenParser.is99TripCompleted(texts)
            else     -> false
        }
        if (!isTripDone) return

        val now = System.currentTimeMillis()
        if (now - lastTripTimestamp < DEBOUNCE_MS) return

        val platform = if (pkg == PKG_UBER) "uber" else "99"
        val trip = ScreenParser.extractTripData(texts, platform) ?: return

        if (Math.abs(trip.value - lastTripValue) < 0.02 && now - lastTripTimestamp < 30_000L) return

        lastTripValue     = trip.value
        lastTripTimestamp = now

        Log.d(TAG, "Trip: ${trip.formatValue()} · ${trip.formatKm()} · $platform")

        // Save locally
        TripStorage.saveTrip(applicationContext, trip)

        // Show overlay card
        overlayView.show(trip, applicationContext)

        // Inject into SmartMobi WebView (if app is open)
        injectIntoWebView(trip)
    }

    private fun injectIntoWebView(trip: TripData) {
        val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val tripJson = """{"id":"${trip.id}","platform":"${trip.platform}","time":"${timeFmt.format(Date(trip.timestamp))}","duration":${trip.duration},"origin":"${trip.origin}","dest":"${trip.dest}","value":${trip.value},"km":${trip.km},"category":"${trip.category}","surge":${trip.surge},"airport":${trip.airport},"_source":"overlay"}"""
        MainActivity.instance?.injectTrip(tripJson)
    }

    override fun onInterrupt() {
        if (::overlayView.isInitialized) overlayView.dismiss()
    }

    override fun onDestroy() {
        if (::overlayView.isInitialized) overlayView.dismiss()
        super.onDestroy()
    }
}
