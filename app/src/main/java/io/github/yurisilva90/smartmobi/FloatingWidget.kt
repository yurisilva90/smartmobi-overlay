package io.github.yurisilva90.smartmobi

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

class FloatingWidget(private val context: Context) {

    companion object {
        const val KEY_WIDGET_CONFIG_JSON = "widget_config_json"
    }

    // ── Aparência configurável (13/09/2026, pedido do Yuri): tamanho da
    // bolinha e quais informações ela mostra. Configurado em "Mais > Painel
    // (Bolinha)" (index.html) e sincronizado via KEY_WIDGET_CONFIG_JSON.
    // Lido de novo a cada show() — uma mudança de config vale a partir da
    // próxima vez que a bolinha aparecer.
    private var sizeScale: Int = 100
    private var showStatus: Boolean = true
    private var showTime: Boolean = true
    private var showKm: Boolean = true
    // PEDIDO (13/09/2026): 3 informações financeiras novas — Ganho da
    // Jornada (destaque em branco) e Ganho por Hora/Km (cor de meta, mesmo
    // limiar vermelho/verde configurado nos Indicadores do Card de Oferta).
    private var showGanhoTotal: Boolean = false
    private var showGanhoHora: Boolean = false
    private var showRpKm: Boolean = false
    // Ordem dos campos abaixo do Status — o motorista arrasta pra reordenar
    // na tela de config; Status em si nunca entra aqui, fica sempre fixo
    // no topo quando ativo.
    private var fieldOrder: List<String> = listOf("tempo", "ganhoTotal", "ganhoHora", "rpkm", "km")
    // Cor de cada status, escolhida pelo motorista num seletor de cor
    // (antes era fixo: Online amarelo, Buscar laranja, Corrida verde).
    private var statusColors: Map<String, Int> = mapOf(
        "online" to Color.parseColor("#FACC15"),
        "buscar" to Color.parseColor("#F97316"),
        "corrida" to Color.parseColor("#22C55E")
    )
    private fun scaleF() = sizeScale.coerceIn(80, 130) / 100f
    private fun gradeColor(g: String) = when (g) {
        "g" -> Color.parseColor("#4ADE80")
        "a" -> Color.parseColor("#FBBF24")
        else -> Color.parseColor("#F87171")
    }

    // Valores ao vivo de ganho — empurrados pelo JS (que tem acesso às
    // corridas realizadas) via updateEarnings(), chamado no mesmo ritmo que
    // updateKm() já é (a cada fix de GPS). Grade ('g'/'a'/'r') já vem
    // calculada do lado JS, reaproveitando rateColorClass (mesmo limiar dos
    // Indicadores do Card de Oferta) — o nativo só pinta com a cor certa.
    private var ganhoTotalVal = 0.0
    private var ganhoHoraVal = 0.0
    private var ganhoHoraGrade = "g"
    private var rpKmVal = 0.0
    private var rpKmGrade = "g"
    private var tvGanhoTotal: TextView? = null
    private var tvGanhoHora: TextView? = null
    private var tvRpKm: TextView? = null

    fun updateEarnings(ganhoTotal: Double, ganhoHora: Double, ganhoHoraGrade: String, rpKm: Double, rpKmGrade: String) {
        this.ganhoTotalVal = ganhoTotal
        this.ganhoHoraVal = ganhoHora
        this.ganhoHoraGrade = ganhoHoraGrade
        this.rpKmVal = rpKm
        this.rpKmGrade = rpKmGrade
        updateDisplay()
    }

