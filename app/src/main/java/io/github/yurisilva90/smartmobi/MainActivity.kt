package io.github.yurisilva90.smartmobi

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.*
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar

    companion object {
        const val SMARTMOBI_URL = "https://yurisilva90.github.io/smartmobi/"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Tela cheia — sem action bar, sem status bar
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        supportActionBar?.hide()

        val root = FrameLayout(this)
        webView  = WebView(this).also { root.addView(it, FrameLayout.LayoutParams(-1, -1)) }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).also {
            it.max = 100
            root.addView(it, FrameLayout.LayoutParams(-1, 6))
        }
        setContentView(root)

        setupWebView()
        webView.loadUrl(SMARTMOBI_URL)
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
            mediaPlaybackRequiresUserGesture = false
        }

        // Bridge mínima — app detecta que está rodando nativo
        webView.addJavascriptInterface(object {
            @JavascriptInterface fun isNativeApp() = true
            @JavascriptInterface fun getVersion()  = "1.2.0-webview"
        }, "SmartMobiNative")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, p: Int) {
                progress.progress = p
                progress.visibility = if (p < 100) View.VISIBLE else View.GONE
            }
            // Permite câmera para o import por foto
            override fun onPermissionRequest(req: PermissionRequest) = req.grant(req.resources)
            // Permite seleção de arquivo (galeria/câmera)
            override fun onShowFileChooser(
                view: WebView, callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                startActivityForResult(params.createIntent(), 1001)
                fileCallback = callback
                return true
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val url = req.url.toString()
                return if (url.startsWith("https://yurisilva90.github.io")) false
                else { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))); true }
            }
            override fun onPageFinished(view: WebView, url: String) {
                // Sinaliza que está rodando como app nativo
                webView.evaluateJavascript(
                    "window._smartmobiNative=true;" +
                    "if(typeof onNativeReady==='function')onNativeReady();", null)
            }
        }
    }

    private var fileCallback: ValueCallback<Array<Uri>>? = null

    override fun onActivityResult(req: Int, result: Int, data: Intent?) {
        super.onActivityResult(req, result, data)
        if (req == 1001) {
            fileCallback?.onReceiveValue(
                if (data?.data != null) arrayOf(data.data!!) else arrayOf()
            )
            fileCallback = null
        }
    }

    override fun onKeyDown(key: Int, event: KeyEvent): Boolean {
        if (key == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack(); return true
        }
        return super.onKeyDown(key, event)
    }

    override fun onResume()  { super.onResume();  webView.onResume() }
    override fun onPause()   { webView.onPause(); super.onPause() }
    override fun onDestroy() { webView.destroy(); super.onDestroy() }
}
