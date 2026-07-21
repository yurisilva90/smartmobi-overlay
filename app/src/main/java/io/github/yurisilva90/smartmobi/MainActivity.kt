package io.github.yurisilva90.smartmobi

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.webkit.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var webProgress: ProgressBar
    private lateinit var splashView: FrameLayout
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var splashDone = false
    private var webReady   = false
    private var pendingScreen: String? = null

    companion object {
        const val URL      = "https://yurisilva90.github.io/mob/"
        const val REQ_PERM = 100
        const val REQ_FILE = 101
        var floatingWidget: FloatingWidget? = null
        var instance: MainActivity? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        pendingScreen = intent.getStringExtra("open_screen")
        // Vigia do OCR: se o app estava fechado e o motorista tocou na
        // notificação de "leitura de tela parou", o extra chega no onCreate
        // (não no onNewIntent) — pede a captura assim que a tela montar.
        if (intent.getBooleanExtra("re_request_capture", false)) {
            Handler(Looper.getMainLooper()).postDelayed({ launchScreenCaptureRequest() }, 600)
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        supportActionBar?.hide()

        val root = FrameLayout(this)
        webView = WebView(this)
        root.addView(webView, FrameLayout.LayoutParams(-1, -1))
        webProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#0EA5E9"))
        }
        root.addView(webProgress, FrameLayout.LayoutParams(-1, 6))
        splashView = buildSplash()
        root.addView(splashView, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        // Substitui a antiga FLAG_FULLSCREEN (conflitava com adjustResize e escondia
        // campo de texto atrás do teclado). Aqui escondemos a status bar via
        // WindowInsetsController (não bloqueia o resize) e empurramos o conteúdo pra
        // cima manualmente quando o teclado abre, comparando com o inset da nav bar.
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.hide(WindowInsetsCompat.Type.statusBars())
        insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val navBottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            view.setPadding(0, 0, 0, maxOf(imeBottom, navBottom))
            insets
        }

        requestAppPermissions()
        requestOverlayPermission()
        requestBatteryOptimizationExemption()
        setupWebView()
        webView.setBackgroundColor(Color.parseColor("#0F172A"))
        webView.loadUrl(URL)
        Handler(Looper.getMainLooper()).postDelayed({ splashDone = true; maybeHideSplash() }, 400)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val screen = intent.getStringExtra("open_screen")
        if (screen != null) {
            pendingScreen = screen
            maybeOpenPendingScreen()
        }
        // Vigia do OCR (20/07/2026): toque na notificação de "leitura de
        // tela parou" chega aqui com esse extra — reabre direto o pedido
        // de permissão de captura, sem o motorista precisar caçar a tela
        // de configuração do Flash.
        if (intent.getBooleanExtra("re_request_capture", false)) {
            launchScreenCaptureRequest()
        }
    }

    // Mesmo fluxo do requestScreenCapture() do bridge JS — extraído pra ser
    // reutilizado pelo vigia do OCR (notificação) sem duplicar código.
    private fun launchScreenCaptureRequest() {
        try {
            val mpm = getSystemService(android.content.Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
            startActivityForResult(mpm.createScreenCaptureIntent(), 7301)
        } catch (_: Exception) {}
    }

    private fun maybeOpenPendingScreen() {
        val screen = pendingScreen
        if (screen != null && webReady) {
            webView.evaluateJavascript(
                "if(typeof navTo==='function') navTo('$screen');", null)
            pendingScreen = null
        }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
        }
    }

    // Sem isso, fabricantes como Xiaomi/Samsung/Motorola podem matar o app em segundo
    // plano por economia de bateria mesmo com o GpsService em foreground — interrompendo
    // o rastreamento de km no meio da jornada sem o usuário perceber.
    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")))
                } catch (e: Exception) { e.printStackTrace() }
            }
        }
    }

    private fun buildSplash(): FrameLayout {
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val frame = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#0F172A")) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        val icon = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(140), dp(140)).apply { bottomMargin = dp(8) }
            setImageResource(R.mipmap.ic_launcher)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        col.addView(icon)
        col.addView(ProgressBar(this).apply {
            indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#0EA5E9"))
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(28) }
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
            // LOAD_DEFAULT respeita os headers de cache do GitHub Pages (ETag/max-age),
            // permitindo reuso em disco e revalidação rápida (304) em vez de rebaixar o
            // index.html inteiro (~1,5MB) toda vez que o processo é morto em segundo
            // plano e o app reabre — antes era LOAD_NO_CACHE + clearCache(true) no
            // onCreate, que forçava download completo em TODO cold start.
            cacheMode = WebSettings.LOAD_DEFAULT; setSupportZoom(false)
            displayZoomControls = false; builtInZoomControls = false
            useWideViewPort = true; loadWithOverviewMode = true; allowFileAccess = true
            setGeolocationEnabled(true); setGeolocationDatabasePath(filesDir.absolutePath)
        }

        // JS Bridge
        val prefs = getSharedPreferences("smartmobi_session", android.content.Context.MODE_PRIVATE)
        webView.addJavascriptInterface(object {
            @JavascriptInterface fun isNativeApp() = true
            @JavascriptInterface fun getVersion()  = "1.5.0"
            @JavascriptInterface fun hasOverlay()  = Settings.canDrawOverlays(this@MainActivity)
            @JavascriptInterface fun openOverlaySettings() {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK })
                } catch (_: Exception) {}
            }
            @JavascriptInterface fun saveSession(json: String) {
                prefs.edit().putString("session", json).apply()
            }
            @JavascriptInterface fun getSession(): String {
                return prefs.getString("session", "") ?: ""
            }

            @JavascriptInterface fun startFloating(startMs: Long, km: Double) {
                if (!Settings.canDrawOverlays(this@MainActivity)) return
                if (floatingWidget == null) floatingWidget = FloatingWidget(applicationContext)
                floatingWidget?.show(startMs, km)
                // Semeia o serviço com o start/km que o JS conhece — permite
                // reanexar uma jornada em aberto depois do Android matar o serviço
                val i = Intent(this@MainActivity, GpsService::class.java).apply {
                    putExtra("EXTRA_START_MS", startMs)
                    putExtra("EXTRA_KM_BITS", java.lang.Double.doubleToRawLongBits(km))
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
            }
            @JavascriptInterface fun updateFloating(km: Double) {
                floatingWidget?.updateKm(km)
            }
            @JavascriptInterface fun updateFloatingStatus(status: String) {
                floatingWidget?.updateStatus(status)
            }
            // Esconde só a bolinha (preferência do usuário) sem afetar o GPS, que continua
            // rastreando em segundo plano. Diferente de stopFloating, que é usado ao
            // encerrar a jornada de fato e também para o GpsService.
            @JavascriptInterface fun hideFloatingOnly() {
                floatingWidget?.hide()
                floatingWidget = null
            }
            @JavascriptInterface fun stopFloating() {
                floatingWidget?.hide()
                floatingWidget = null
                stopGpsService()
            }
            // GPS nativo — rastreia km em background
            @JavascriptInterface fun getGpsKm(): Double = GpsService.totalKm
            @JavascriptInterface fun getGpsStartTime(): Long = GpsService.startTimeMs
            @JavascriptInterface fun getGpsPausedMs(): Long = GpsService.pausedMs
            @JavascriptInterface fun isGpsRunning(): Boolean = GpsService.isRunning
            // Km exato no instante da última virada de dia (00:00) capturada
            // durante a jornada atual. -1.0 = ainda não cruzou meia-noite
            // (ou o app já consumiu/limpou o snapshot anterior).
            @JavascriptInterface fun getKmAtMidnight(): Double = GpsService.kmAtMidnight
            // Data (yyyy-MM-dd) do dia que começou naquela virada — string
            // vazia se não há snapshot pendente.
            @JavascriptInterface fun getMidnightSnapshotDate(): String = GpsService.midnightSnapshotDate
            // Chamado pelo JS depois que o motorista decide e a divisão foi
            // aplicada — evita reaplicar a mesma divisão se o app reabrir.
            @JavascriptInterface fun clearMidnightSnapshot() {
                GpsService.clearMidnightSnapshot(this@MainActivity)
                val i = Intent(this@MainActivity, GpsService::class.java).apply { action = "CLEAR_MIDNIGHT" }
                startService(i)
            }
            @JavascriptInterface fun saveUserToken(userId: String, accessToken: String) {
                // Armazena credenciais para o GpsService usar nas notificações de reporte rápido
                GpsService.saveUserCredentials(this@MainActivity, userId, accessToken)
            }


            @JavascriptInterface fun startGpsService() {
                val i = Intent(this@MainActivity, GpsService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
            }
            @JavascriptInterface fun pauseGpsService() {
                val i = Intent(this@MainActivity, GpsService::class.java).apply { action = "PAUSE" }
                startService(i)
            }
            @JavascriptInterface fun resumeGpsService() {
                val i = Intent(this@MainActivity, GpsService::class.java).apply { action = "RESUME" }
                startService(i)
            }
            @JavascriptInterface fun stopGpsService() {
                GpsService.clearSavedState(this@MainActivity)
                stopService(Intent(this@MainActivity, GpsService::class.java))
            }

            // Vibração de confirmação (usada em receiveOverlayTrip)
            @JavascriptInterface fun vibrate(ms: Long) {
                try {
                    val v = getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator ?: return
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        v.vibrate(android.os.VibrationEffect.createOneShot(ms, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                    } else {
                        @Suppress("DEPRECATION") v.vibrate(ms)
                    }
                } catch (_: Exception) {}
            }

            // Abre a tela de Acessibilidade do Android pro usuário ativar o MōB Flash
            @JavascriptInterface fun openA11ySettings() {
                try {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    })
                } catch (_: Exception) {}
            }

            // Status do MōB Flash — permite a tela de configuração mostrar o que falta
            @JavascriptInterface fun isA11yEnabled(): Boolean {
                return try {
                    val enabledServices = Settings.Secure.getString(
                        contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                    ) ?: ""
                    enabledServices.contains("${packageName}/${packageName}.TripReaderService")
                } catch (_: Exception) { false }
            }

            // Salva a configuração do MōB Flash (lida pelo TripReaderService via SharedPreferences)
            // configJson vem pronto do JS: {"enabled":..,"custoPorKm":..,"kpis":{...}}
            @JavascriptInterface fun saveFlashConfig(configJson: String) {
                getSharedPreferences(GpsService.PREFS_NAME, android.content.Context.MODE_PRIVATE).edit()
                    .putString(TripReaderService.KEY_FLASH_CONFIG_JSON, configJson)
                    .apply()
            }

            // Captura de tela pro OCR do MōB Flash (a oferta da 99 é imagem)
            @JavascriptInterface fun requestScreenCapture() {
                runOnUiThread { launchScreenCaptureRequest() }
            }
            @JavascriptInterface fun isScreenCaptureActive(): Boolean = ScreenOcrService.isActive
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
                // Valida o HOST exato (não startsWith na URL, que aceitaria
                // yurisilva90.github.io.evil.com como se fosse interno).
                val internal = r.url.scheme == "https" && r.url.host == "yurisilva90.github.io"
                return if (internal) false
                else { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.url.toString()))); true }
            }
            override fun onPageFinished(v: WebView, url: String) {
                webView.evaluateJavascript(
                    "window._smartmobiNative=true;window._nativeVersion='1.4.9';" +
                    "if(typeof onNativeReady==='function')onNativeReady();", null)
                webReady = true; maybeHideSplash()
                // Pequeno atraso pra dar tempo do login assincrono (Supabase) resolver
                // antes de navegar — senão a navegacao pode disparar ainda na tela de login.
                Handler(Looper.getMainLooper()).postDelayed({ maybeOpenPendingScreen() }, 900)
            }
        }
    }

    private fun stopGpsService() {
        GpsService.clearSavedState(this)
        stopService(Intent(this, GpsService::class.java))
    }

    @Deprecated("") override fun onActivityResult(req: Int, result: Int, data: Intent?) {
        super.onActivityResult(req, result, data)
        if (req == REQ_FILE) { fileCallback?.onReceiveValue(if (data?.data != null) arrayOf(data.data!!) else arrayOf()); fileCallback = null }
        if (req == 7301) {
            if (result == RESULT_OK && data != null) {
                ScreenOcrService.pendingResultCode = result
                ScreenOcrService.pendingResultData = data
                val it = Intent(this, ScreenOcrService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(it) else startService(it)
                // O serviço leva um instante pra terminar de configurar a
                // gravação (onStartCommand roda de forma assíncrona). Sem
                // essa espera, a tela checava "ativo?" cedo demais e mostrava
                // "Pendente" mesmo já tendo funcionado — daí parecia que só
                // "pegava" na segunda vez que a pessoa tocava em Ativar.
                Handler(Looper.getMainLooper()).postDelayed({
                    webView.evaluateJavascript("try{renderFlashPerms&&renderFlashPerms()}catch(e){}", null)
                }, 700)
            } else {
                webView.evaluateJavascript("try{renderFlashPerms&&renderFlashPerms()}catch(e){}", null)
            }
        }
    }
    override fun onKeyDown(k: Int, e: KeyEvent): Boolean {
        if (k == KeyEvent.KEYCODE_BACK && webView.canGoBack()) { webView.goBack(); return true }
        return super.onKeyDown(k, e)
    }
    override fun onResume() {
        super.onResume()
        instance = this
        webView.onResume()
        maybeOpenPendingScreen()
        // Sincroniza KM nativo com o web app ao voltar ao foreground
        if (GpsService.isRunning) {
            val km = GpsService.totalKm
            val startMs = GpsService.startTimeMs
            val pausedMs = GpsService.pausedMs
            webView.evaluateJavascript(
                "if(typeof nativeSyncGps==='function') nativeSyncGps($km, $startMs, $pausedMs);", null
            )
        }
    }
    override fun onPause()   { webView.onPause(); super.onPause() }
    override fun onDestroy() { instance = null; webView.destroy(); super.onDestroy() }
}