    private fun loadConfig() {
        try {
            val prefs = context.getSharedPreferences(GpsService.PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_WIDGET_CONFIG_JSON, null) ?: return
            val cfg = org.json.JSONObject(raw)
            sizeScale = cfg.optInt("sizeScale", 100)
            showStatus = cfg.optBoolean("showStatus", true)
            showTime = cfg.optBoolean("showTime", true)
            showKm = cfg.optBoolean("showKm", true)
            showGanhoTotal = cfg.optBoolean("showGanhoTotal", false)
            showGanhoHora = cfg.optBoolean("showGanhoHora", false)
            showRpKm = cfg.optBoolean("showRpKm", false)

            val orderArr = cfg.optJSONArray("order")
            if (orderArr != null) {
                val known = setOf("tempo", "ganhoTotal", "ganhoHora", "rpkm", "km")
                val parsed = LinkedHashSet<String>()
                for (i in 0 until orderArr.length()) {
                    val k = orderArr.optString(i)
                    if (k in known) parsed.add(k)
                }
                // Qualquer campo conhecido que não veio na lista (config antiga/
                // incompleta) entra no final — nunca some silenciosamente.
                known.forEach { if (it !in parsed) parsed.add(it) }
                fieldOrder = parsed.toList()
            }

            val colorsObj = cfg.optJSONObject("statusColors")
            if (colorsObj != null) {
                val parsedColors = HashMap<String, Int>()
                for (key in listOf("online", "buscar", "corrida")) {
                    val hex = colorsObj.optString(key, "")
                    parsedColors[key] = try {
                        if (hex.isNotEmpty()) Color.parseColor(hex) else statusColors[key]!!
                    } catch (_: Exception) { statusColors[key]!! }
                }
                statusColors = parsedColors
            }

            // Teto de 4 informações ativas (contando Status) — reforçado aqui
            // por segurança, mesmo a UI já impedindo isso na origem.
            var active = if (showStatus) 1 else 0
            for (key in fieldOrder) {
                val isOn = when (key) {
                    "tempo" -> showTime; "ganhoTotal" -> showGanhoTotal
                    "ganhoHora" -> showGanhoHora; "rpkm" -> showRpKm; "km" -> showKm
                    else -> false
                }
                if (!isOn) continue
                if (active >= 4) {
                    when (key) {
                        "tempo" -> showTime = false
                        "ganhoTotal" -> showGanhoTotal = false
                        "ganhoHora" -> showGanhoHora = false
                        "rpkm" -> showRpKm = false
                        "km" -> showKm = false
                    }
                } else active++
            }
        } catch (_: Exception) {}
    }

    // CORRIGIDO (24/07/2026, confirmado pelo Yuri): abrir o app MōB (a PWA)
    // chama updateFloatingStatus("running") pelo bridge JS, que cai em
    // updateStatus() aqui embaixo — e esse método escreve nos MESMOS
    // elementos visuais (status_tv/status_dot) que updateTripState()
    // usa pra mostrar Buscar/Corrida. Resultado: só de abrir o app com uma
    // corrida rolando, o rótulo voltava pra "Online" genérico por cima do
    // Buscar/Corrida real. Guarda o último sub-status de corrida conhecido
    // pra updateStatus() não pisar em cima dele.
    private var lastTripSubStatus: String? = null

