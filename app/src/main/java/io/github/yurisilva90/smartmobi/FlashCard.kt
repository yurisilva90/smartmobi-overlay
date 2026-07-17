package io.github.yurisilva90.smartmobi

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread

// ══════════════════════════════════════════════════════════════════
// MōB Flash — card flutuante de decisão rápida sobre a oferta.
// Aparece por cima do app da 99/Uber. SÓ EXIBE — nunca toca em nada
// (exceto o próprio card, que fecha ao ser tocado).
//
// Até 4 métricas, sempre numa linha só. Largura do card se ajusta à
// quantidade de métricas ativas (2 selecionadas = card mais estreito).
// ══════════════════════════════════════════════════════════════════
class FlashCard(private val context: Context) {

    data class Metric(val label: String, val value: String, val grade: String) // grade: "g"/"a"/"r"

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var container: FrameLayout? = null
    private val hideRunnable = Runnable { hide() }

    // ── Voz por cor: "Aceitar/Analisar/Recusar corrida" ──
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingPhrase: String? = null
    private var lastSpokenGrade: String? = null
    private var lastSpokenReason: String? = null
    private val audioManager by lazy { context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }

    // PEDIDO (17/07/2026): "não tá tocando áudio em nenhuma" — até agora
    // toda falha de TTS (engine não instalado, idioma faltando, exceção no
    // speak) era 100% silenciosa: sem log, sem toast, nada. Sem log não dá
    // pra saber se é o engine falhando de vez, volume/foco de áudio
    // brigando com Waze/navegação por cima, ou outra coisa. Log manda pro
    // mesmo trip_reader_log de sempre, então já dá pra consultar direto.
    private fun ttsLog(tag: String) {
        thread(isDaemon = true) {
            try {
                val prefs = context.getSharedPreferences(GpsService.PREFS_NAME, Context.MODE_PRIVATE)
                val userId = prefs.getString(GpsService.KEY_USER_ID, null)
                val deviceId = try {
                    Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                } catch (_: Exception) { "unknown" }
                val body = JSONObject().apply {
                    put("device_id", deviceId)
                    if (userId != null) put("user_id", userId)
                    put("platform", "TTS")
                    put("package", "flashcard")
                    put("screen_class", tag)
                    put("texts", JSONObject().apply {
                        put("state", tag); put("money", JSONArray()); put("km", JSONObject.NULL)
                        put("min", JSONObject.NULL); put("raw", JSONArray())
                    })
                }
                val conn = URL("${TripReaderService.SUPABASE_URL}/rest/v1/trip_reader_log").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 6000; conn.readTimeout = 6000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("apikey", TripReaderService.SUPABASE_ANON)
                conn.setRequestProperty("Authorization", "Bearer ${TripReaderService.SUPABASE_ANON}")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                conn.responseCode
                conn.disconnect()
            } catch (_: Exception) {}
        }
    }

