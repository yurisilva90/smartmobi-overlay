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
// ScreenOcrService — captura de tela + OCR local (ML Kit).
//
// Existe porque a tela de oferta da 99 é desenhada como canvas (sem nós
// de acessibilidade) — comprovado pelos logs DIAG_EMPTY. O caminho real
// da informação é a imagem, então: captura de tela → Bitmap → OCR.
//
// REESCRITO (16/08/2026, bloqueador de Play Store item 5): antes usava
// MediaProjection (VirtualDisplay + ImageReader), que no Android 14+ pede
// consentimento do usuário TODA sessão nova e mantém uma notificação
// persistente extra enquanto ativo — péssimo de UX e mal visto em revisão
// da Play Store. Agora usa AccessibilityService.takeScreenshot() (API 30+,
// disponível desde Android 11): captura direto do buffer de hardware, sem
// diálogo de consentimento por sessão e sem notificação própria — o
// TripReaderService já É o AccessibilityService, então só precisa da
// permissão de acessibilidade que o app já pede (mesma de sempre).
//
// TROCA REAL (documentada, não escondida): takeScreenshot() não existe
// antes do Android 11. Em aparelhos Android 7-10 (API 24-29, dentro do
// minSdk 24 do app), o Flash por OCR simplesmente não funciona mais — an-
// tes funcionava neles via MediaProjection (Android 5+). Todo o resto do
// app (GPS, captura via accessibility tree pra Uber/99 quando não é
// canvas, etc.) continua funcionando normal nesses aparelhos; só o OCR de
// tela (usado hoje só pra 99, que renderiza a oferta em canvas) fica
// indisponível. Se isso for um problema real (base de usuários com
// Android antigo), a solução seria voltar o MediaProjection só como
// fallback nesses casos — não implementado agora, avaliar se aparecer
// usuário reclamando.
//
// • Só captura quando o TripReaderService pede (oferta provável).
// • OCR roda 100% no aparelho (sem internet), latência ~200-400ms.
// • Nada de imagem sai do celular — só o TEXTO reconhecido é usado (o
//   Bitmap em si só é usado pra salvar o snapshot do Flash quando vira
//   card, e isso já era assim antes — não mudou com essa reescrita).
// ══════════════════════════════════════════════════════════════════
object ScreenOcrService {

    // "Ativo" agora só depende de duas coisas: o Android suportar
    // takeScreenshot() (API 30+) e o AccessibilityService estar rodando —
    // não existe mais "sessão" separada pra iniciar/parar, então não tem
    // pendingResultCode/pendingResultData nem serviço próprio.
    val isActive: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && TripReaderService.instance != null

    private val main = Handler(Looper.getMainLooper())
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    @Volatile private var busy = false
    @Volatile private var busySinceMs = 0L

    // Captura 1 frame e devolve as LINHAS de texto reconhecidas + o Bitmap
    // do mesmo frame (pra quem chamar decidir se salva o print — só quando
    // vira card, não em todo frame). Quem recebe o bitmap é responsável por
    // reciclar (bmp.recycle()) depois de usar.
    fun captureAndRecognize(onResult: (List<String>, Bitmap?) -> Unit, onError: ((String) -> Unit)? = null) {
        // Mesma trava de segurança contra listener que nunca dispara
        // (confirmado em log real, 23/07/2026) — mantida igual à versão
        // anterior, só que agora protegendo a chamada de takeScreenshot()
        // em vez do processamento do ImageReader.
        if (busy) {
            if (System.currentTimeMillis() - busySinceMs > 1500) {
                busy = false
            } else {
                onError?.invoke("ocupado")
                return
            }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            onError?.invoke("android < 11, takeScreenshot indisponível")
            return
        }
        val svc = TripReaderService.instance ?: run { onError?.invoke("accessibility service não conectado"); return }
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
                            // ML Kit InputImage.fromBitmap não trabalha bem
                            // com Bitmap em config HARDWARE — copia pra
                            // ARGB_8888 (software) antes de processar.
                            bmp = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                            wrapped?.recycle()
                            hb.close()
                        } catch (e: Exception) {
                            busy = false
                            onError?.invoke("wrap bitmap: ${e.message}")
                            return
                        }
                        if (bmp == null) { busy = false; onError?.invoke("bitmap nulo"); return }
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

    private fun runOcr(bmp: Bitmap, onResult: (List<String>, Bitmap?) -> Unit, onError: ((String) -> Unit)?) {
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
            .addOnFailureListener { e -> busy = false; bmp.recycle(); onError?.invoke("mlkit: ${e.message}") }
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
}