    private val wm      = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var container: LinearLayout? = null
    private var tvTime: TextView? = null
    private var tvKm:   TextView? = null
    private var km = 0.0

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    ).apply {
        // PEDIDO (14/09/2026, Yuri): iniciar do lado esquerdo, na mesma
        // posição do Card de Oferta (FlashCard: Gravity.TOP|START, x=12dp,
        // y=58dp) — os dois nunca aparecem ao mesmo tempo (bolinha é a
        // jornada, card é a oferta chegando), então ocupar o mesmo canto
        // aproveita melhor o espaço da tela em vez de espalhar em dois
        // cantos diferentes.
        val density = context.resources.displayMetrics.density
        gravity = Gravity.TOP or Gravity.START
        x = (12 * density).toInt()
        y = (58 * density).toInt()
    }

    // Tick a cada segundo — para automaticamente quando pausado
    private val tickRunnable = object : Runnable {
        override fun run() {
            updateDisplay()
            // Se pausado, re-agendar com intervalo maior (economiza bateria)
            val delay = if (GpsService.isPaused) 5000L else 1000L
            handler.postDelayed(this, delay)
        }
    }

    fun show(startTimestamp: Long, currentKm: Double) {
        // Atualiza o km; NÃO reseta estado de pausa (lido direto do GpsService)
        if (startTimestamp > 0) GpsService.startTimeMs = startTimestamp
        km = currentKm
        if (container != null) { updateDisplay(); return }
        loadConfig()
        handler.post {
            container = buildWidget()
            try { wm.addView(container, params) } catch (e: Exception) { e.printStackTrace() }
            handler.post(tickRunnable)
        }
    }

    fun updateKm(newKm: Double) { km = newKm; updateDisplay() }

    // Sub-status da corrida atual (Online/Buscar/Corrida), detectado pelo
    // TripReaderService via leitura de tela (Accessibility). Só tem efeito
    // quando a jornada está de fato "running" (não pausada/parada) — nesses
    // casos o card continua mostrando Pausado/Offline normalmente.
    // Cores: Online=verde (igual sempre foi), Buscar=laranja, Corrida=azul.
    // Cor do status — antes fixa (Online amarelo/Buscar laranja/Corrida
    // verde), agora escolhida pelo motorista num seletor de cor por status.
    private fun colorFor(key: String): Int = statusColors[key] ?: statusColors["online"]!!

    fun updateTripState(subStatus: String) {
        lastTripSubStatus = subStatus
        handler.post {
            if (!GpsService.isRunning || GpsService.isPaused) return@post
            val (label, key) = when (subStatus) {
                "buscar"  -> "Buscar" to "buscar"
                "corrida" -> "Corrida" to "corrida"
                else      -> "Online" to "online"
            }
            val c = colorFor(key)
            container?.findViewWithTag<TextView>("status_tv")?.apply { text = label; setTextColor(c) }
            container?.findViewWithTag<FrameLayout>("status_dot")?.apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c) }
            }
            container?.background = borderDrawable(c)
        }
    }

    // Fundo + borda arredondada do card, na cor do status atual — usado no
    // build inicial e nas duas trocas de veredito (updateTripState/updateStatus).
    // Escala com sizeScale pra acompanhar o tamanho do resto da bolinha.
    private fun borderDrawable(c: Int): GradientDrawable {
        val density = context.resources.displayMetrics.density * scaleF()
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 20 * density
            setColor(Color.parseColor("#0F172A"))
            setStroke((2 * density).toInt(), c)
        }
    }

    fun updateStatus(status: String) {
        // Apenas atualiza cores/labels — o estado de pausa real está no GpsService
        // Se já tem Buscar/Corrida mostrado e o status que chegou é "running"
        // (jornada rodando, sinal genérico — é o que dispara ao abrir o app),
        // não pisa em cima do rótulo mais específico. Pausado/Offline sempre
        // sobrescrevem, porque aí o conceito de Buscar/Corrida não se aplica.
        if (status == "running" && (lastTripSubStatus == "buscar" || lastTripSubStatus == "corrida")) return
        if (status == "stopped") lastTripSubStatus = null
        handler.post {
            // CORRIGIDO (11/09/2026, pedido do Yuri): "Pausado" (cinza) e
            // "Offline" (vermelho) nunca apareciam na prática — pausar foi
            // removido da UI (gpsPause() é no-op no JS) e encerrar sempre
            // chama hide() antes de qualquer status renderizar (stopFloating
            // no MainActivity). Só sobra "running" (Online); qualquer outro
            // valor cai no mesmo tratamento — menos estado morto pra manter.
            val c = colorFor("online")
            container?.findViewWithTag<TextView>("status_tv")?.apply { text = "Online"; setTextColor(c) }
            container?.findViewWithTag<FrameLayout>("status_dot")?.apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c) }
            }
            container?.background = borderDrawable(c)
            updateDisplay()
        }
    }

    fun hide() {
        handler.removeCallbacks(tickRunnable)
        handler.post {
            container?.let { try { wm.removeView(it) } catch (e: Exception) {} }
            container = null
        }
    }

    private fun fmtBr(v: Double): String = String.format(java.util.Locale.US, "%.2f", v).replace(".", ",")

    private fun updateDisplay() {
        // ── FONTE AUTORITATIVA: lê sempre do GpsService companion ──────────
        // Nunca usa cópias locais — assim a bolinha nunca fica dessincronizada
        // com o estado real da jornada, mesmo após recriações do widget.
        val gStart      = GpsService.startTimeMs
        val gPausedMs   = GpsService.pausedMs
        val gIsPaused   = GpsService.isPaused
        val gPauseStart = GpsService.pauseStartMs

        if (gStart <= 0L) {
            tvTime?.text = "00:00"; tvKm?.text = "0.0 km"
            tvGanhoTotal?.text = "R$ 0,00"
            tvGanhoHora?.text = "R$ 0,00/h"
            tvRpKm?.text = "R$ 0,00/km"
            return
        }

        // Tempo total acumulado em pausa (inclui a pausa atual se ainda ativa)
        val pausedTotal = gPausedMs + (if (gIsPaused) System.currentTimeMillis() - gPauseStart else 0L)

        // Elapsed: se pausado congela no momento em que pausou
        val elapsedMs = if (gIsPaused)
            (gPauseStart - gStart - gPausedMs).coerceAtLeast(0L)
        else
            (System.currentTimeMillis() - gStart - pausedTotal).coerceAtLeast(0L)

        val totalSec = elapsedMs / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60

        tvTime?.text = "%02d:%02d".format(h, m)
        tvKm?.text   = "%.1f km".format(km)
        // Ganho da Jornada/Hora/Km — valores empurrados do JS via
        // updateEarnings() (dado financeiro só existe no lado JS, que tem
        // acesso às corridas realizadas). Ganho por Hora/Km pintam pela
        // mesma cor de meta (verde/amarelo/vermelho) já calculada lá.
        tvGanhoTotal?.text = "R$ ${fmtBr(ganhoTotalVal)}"
        tvGanhoHora?.apply { text = "R$ ${fmtBr(ganhoHoraVal)}/h"; setTextColor(gradeColor(ganhoHoraGrade)) }
        tvRpKm?.apply { text = "R$ ${fmtBr(rpKmVal)}/km"; setTextColor(gradeColor(rpKmGrade)) }
    }

    private fun buildWidget(): LinearLayout {
        val s = scaleF()
        val dp = { v: Int -> (v * context.resources.displayMetrics.density * s).toInt() }
        val tx = { v: Float -> v * s }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = borderDrawable(colorFor("online"))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            elevation = dp(8).toFloat()
        }

        // Status sempre fixo no topo quando ativo — não faz parte de
        // fieldOrder (o motorista não pode reordenar essa linha).
        if (showStatus) {
            val header = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            }
            val statusDot = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(7), dp(7)).apply { rightMargin = dp(5) }
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(colorFor("online")) }
                tag = "status_dot"
            }
            header.addView(statusDot)
            val statusTv = TextView(context).apply {
                text = "Online"; textSize = tx(10f)
                setTextColor(colorFor("online"))
                setTypeface(null, Typeface.BOLD); tag = "status_tv"
            }
            header.addView(statusTv)
            card.addView(header)
        }

        // Os demais campos entram na ordem escolhida pelo motorista
        // (fieldOrder), arrastada na tela de config — cada um só se ativo.
        tvTime = null; tvKm = null; tvGanhoTotal = null; tvGanhoHora = null; tvRpKm = null
        fieldOrder.forEach { key ->
            when (key) {
                "tempo" -> if (showTime) {
                    tvTime = TextView(context).apply {
                        text = "00:00"; textSize = tx(20f)
                        setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD)
                    }
                    card.addView(tvTime)
                }
                "ganhoTotal" -> if (showGanhoTotal) {
                    // Destaque em branco (pedido 13/09/2026) — mesmo peso
                    // visual do Tempo, é o número que mais importa achar rápido.
                    tvGanhoTotal = TextView(context).apply {
                        text = "R$ 0,00"; textSize = tx(15f)
                        setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD)
                    }
                    card.addView(tvGanhoTotal)
                }
                "ganhoHora" -> if (showGanhoHora) {
                    tvGanhoHora = TextView(context).apply {
                        text = "R$ 0,00/h"; textSize = tx(13f)
                        setTextColor(gradeColor(ganhoHoraGrade)); setTypeface(null, Typeface.BOLD)
                    }
                    card.addView(tvGanhoHora)
                }
                "rpkm" -> if (showRpKm) {
                    tvRpKm = TextView(context).apply {
                        text = "R$ 0,00/km"; textSize = tx(13f)
                        setTextColor(gradeColor(rpKmGrade)); setTypeface(null, Typeface.BOLD)
                    }
                    card.addView(tvRpKm)
                }
                "km" -> if (showKm) {
                    tvKm = TextView(context).apply {
                        text = "0.0 km"; textSize = tx(13f)
                        setTextColor(Color.parseColor("#FACC15")); setTypeface(null, Typeface.BOLD)
                    }
                    card.addView(tvKm)
                }
            }
        }

        // Touch: arrastar + tap para abrir app
        var dX = 0f; var dY = 0f; var moved = false
        card.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN  -> { dX = params.x - ev.rawX; dY = params.y - ev.rawY; moved = false; true }
                MotionEvent.ACTION_MOVE  -> {
                    val nx = (ev.rawX + dX).toInt(); val ny = (ev.rawY + dY).toInt()
                    if (Math.abs(nx - params.x) > 5 || Math.abs(ny - params.y) > 5) moved = true
                    params.x = nx; params.y = ny
                    try { wm.updateViewLayout(card, params) } catch (e: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP    -> {
                    if (!moved) {
                        context.startActivity(Intent(context, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                            putExtra("open_screen", "jornada")
                        })
                    }
                    true
                }
                else -> false
            }
        }
        return card
    }
}