    private fun ensureTts() {
        if (tts != null) return
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val langResult = tts?.setLanguage(Locale("pt", "BR"))
                ttsReady = true
                ttsLog("TTS_INIT_OK lang=$langResult")
                // A 1ª oferta da sessão às vezes chegava antes do motor de
                // TTS terminar de iniciar, e a fala era simplesmente
                // descartada. Agora guarda e fala assim que fica pronto.
                pendingPhrase?.let { p ->
                    speakNow(p)
                    pendingPhrase = null
                }
            } else {
                // Antes: nada acontecia aqui — motorista nunca saberia que o
                // motor de voz nem chegou a iniciar (sem áudio nenhum, sem
                // pista de por quê).
                ttsLog("TTS_INIT_FAIL status=$status")
            }
        }
    }

    // Pede foco de áudio transiente antes de falar — sem isso, em alguns
    // aparelhos a fala do TTS pode ficar inaudível/cortada quando o app de
    // navegação (Waze, o próprio Uber/99) já está usando o canal de áudio
    // pra dar instrução de rota no mesmo instante.
    private var focusRequest: AudioFocusRequest? = null
    private fun requestAudioFocus() {
        val am = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(attrs).build()
                focusRequest = req
                am.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            }
        } catch (_: Exception) {}
    }

    private fun speakNow(phrase: String) {
        requestAudioFocus()
        try {
            val r = tts?.speak(phrase, TextToSpeech.QUEUE_FLUSH, null, "mob_flash_grade")
            // ERROR (-1) aqui significa que o motor RECUSOU a fala na hora —
            // engine ocupado, texto vazio, etc. Antes isso não gerava log
            // nenhum; agora fica registrado com o texto que falhou.
            if (r == TextToSpeech.ERROR) ttsLog("TTS_SPEAK_ERROR phrase=$phrase")
        } catch (e: Exception) {
            ttsLog("TTS_SPEAK_EXCEPTION ${e.message}")
        }
    }

    private fun speakGrade(grade: String, reason: String? = null) {
        val phrase = when (grade) {
            "g" -> "Aceitar corrida"
            "a" -> "Analisar corrida"
            // PEDIDO (16/07/2026): quando a recusa é por regra (parada,
            // passageiro novo) e não só pelos KPIs financeiros, o áudio diz
            // o motivo — "Tem parada" / "Passageiro novo" — junto da recusa,
            // pra não parecer que os KPIs deram ruim quando na verdade
            // podiam estar bons.
            else -> if (reason != null) "Recusar corrida, $reason" else "Recusar corrida"
        }
        ensureTts()
        if (!ttsReady) { pendingPhrase = phrase; return }
        speakNow(phrase)
    }
    fun shutdownTts() {
        try {
            focusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
            tts?.stop(); tts?.shutdown()
        } catch (_: Exception) {}
        tts = null; ttsReady = false; pendingPhrase = null
    }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()
    private fun dpf(v: Int) = (v * context.resources.displayMetrics.density)

    private fun colorOf(grade: String) = when (grade) {
        "g" -> Color.parseColor("#10B981")
        "a" -> Color.parseColor("#F59E0B")
        else -> Color.parseColor("#EF4444")
    }

    // Base fixa (bordas + padding) + um "slot" de largura por métrica ativa.
    // É assim que o card fica mais estreito com 2 KPIs e mais largo com 4.
    private val baseWidthPx = dp(46)
    private val tileWidthPx = dp(74)
    private fun widthFor(n: Int) = baseWidthPx + tileWidthPx * n.coerceIn(1, 4)

    private val params = WindowManager.LayoutParams(
        widthFor(4),
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        // SEM FLAG_NOT_TOUCHABLE: o card responde a toque (fecha ao tocar,
        // igual o Gigu). FLAG_NOT_FOCUSABLE continua — não pode roubar foco
        // de teclado do app por baixo.
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = dp(58) }

    // platform: "99" ou "UBER". overallGrade: pior nota entre as métricas ativas.
    // metrics: até 4, sempre numa linha só. autoHideMs é só rede de segurança —
    // o normal é o TripReaderService chamar hide() sozinho quando a oferta some (~1s).
    fun show(platform: String, overallGrade: String, metrics: List<Metric>, totalMin: Int, totalKm: Double, declineReason: String? = null, declineReasonShort: String? = null, autoHideMs: Long = 20000L) {
        // Antes exigia pelo menos 1 métrica pra mostrar o card. Com o grupo
        // "Recusas" (17/07/2026), uma oferta pode ser recusada mesmo com
        // todos os Indicadores desligados — nesse caso o card mostra só a
        // barra vermelha + o motivo, sem nenhum número em cima.
        if (metrics.isEmpty() && declineReason == null) return
        handler.removeCallbacks(hideRunnable)
        handler.post {
            val wasHidden = container == null
            container?.let { try { wm.removeView(it) } catch (_: Exception) {} }
            val cardWidthPx = widthFor(metrics.size)
            params.width = cardWidthPx
            container = buildCard(platform, overallGrade, metrics, totalMin, totalKm, cardWidthPx, declineReasonShort ?: declineReason)
            try { wm.addView(container, params) } catch (e: Exception) { e.printStackTrace() }
            handler.postDelayed(hideRunnable, autoHideMs)
            // BUG CONFIRMADO (17/07/2026): "só fala quando tava escondido"
            // parecia certo (evita repetir a fala toda vez que o OCR só
            // refina km/min da MESMA oferta) — mas também calava o áudio
            // quando uma oferta DIFERENTE substituía a anterior na tela sem
            // passar por hide() no meio (ex: card ainda aberto da oferta A
            // quando a oferta B chega). Resultado: card trocava de número,
            // motorista não ouvia nada. Agora fala quando o card tava
            // escondido OU quando o veredito (cor + motivo) mudou — refino
            // da mesma oferta continua mudo, oferta nova sempre fala.
            val gradeChanged = overallGrade != lastSpokenGrade || declineReason != lastSpokenReason
            if (wasHidden || gradeChanged) {
                lastSpokenGrade = overallGrade
                lastSpokenReason = declineReason
                speakGrade(overallGrade, declineReason)
            }
        }
    }

    fun hide() {
        handler.removeCallbacks(hideRunnable)
        handler.post {
            container?.let { try { wm.removeView(it) } catch (_: Exception) {} }
            container = null
            lastSpokenGrade = null
            lastSpokenReason = null
        }
    }

    // "0h04m" se >=60min, senão "4m"
    private fun fmtMin(min: Int): String {
        if (min >= 60) {
            val h = min / 60; val m = min % 60
            return "${h}h${m.toString().padStart(2, '0')}m"
        }
        return "${min}m"
    }

    private fun buildCard(platform: String, overallGrade: String, metrics: List<Metric>, totalMin: Int, totalKm: Double, cardWidthPx: Int, declineReason: String? = null): FrameLayout {
        val gradeColor = colorOf(overallGrade)

        val root = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(cardWidthPx, FrameLayout.LayoutParams.WRAP_CONTENT)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpf(16)
                setColor(Color.parseColor("#E60E0E17"))
            }
            elevation = dpf(10)
            isClickable = true
            // Toque em qualquer parte do card fecha ele — igual o Gigu.
            setOnClickListener { hide() }
        }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            // Largura EXPLÍCITA (não MATCH_PARENT) — root já tem largura fixa
            // definida acima, então isso é só espelhar o mesmo valor. Uma
            // janela WRAP_CONTENT com filho MATCH_PARENT colapsa o conteúdo.
            layoutParams = FrameLayout.LayoutParams(cardWidthPx, FrameLayout.LayoutParams.WRAP_CONTENT)
        }

        // Borda lateral mais grossa (8dp) pra ficar bem visível de relance.
        row.addView(sideBar(dp(8), FrameLayout.LayoutParams.MATCH_PARENT, gradeColor, roundLeft = true))

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(dp(11), dp(10), dp(11), dp(9))
        }

        // Métricas numa linha só — números grandes (tamanho Gigu). Quando não
        // tem nenhum Indicador ligado (só recusa), pula essa linha inteira.
        if (metrics.isNotEmpty()) {
            val metricsRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            metrics.forEachIndexed { idx, m ->
                val tile = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val n = TextView(context).apply {
                    text = m.value; textSize = 31f
                    setTextColor(colorOf(m.grade)); setTypeface(Typeface.DEFAULT_BOLD)
                    gravity = Gravity.CENTER
                    maxLines = 1
                    // BUG CONFIRMADO (14/07/2026): valor calculado certo no banco
                    // ("69,23"), mas a caixa é largura fixa (dividida entre as
                    // métricas) e a fonte era tamanho fixo (31sp) sem proteção —
                    // quando o número era largo (R$/HORA costuma ter mais dígitos
                    // que R$/KM) e não cabia, o Android cortava sem avisar,
                    // geralmente a última casa decimal. Autosize encolhe a fonte
                    // até caber, sem cortar nada — 31sp continua sendo o tamanho
                    // normal quando já cabe.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        setAutoSizeTextTypeUniformWithConfiguration(18, 31, 1, TypedValue.COMPLEX_UNIT_SP)
                    }
                }
                val l = TextView(context).apply {
                    text = m.label; textSize = 6.8f
                    setTextColor(Color.parseColor("#8A8A99")); setTypeface(Typeface.DEFAULT_BOLD)
                    gravity = Gravity.CENTER; setPadding(0, dp(3), 0, 0)
                    maxLines = 1
                }
                tile.addView(n); tile.addView(l)
                metricsRow.addView(tile)
                // Linha vertical fina entre os KPIs (mockup "Variação A", aprovado
                // por Yuri em 16/07/2026) — sem ela os números ficavam colados e
                // difíceis de separar de relance com 3-4 KPIs na tela.
                if (idx < metrics.size - 1) metricsRow.addView(vDivider())
            }
            content.addView(metricsRow)

            val div = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                    topMargin = dp(9); bottomMargin = dp(7)
                }
                setBackgroundColor(Color.parseColor("#1FFFFFFF"))
            }
            content.addView(div)
        }

        // Linha de baixo: logo + tempo + km juntos. Texto branco, 50% maior
        // que o padrão anterior, sem a palavra "Total".
        val bottomRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val isUber = platform == "UBER"
        val logoBadge = TextView(context).apply {
            text = if (isUber) "UBER" else "99"
            textSize = if (isUber) 9.5f else 10f
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(if (isUber) Color.WHITE else Color.parseColor("#111111"))
            setPadding(dp(7), dp(3), dp(7), dp(3))
            background = GradientDrawable().apply {
                cornerRadius = dpf(5)
                setColor(if (isUber) Color.BLACK else Color.parseColor("#FFC800"))
                if (isUber) { setStroke(dp(1), Color.parseColor("#333333")) }
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
        }
        bottomRow.addView(logoBadge)

        val tTime = TextView(context).apply {
            text = fmtMin(totalMin); textSize = 15.75f
            setTextColor(Color.WHITE); setTypeface(Typeface.DEFAULT_BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        bottomRow.addView(tTime)

        // Motivo da recusa (parada / passageiro novo / nota baixa / buscar
        // longe / endereço bloqueado) — mockup "espaço central" aprovado por
        // Yuri (17/07/2026). Fica vazio quando não é recusa por regra (card
        // normal, igual sempre foi). Autosize porque "Parada · Novo ·
        // Endereço" às vezes não cabe no espaço entre o tempo e o km.
        if (!declineReason.isNullOrBlank()) {
            val tReason = TextView(context).apply {
                text = declineReason; textSize = 15.75f
                setTextColor(Color.parseColor("#EF4444")); setTypeface(Typeface.DEFAULT_BOLD)
                gravity = Gravity.CENTER
                maxLines = 1
                setPadding(dp(4), 0, dp(4), 0)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f)
                // PEDIDO (17/07/2026): mesmo tamanho de "14m"/"4,4 km" — antes
                // era 9.5sp e ficava pequeno demais pra ler de relance
                // dirigindo (confirmado em foto real). Autosize só entra pra
                // valer quando mais de 1 recusa bate ao mesmo tempo e o texto
                // combinado ("PARADA · NOVO") não cabe no tamanho cheio —
                // reduz até caber, nunca corta, igual já acontece nos números
                // dos KPIs.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    setAutoSizeTextTypeUniformWithConfiguration(8, 16, 1, TypedValue.COMPLEX_UNIT_SP)
                }
            }
            bottomRow.addView(tReason)
        }

        val tKm = TextView(context).apply {
            text = "%.1f km".format(totalKm); textSize = 15.75f
            setTextColor(Color.WHITE); setTypeface(Typeface.DEFAULT_BOLD)
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        bottomRow.addView(tKm)
        content.addView(bottomRow)

        row.addView(content)
        row.addView(sideBar(dp(8), FrameLayout.LayoutParams.MATCH_PARENT, gradeColor, roundRight = true))

        root.addView(row)
        return root
    }

    private fun sideBar(w: Int, h: Int, color: Int, roundLeft: Boolean = false, roundRight: Boolean = false): android.view.View {
        return android.view.View(context).apply {
            layoutParams = LinearLayout.LayoutParams(w, h)
            background = GradientDrawable().apply {
                setColor(color)
                val r = dpf(16)
                cornerRadii = if (roundLeft)
                    floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
                else if (roundRight)
                    floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
                else floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
            }
        }
    }

    // Linha vertical fina entre KPIs — MATCH_PARENT de altura (estica pra
    // acompanhar a linha inteira de métricas, já que metricsRow é
    // WRAP_CONTENT e a altura real vem do tile mais alto), com uma margem
    // pequena em cima/embaixo pra não encostar nas bordas.
    private fun vDivider(): android.view.View {
        return android.view.View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(1), LinearLayout.LayoutParams.MATCH_PARENT).apply {
                topMargin = dp(3); bottomMargin = dp(3)
            }
            setBackgroundColor(Color.parseColor("#26FFFFFF"))
        }
    }
}
