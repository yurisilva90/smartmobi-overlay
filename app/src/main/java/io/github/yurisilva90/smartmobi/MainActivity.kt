package io.github.yurisilva90.smartmobi

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.*
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var webProgress: ProgressBar
    private lateinit var splashView: View
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var splashDone = false
    private var webReady   = false

    companion object {
        const val URL      = "https://yurisilva90.github.io/smartmobi/"
        const val REQ_PERM = 100
        const val REQ_FILE = 101
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                        WindowManager.LayoutParams.FLAG_FULLSCREEN)
        supportActionBar?.hide()

        // Root container
        val root = FrameLayout(this)

        // WebView (atrás)
        webView = WebView(this)
        root.addView(webView, FrameLayout.LayoutParams(-1, -1))

        // Progress bar fina no topo
        webProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; visibility = View.GONE
        }
        root.addView(webProgress, FrameLayout.LayoutParams(-1, 5))

        // Splash (na frente)
        splashView = layoutInflater.inflate(R.layout.splash, root, false)
        root.addView(splashView, FrameLayout.LayoutParams(-1, -1))

        setContentView(root)

        requestAppPermissions()
        setupWebView()
        webView.loadUrl(URL)

        // Splash mínimo de 2s
        Handler(Looper.getMainLooper()).postDelayed({
            splashDone = true
            maybeHideSplash()
        }, 2000)
    }

    private fun maybeHideSplash() {
        if (splashDone && webReady) {
            splashView.animate().alpha(0f).setDuration(300).withEndAction {
                splashView.visibility = View.GONE
            }.start()
        }
    }

    private fun requestAppPermissions() {
        val needed = mutableListOf<String>()
        listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.CAMERA
        ).forEach {
            if (ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED)
                needed.add(it)
        }
        if (needed.isNotEmpty())
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQ_PERM)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled                = true
            domStorageEnabled                = true
            databaseEnabled                  = true
            cacheMode                        = WebSettings.LOAD_DEFAULT
            setSupportZoom(false)
            displayZoomControls              = false
            builtInZoomControls              = false
            useWideViewPort                  = true
            loadWithOverviewMode             = true
            allowFileAccess                  = true
            setGeolocationEnabled(true)
            setGeolocationDatabasePath(filesDir.absolutePath)
            mediaPlaybackRequiresUserGesture = false
        }

        // Bridge nativa → JS
        webView.addJavascriptInterface(object {
            @JavascriptInterface fun isNativeApp() = true
            @JavascriptInterface fun getVersion()  = "1.3.0"
            @JavascriptInterface fun startGpsService() {
                val i = Intent(this@MainActivity, GpsService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    startForegroundService(i) else startService(i)
            }
            @JavascriptInterface fun stopGpsService() =
                stopService(Intent(this@MainActivity, GpsService::class.java))
        }, "SmartMobiNative")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, p: Int) {
                webProgress.progress = p
                webProgress.visibility = if (p < 100) View.VISIBLE else View.GONE
            }
            override fun onGeolocationPermissionsShowPrompt(
                origin: String, callback: GeolocationPermissions.Callback
            ) = callback.invoke(origin, true, false)

            override fun onPermissionRequest(req: PermissionRequest) =
                req.grant(req.resources)

            override fun onShowFileChooser(
                view: WebView, cb: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileCallback = cb
                try { startActivityForResult(params.createIntent(), REQ_FILE) }
                catch (e: Exception) { cb.onReceiveValue(arrayOf()); fileCallback = null }
                return true
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                val url = r.url.toString()
                return if (url.startsWith("https://yurisilva90.github.io")) false
                else { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))); true }
            }
            override fun onPageFinished(view: WebView, url: String) {
                webView.evaluateJavascript(
                    "window._smartmobiNative=true;window._nativeVersion='1.3.0';" +
                    "if(typeof onNativeReady==='function')onNativeReady();", null)
                webReady = true
                maybeHideSplash()
            }
        }
    }

    @Deprecated("Deprecated")
    override fun onActivityResult(req: Int, result: Int, data: Intent?) {
        super.onActivityResult(req, result, data)
        if (req == REQ_FILE) {
            fileCallback?.onReceiveValue(
                if (data?.data != null) arrayOf(data.data!!) else arrayOf())
            fileCallback = null
        }
    }

    override fun onKeyDown(k: Int, e: KeyEvent): Boolean {
        if (k == KeyEvent.KEYCODE_BACK && webView.canGoBack()) { webView.goBack(); return true }
        return super.onKeyDown(k, e)
    }

    override fun onResume()  { super.onResume();  webView.onResume() }
    override fun onPause()   { webView.onPause(); super.onPause() }
    override fun onDestroy() { webView.destroy(); super.onDestroy() }
}
