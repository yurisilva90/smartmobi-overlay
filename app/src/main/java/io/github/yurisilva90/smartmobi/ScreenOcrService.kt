package io.github.yurisilva90.smartmobi

import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

// ══════════════════════════════════════════════════════════════════
// ScreenOcrService — fachada de captura de tela + OCR local (ML Kit).
//
// Android 11+ (API 30+): AccessibilityService.takeScreenshot(), sem
// autorização separada de captura.
// Android 7-10 (API 24-29): fallback para LegacyScreenOcrService usando
// MediaProjection, pois takeScreenshot() ainda não existe nesses aparelhos.
// ══════════════════════════════════════════════════════════════════
object ScreenOcrService {

    val isActive: Boolean
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            TripReaderService.instance != null
        } else {
            LegacyScreenOcrService.isActive
        }

    private val main = Handler(Looper.getMainLooper())
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    @Volatile private var busy = false
    @Volatile private var busySinceMs = 0L

    fun captureAndRecognize(onResult: (List<String>, Bitmap?) -> Unit, onError: ((String) -> Unit)? = null) {
        // Em Android 10 ou inferior, delega integralmente ao fluxo antigo de
        // MediaProjection. O restante do app continua chamando a mesma API.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            val legacy = LegacyScreenOcrService.instance ?: run {
                onError?.invoke("captura de tela não autorizada")
                return
            }
            legacy.captureAndRecognize(onResult, onError)
            return
        }

        if (busy) {
            if (System.currentTimeMillis() - busySinceMs > 1500) {
                busy = false
            } else {
                onError?.invoke("ocupado")
                return
            }
        }

        val svc = TripReaderService.instance ?: run {
            onError?.invoke("accessibility service não conectado")
            return
        }
        busy = true
        busySinceMs = System.currentTimeMillis()
        try {
            svc.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                svc.mainExecutor,
                object : android.accessibilityservice.AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: android.accessibilityservice.AccessibilityService.ScreenshotResult) {
                        var bmp: Bitmap? = null
                        try {
                            val hb = result.hardwareBuffer
                            val wrapped = Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
                            bmp = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                            wrapped?.recycle()
                            hb.close()
                        } catch (e: Exception) {
                            busy = false
                            onError?.invoke("wrap bitmap: ${e.message}")
                            return
                        }
                        if (bmp == null) {
                            busy = false
                            onError?.invoke("bitmap nulo")
                            return
                        }
                        runOcr(bmp, onResult, onError)
                    }

                    override fun onFailure(errorCode: Int) {
                        busy = false
                        onError?.invoke("takeScreenshot erro=$errorCode")
                    }
                }
            )
        } catch (e: Exception) {
            busy = false
            onError?.invoke("takeScreenshot: ${e.message}")
        }
    }

    private fun runOcr(
        bmp: Bitmap,
        onResult: (List<String>, Bitmap?) -> Unit,
        onError: ((String) -> Unit)?
    ) {
        val input = InputImage.fromBitmap(bmp, 0)
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
            .addOnFailureListener { e ->
                busy = false
                bmp.recycle()
                onError?.invoke("mlkit: ${e.message}")
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
}
