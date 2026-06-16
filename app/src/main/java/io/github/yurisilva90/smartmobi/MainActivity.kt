package io.github.yurisilva90.smartmobi

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var webProgress: ProgressBar
    private lateinit var splashView: FrameLayout
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var splashDone = false
    private var webReady   = false

    companion object {
        const val URL      = "https://yurisilva90.github.io/smartmobi/"
        const val REQ_PERM = 100
        const val REQ_FILE = 101
        var floatingWidget: FloatingWidget? = null
        var instance: MainActivity? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        supportActionBar?.hide()

        val root = FrameLayout(this)
        webView = WebView(this)
        root.addView(webView, FrameLayout.LayoutParams(-1, -1))
        webProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#22C55E"))
        }
        root.addView(webProgress, FrameLayout.LayoutParams(-1, 6))
        splashView = buildSplash()
        root.addView(splashView, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        requestAppPermissions()
        requestOverlayPermission()
        setupWebView()
        webView.loadUrl(URL)
        Handler(Looper.getMainLooper()).postDelayed({ splashDone = true; maybeHideSplash() }, 2000)
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
        }
    }

    private fun buildSplash(): FrameLayout {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val frame = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#0F172A")) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        val iconBg = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.parseColor("#22C55E"), Color.parseColor("#16A34A"))
        ).apply { shape = GradientDrawable.RECTANGLE; cornerRadius = dp(24).toFloat() }
        val icon = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(80), dp(80)).apply { bottomMargin = dp(20) }
            background = iconBg
        }
        icon.addView(TextView(this).apply {
            text = "SM"; textSize = 26f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE); gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(-1, -1)
        })
        col.addView(icon)
        col.addView(TextView(this).apply {
            text = "SmartMobi"; textSize = 28f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE); gravity = Gravity.CENTER
        })
        col.addView(TextView(this).apply {
            text = "Gestao para motoristas"; textSize = 13f
            setTextColor(Color.parseColor("#64748B")); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) }
        })
        col.addView(ProgressBar(this).apply {
            indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#22C55E"))
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(36) }
        })
        frame.addView(col, FrameLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER })
        return frame
    }

    private fun maybeHideSplash() {
        if (splashDone && webReady) {
            splashView.animate().alpha(0f).setDuration(350).withEndAction { splashView.visibility = View.GONE }.start()
        }
    }

    private fun requestAppPermissions() {
        val needed = mutableListOf<String>()
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.CAMERA
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            perms.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
        perms.forEach { if (ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED) needed.add(it) }
        if (needed.isNotEmpty()) ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQ_PERM)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true; databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT; setSupportZoom(false)
            displayZoomControls = false; builtInZoomControls = false
            useWideViewPort = true; loadWithOverviewMode = true; allowFileAccess = true
            setGeolocationEnabled(true); setGeolocationDatabasePath(filesDir.absolutePath)
        }

        // JS Bridge
        webView.addJavascriptInterface(object {
            @JavascriptInterface fun isNativeApp() = true
            @JavascriptInterface fun getVersion()  = "1.4.0"
            @JavascriptInterface fun hasOverlay()  = Settings.canDrawOverlays(this@MainActivity)

            @JavascriptInterface fun startFloating(startMs: Long, km: Double) {
                if (!Settings.canDrawOverlays(this@MainActivity)) return
                if (floatingWidget == null) floatingWidget = FloatingWidget(applicationContext)
                floatingWidget?.show(startMs, km)
                startGpsService()
            }
            @JavascriptInterface fun updateFloating(km: Double) {
                floatingWidget?.updateKm(km)
            }
            @JavascriptInterface fun stopFloating() {
                floatingWidget?.hide()
                floatingWidget = null
                stopGpsService()
            }
            @JavascriptInterface fun startGpsService() {
                val i = Intent(this@MainActivity, GpsService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
            }
            @JavascriptInterface fun stopGpsService() =
                stopService(Intent(this@MainActivity, GpsService::class.java))
        }, "SmartMobiNative")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(v: WebView, p: Int) {
                webProgress.progress = p; webProgress.visibility = if (p < 100) View.VISIBLE else View.GONE
            }
            override fun onGeolocationPermissionsShowPrompt(o: String, cb: GeolocationPermissions.Callback) = cb.invoke(o, true, false)
            override fun onPermissionRequest(r: PermissionRequest) = r.grant(r.resources)
            override fun onShowFileChooser(v: WebView, cb: ValueCallback<Array<Uri>>, p: FileChooserParams): Boolean {
                fileCallback = cb
                try { startActivityForResult(p.createIntent(), REQ_FILE) } catch (e: Exception) { cb.onReceiveValue(arrayOf()); fileCallback = null }
                return true
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                val url = r.url.toString()
                return if (url.startsWith("https://yurisilva90.github.io")) false
                else { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))); true }
            }
            override fun onPageFinished(v: WebView, url: String) {
                webView.evaluateJavascript(
                    "window._smartmobiNative=true;window._nativeVersion='1.4.0';" +
                    "if(typeof onNativeReady==='function')onNativeReady();", null)
                webReady = true; maybeHideSplash()
            }
        }
    }

    private fun startGpsService() {
        val i = Intent(this, GpsService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
    }
    private fun stopGpsService() = stopService(Intent(this, GpsService::class.java))

    @Deprecated("") override fun onActivityResult(req: Int, result: Int, data: Intent?) {
        super.onActivityResult(req, result, data)
        if (req == REQ_FILE) { fileCallback?.onReceiveValue(if (data?.data != null) arrayOf(data.data!!) else arrayOf()); fileCallback = null }
    }
    override fun onKeyDown(k: Int, e: KeyEvent): Boolean {
        if (k == KeyEvent.KEYCODE_BACK && webView.canGoBack()) { webView.goBack(); return true }
        return super.onKeyDown(k, e)
    }
    override fun onResume()  { super.onResume();  instance = this; webView.onResume() }
    override fun onPause()   { webView.onPause(); super.onPause() }
    override fun onDestroy() { instance = null; webView.destroy(); super.onDestroy() }
}
