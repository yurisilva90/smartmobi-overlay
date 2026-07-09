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

// ══════════════════════════════════════════════════════════════════
// ScreenOcrService — captura de tela + OCR local (ML Kit).
//
// Existe porque a tela de oferta da 99 é desenhada como canvas (sem nós
// de acessibilidade) — comprovado pelos logs DIAG_EMPTY. O caminho real
// da informação é a imagem, então: MediaProjection → Bitmap → OCR.
//
// • Só captura quando o TripReaderService pede (oferta provável).
// • OCR roda 100% no aparelho (sem internet), latência ~200-400ms.
// • Nada de imagem sai do celular — só o TEXTO reconhecido é usado.
// ══════════════════════════════════════════════════════════════════
class ScreenOcrService : Service() {

    companion object {
        @Volatile var instance: ScreenOcrService? = null
        val isActive: Boolean get() = instance?.projection != null

        var pendingResultCode: Int = 0
        var pendingResultData: Intent? = null

        const val CHANNEL_ID = "mob_screen_ocr"
        const val NOTIF_ID = 4102
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val main = Handler(Looper.getMainLooper())
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    @Volatile private var busy = false

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
                    override fun onStop() { teardownDisplay(); projection = null }
                }, main)
                setupDisplay()
            } catch (e: Exception) { e.printStackTrace() }
        }
        return START_STICKY
    }

    private fun startForegroundWithNotif() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "MōB Flash — leitura de ofertas", NotificationManager.IMPORTANCE_MIN)
            )
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
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
            startForeground(NOTIF_ID, notif, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
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
            "mob-flash-ocr", w, h, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, main
        )
    }

    private fun teardownDisplay() {
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        virtualDisplay = null
        imageReader = null
    }

    // Captura 1 frame e devolve as LINHAS de texto reconhecidas (thread-safe,
    // descarta pedidos enquanto um OCR está em andamento — velocidade > tudo).
    fun captureAndRecognize(onResult: (List<String>) -> Unit, onError: ((String) -> Unit)? = null) {
        if (busy) return
        val reader = imageReader ?: run { onError?.invoke("sem imageReader"); return }
        busy = true
        main.post {
            var bmp: Bitmap? = null
            try {
                val img = reader.acquireLatestImage()
                if (img == null) { busy = false; onError?.invoke("sem frame disponivel"); return@post }
                val plane = img.planes[0]
                val rowStride = plane.rowStride
                val pixelStride = plane.pixelStride
                val rowPadding = rowStride - pixelStride * img.width
                bmp = Bitmap.createBitmap(
                    img.width + rowPadding / pixelStride, img.height, Bitmap.Config.ARGB_8888
                )
                bmp.copyPixelsFromBuffer(plane.buffer)
                img.close()
            } catch (e: Exception) {
                busy = false
                onError?.invoke("captura: ${e.message}")
                return@post
            }
            val input = InputImage.fromBitmap(bmp!!, 0)
            recognizer.process(input)
                .addOnSuccessListener { result ->
                    val lines = ArrayList<String>()
                    for (block in result.textBlocks) {
                        for (line in block.lines) {
                            val t = line.text.trim()
                            if (t.isNotEmpty()) lines.add(t)
                        }
                    }
                    busy = false
                    onResult(lines)
                }
                .addOnFailureListener { e -> busy = false; onError?.invoke("mlkit: ${e.message}") }
        }
    }

    override fun onDestroy() {
        teardownDisplay()
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        instance = null
        super.onDestroy()
    }
}
