package io.github.yurisilva90.smartmobi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * Fallback de captura para Android 7-10 (API 24-29).
 *
 * A partir do Android 11 o MōB usa AccessibilityService.takeScreenshot(),
 * sem autorização separada. Em Android 10 ou inferior esse método não existe,
 * então mantemos o fluxo antigo de MediaProjection apenas nesses aparelhos.
 */
class LegacyScreenOcrService : Service() {

    companion object {
        @Volatile var instance: LegacyScreenOcrService? = null
        val isActive: Boolean get() = instance?.projection != null

        var pendingResultCode: Int = 0
        var pendingResultData: Intent? = null

        const val CHANNEL_ID = "mob_screen_ocr_legacy"
        const val NOTIF_ID = 4102
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val main = Handler(Looper.getMainLooper())
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    @Volatile private var busy = false
    @Volatile private var busySinceMs = 0L
    // CORRIGIDO (01/09/2026, dado real: em 5 dias de log, 545 OCR_ERRO:
    // ocupado + 134 OCR_ERRO: sem frame disponivel — quase metade das
    // tentativas de leitura de oferta da 99 falhando por concorrência
    // nesse pipeline). Porta pra cá o MESMO mecanismo de fila que já existe
    // e funciona em ScreenOcrService (caminho Android 11+/takeScreenshot):
    // no máximo 1 retry pendente por vez, tenta de novo em 180ms em vez de
    // descartar a leitura na hora.
    @Volatile private var retryQueued = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundWithNotif()
        val data = pendingResultData
        val code = pendingResultCode
        if (projection == null && data != null) {
            try {
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                projection = mpm.getMediaProjection(code, data)
                projection?.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        teardownDisplay()
                        projection = null
                    }
                }, main)
                setupDisplay()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return START_STICKY
    }

    private fun startForegroundWithNotif() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "MōB Flash — leitura de ofertas",
                    NotificationManager.IMPORTANCE_MIN
                )
            )
        }
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notif: Notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }.setContentTitle("MōB Flash ativo")
            .setContentText("Lendo ofertas da 99/Uber")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                notif,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun setupDisplay() {
        val dm = resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection?.createVirtualDisplay(
            "mob-flash-ocr-legacy",
            w,
            h,
            dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null,
            main
        )
    }

    private fun teardownDisplay() {
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        virtualDisplay = null
        imageReader = null
    }

    fun captureAndRecognize(
        onResult: (List<String>, Bitmap?) -> Unit,
        onError: ((String) -> Unit)? = null
    ) {
        if (busy) {
            if (System.currentTimeMillis() - busySinceMs > 1500) {
                busy = false
            } else {
                if (!retryQueued) {
                    retryQueued = true
                    main.postDelayed({
                        retryQueued = false
                        captureAndRecognize(onResult, onError)
                    }, 180L)
                }
                return
            }
        }

        val reader = imageReader ?: run {
            onError?.invoke("sem imageReader")
            return
        }

        busy = true
        busySinceMs = System.currentTimeMillis()
        main.post {
            var bmp: Bitmap? = null
            try {
                val img = reader.acquireLatestImage()
                if (img == null) {
                    busy = false
                    if (!retryQueued) {
                        retryQueued = true
                        main.postDelayed({
                            retryQueued = false
                            captureAndRecognize(onResult, onError)
                        }, 180L)
                    } else {
                        onError?.invoke("sem frame disponivel")
                    }
                    return@post
                }
                val plane = img.planes[0]
                val rowStride = plane.rowStride
                val pixelStride = plane.pixelStride
                val rowPadding = rowStride - pixelStride * img.width
                bmp = Bitmap.createBitmap(
                    img.width + rowPadding / pixelStride,
                    img.height,
                    Bitmap.Config.ARGB_8888
                )
                bmp!!.copyPixelsFromBuffer(plane.buffer)
                img.close()
            } catch (e: Exception) {
                busy = false
                onError?.invoke("captura: ${e.message}")
                return@post
            }

            val bitmap = bmp ?: run {
                busy = false
                onError?.invoke("bitmap nulo")
                return@post
            }
            val input = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(input)
                .addOnSuccessListener { result ->
                    val lines = ArrayList<String>()
                    for (block in result.textBlocks) {
                        for (line in block.lines) {
                            val text = line.text.trim()
                            if (text.isNotEmpty()) lines.add(normalizeOcrText(text))
                        }
                    }
                    busy = false
                    onResult(lines, bitmap)
                }
                .addOnFailureListener { e ->
                    busy = false
                    bitmap.recycle()
                    onError?.invoke("mlkit: ${e.message}")
                }
        }
    }

    private val rsMoneyRe = Regex("""(?i)\bRS(?=\d)""")
    private val servicoRe = Regex("""(?i)servico""")

    private fun normalizeOcrText(s: String): String {
        var out = s
        out = rsMoneyRe.replace(out) { "R$" }
        out = servicoRe.replace(out) { "serviço" }
        return out
    }

    override fun onDestroy() {
        teardownDisplay()
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        if (instance === this) instance = null
        super.onDestroy()
    }
}
