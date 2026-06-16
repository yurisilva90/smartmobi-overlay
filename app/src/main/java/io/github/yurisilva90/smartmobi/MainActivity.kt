package io.github.yurisilva90.smartmobi

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
    private lateinit var progress: ProgressBar
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    companion object {
        const val URL      = "https://yurisilva90.github.io/smartmobi/"
        const val REQ_PERM = 100
        const val REQ_FILE = 101
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                        WindowManager.LayoutParams.FLAG_FULLSCREEN)
        supportActionBar?.hide()

        val root = FrameLayout(this)
        webView  = WebView(this).also { root.addView(it, FrameLayout.LayoutParams(-1, -1)) }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).also {
            it.max = 100; root.addView(it, FrameLayout.LayoutParams(-1, 6))
        }
        setContentView(root)
        requestPermissions()
        setupWebView()
        webView.loadUrl(URL)
    }

    private fun requestPermissions() {
        val needed = mutableListOf<String>()
        val perms  = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.CAMERA
        )
        perms.forEach {
            if (ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED)
                needed.add(it)
        }
        if (needed.isNotEmpty())
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQ_PERM)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled               = true
            domStorageEnabled               = true
            databaseEnabled                 = true
            cacheMode                       = WebSettings.LOAD_DEFAULT
            setSupportZoom(false)
            displayZoomControls             = false
            builtInZoomControls             = false
            useWideViewPort                 = true
            loadWithOverviewMode            = true
            allowFileAccess                 = true
            setGeolocationEnabled(true)
            setGeolocationDatabasePath(filesDir.absolutePath)
        }

        webView.addJavascriptInterface(object {
            @JavascriptInterface fun isNativeApp() = true
            @JavascriptInterface fun getVersion()  = "1.3.0"
            @JavascriptInterface fun startGpsService() {
                val i = Intent(this@MainActivity, GpsService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    startForegroundService(i) else startService(i)
            }
            @JavascriptInterface fun stopGpsService() {
                stopService(Intent(this@MainActivity, GpsService::class.java))
            }
        }, "SmartMobiNative")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, p: Int) {
                progress.progress = p
                progress.visibility = if (p < 100) View.VISIBLE else View.GONE
            }
            // GPS no WebView
            override fun onGeolocationPermissionsShowPrompt(
                origin: String, callback: GeolocationPermissions.Callback
            ) { callback.invoke(origin, true, false) }

            // Câmera e galeria
            override fun onPermissionRequest(req: PermissionRequest) = req.grant(req.resources)
            override fun onShowFileChooser(
                view: WebView, cb: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileCallback = cb
                startActivityForResult(params.createIntent(), REQ_FILE)
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
            }
        }
    }

    override fun onActivityResult(req: Int, result: Int, data: Intent?) {
        super.onActivityResult(req, result, data)
        if (req == REQ_FILE) {
            fileCallback?.onReceiveValue(
                if (data?.data != null) arrayOf(data.data!!) else arrayOf()
            )
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
