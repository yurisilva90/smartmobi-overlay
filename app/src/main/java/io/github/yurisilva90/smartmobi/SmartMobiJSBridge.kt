package io.github.yurisilva90.smartmobi

import android.content.Context
import android.webkit.JavascriptInterface

// Ponte JavaScript ↔ Android
// Chamado pelo SmartMobi web via: SmartMobiNative.nomeDaFuncao()
class SmartMobiJSBridge(private val context: Context) {

    @JavascriptInterface
    fun openOverlaySettings() {
        (context as? MainActivity)?.openOverlaySettings()
    }

    @JavascriptInterface
    fun openA11ySettings() {
        (context as? MainActivity)?.openA11ySettings()
    }

    @JavascriptInterface
    fun hasOverlayPermission(): Boolean =
        android.provider.Settings.canDrawOverlays(context)

    @JavascriptInterface
    fun isNativeApp(): Boolean = true

    @JavascriptInterface
    fun getVersion(): String = "1.0.0-webview"

    @JavascriptInterface
    fun getTodayTripsJson(): String {
        val trips = io.github.yurisilva90.smartmobi.storage.TripStorage.getTodayTrips(context)
        return com.google.gson.Gson().toJson(trips)
    }

    @JavascriptInterface
    fun vibrate(ms: Long) {
        val vib = context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
        vib?.vibrate(ms)
    }
}
