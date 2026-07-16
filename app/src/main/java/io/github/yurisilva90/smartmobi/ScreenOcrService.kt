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
    @Volatile private var busySinceMs = 0L

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

    // Captura 1 frame e devolve as LINHAS de texto reconhecidas + o Bitmap
    // do mesmo frame (pra quem chamar decidir se salva o print — só quando
    // vira card, não em todo frame). Quem recebe o bitmap é responsável por
    // reciclar (bmp.recycle()) depois de usar.
    fun captureAndRecognize(onResult: (List<String>, Bitmap?) -> Unit, onError: ((String) -> Unit)? = null) {
        // INVESTIGAÇÃO (13/07/2026): oferta chegando durante corrida ativa
        // nunca foi capturada em ~90s de tentativas repetidas, mesmo com
        // print manual confirmando que a tela realmente mostrava o card.
        // Uma hipótese real: o listener do ML Kit (sucesso OU erro) às vezes
        // não dispara nenhum dos dois — aí "busy" fica true pra sempre e
        // TODA captura futura é ignorada silenciosamente, sem log de erro
        // nenhum (por isso não aparecia nem como OCR_ERRO). Trava de
        // segurança: se "busy" ficar travado por mais de 5s, destrava
        // sozinho e segue a captura, em vez de continuar bloqueado.
        if (busy) {
            if (System.currentTimeMillis() - busySinceMs > 5000) {
                busy = false
            } else {
                return
            }
        }
        val reader = imageReader ?: run { onError?.invoke("sem imageReader"); return }
        busy = true
        busySinceMs = System.currentTimeMillis()
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
                            if (t.isNotEmpty()) lines.add(normalizeOcrText(t))
                        }
                    }
                    busy = false
                    onResult(lines, bmp)
                }
                .addOnFailureListener { e -> busy = false; bmp?.recycle(); onError?.invoke("mlkit: ${e.message}") }
        }
    }

    // ── Correção de erros sistemáticos de OCR ──────────────────────────
    // CONFIRMADO EM LOG REAL (15/07/2026): em várias ofertas da 99, o ML
    // Kit leu "R$" como "RS" (ex: "RS7,90", "RS8,64", "RS12,20") e
    // "serviço" sem cedilha ("Taxa de servico") — o suficiente pra NENHUM
    // padrão de isOfferScreen()/parseOffer() bater, mesmo com a oferta
    // genuína e legível na tela. O motorista não via card nem ouvia som
    // nenhum. Corrige aqui, uma vez, na origem — todo o resto do parser
    // (isOfferScreen, parseOffer, extractMoney) já funciona a partir daqui
    // sem precisar de tolerância espalhada em cada regex.
    private val rsMoneyRe = Regex("""(?i)\bRS(?=\d)""")
    private val servicoRe = Regex("""(?i)servico""")
    private fun normalizeOcrText(s: String): String {
        var out = s
        // Forma lambda do replace() — evita o parse de "$" como referência
        // de grupo de regex (Regex.replace(input, "R$") quebraria em
        // runtime: "$" sozinho não é uma referência de grupo válida).
        out = rsMoneyRe.replace(out) { "R$" }
        out = servicoRe.replace(out) { "serviço" }
        return out
    }

    override fun onDestroy() {
        teardownDisplay()
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        instance = null
        super.onDestroy()
    }
}
