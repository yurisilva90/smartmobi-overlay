package io.github.yurisilva90.smartmobi

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.provider.Settings
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
        var instance: MainActivity? = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this

        // Fullscreen — sem barra de status nem navegação
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        supportActionBar?.hide()

        // Layout
        val root = FrameLayout(this)
        webView = WebView(this).also { root.addView(it, FrameLayout.LayoutParams(-1, -1)) }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).also {
            it.max = 100
            it.isIndeterminate = false
            root.addView(it, FrameLayout.LayoutParams(-1, 8))
        }
        setContentView(root)

        setupWebView()
        checkPermissionsAndLoad()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled      = true
            domStorageEnabled      = true
            databaseEnabled        = true
            cacheMode              = WebSettings.LOAD_DEFAULT
            setSupportZoom(false)
            displayZoomControls    = false
            builtInZoomControls    = false
            useWideViewPort        = true
            loadWithOverviewMode   = true
            allowFileAccess        = true
            mediaPlaybackRequiresUserGesture = false
        }

        // JavaScript bridge — native → web
        webView.addJavascriptInterface(SmartMobiJSBridge(this), "SmartMobiNative")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
            }
            override fun onPermissionRequest(request: PermissionRequest) {
                request.grant(request.resources) // Câmera, microfone para import
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                // Abre links externos no browser do sistema
                return if (url.startsWith("https://yurisilva90.github.io")) {
                    false
                } else {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    true
                }
            }
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.proceed()
            }
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                injectNativeFlag()
                checkPermissionsAndShowBanner()
            }
        }
    }

    private fun checkPermissionsAndLoad() {
        webView.loadUrl(SMARTMOBI_URL)
    }

    // Injeta flag no JS para o app saber que está rodando nativo
    private fun injectNativeFlag() {
        webView.evaluateJavascript(
            "window._smartmobiNative = true; " +
            "if(typeof onNativeReady === 'function') onNativeReady();",
            null
        )
    }

    // Mostra banner de permissões se necessário
    private fun checkPermissionsAndShowBanner() {
        val hasOverlay = Settings.canDrawOverlays(this)
        val hasA11y    = isA11yEnabled()
        if (!hasOverlay || !hasA11y) {
            val msg = when {
                !hasOverlay && !hasA11y -> "Configure as permissões do SmartMobi Overlay"
                !hasOverlay -> "Ative a sobreposição para o overlay funcionar"
                else        -> "Ative o serviço de acessibilidade para capturar corridas"
            }
            webView.evaluateJavascript(
                "if(typeof showNativeBanner==='function') showNativeBanner('$msg');",
                null
            )
        }
    }

    // Injeta uma corrida capturada pelo overlay no localStorage do SmartMobi
    fun injectTrip(tripJson: String) {
        runOnUiThread {
            webView.evaluateJavascript(
                "if(typeof receiveOverlayTrip==='function') receiveOverlayTrip($tripJson);",
                null
            )
        }
    }

    private fun isA11yEnabled(): Boolean {
        val am = getSystemService(ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
        return am.getEnabledAccessibilityServiceList(
            android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_GENERIC
        ).any { it.resolveInfo.serviceInfo.packageName == packageName }
    }

    // Botão voltar navega no WebView
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onResume() {
        super.onResume()
        instance = this
        webView.onResume()
    }

    override fun onPause() {
        webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        instance = null
        webView.destroy()
        super.onDestroy()
    }

    // Abre telas de permissão (chamado pelo JS via bridge)
    fun openOverlaySettings() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")))
    }

    fun openA11ySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }
}
