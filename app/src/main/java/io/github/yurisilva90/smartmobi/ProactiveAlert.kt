package io.github.yurisilva90.smartmobi

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread
import kotlin.math.*

// ══════════════════════════════════════════════════════════════════
// ProactiveAlert — alertas de Fiscalização/Lotação disparados por
// proximidade de local (raio de 100m), pedido do Yuri (16/08/2026),
// substitui o sistema antigo de notificação em GpsService.kt (ver
// comentário de desativação lá).
//
// Regras aprovadas:
// • Só dispara em corrida ou online (nunca "buscar" — motorista
//   navegando até o passageiro não deve ser interrompido).
// • Raio de 100m (não 1,5km) — serve tanto pra perguntar sobre local
//   pré-setado (aeroporto/terminal/shopping/hotel/turístico/centro de
//   convenções/evento) quanto pra confirmar relato de outro motorista.
// • Prioridade ABSOLUTA do card de oferta — se uma oferta chega
//   enquanto esse card tá na tela, ele cai na hora, sem exceção
//   (ver FlashCard.show(), chama ProactiveAlert.forceHide() primeiro).
// • Nunca mostra card com pergunta simplificada — sempre a pergunta
//   completa (Quem+Tipo pra Fiscalização, 5 opções pra Lotação),
//   igual ao relato manual — só o tempo de espera muda (10s corrida
//   / 5s online).
// • Anti-spam centralizado no banco (can_prompt_venue) — nunca
//   pergunta o mesmo local 2x em 4h, nem mais de 4x por dia.
// ══════════════════════════════════════════════════════════════════
object ProactiveAlert {

    private const val CHECK_INTERVAL_MS = 2 * 60 * 1000L   // 2min, mesmo ritmo do cron de venue
    private const val RADIUS_M = 100.0
    private val handler = Handler(Looper.getMainLooper())
    private val wm by lazy { appCtx?.getSystemService(Context.WINDOW_SERVICE) as? WindowManager }
    private var appCtx: Context? = null
    private var container: FrameLayout? = null
    private val autoHideRunnable = Runnable { forceHide() }
    private var countdownRunnable: Runnable? = null
    @Volatile private var busy = false

    private val checkRunnable = object : Runnable {
        override fun run() {
            val state = TripReaderService.confirmedTripSubState
            if (GpsService.isRunning && !GpsService.isPaused && (state == "online" || state == "corrida") && !busy) {
                thread(isDaemon = true) { runCheck(state) }
            }
            handler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    fun startLoop(ctx: Context) {
        appCtx = ctx.applicationContext
        handler.removeCallbacks(checkRunnable)
        handler.postDelayed(checkRunnable, 30_000L) // primeira checagem só depois de 30s de jornada
    }

    fun stopLoop() {
        handler.removeCallbacks(checkRunnable)
        forceHide()
    }

    // Chamado pelo FlashCard.show() ANTES de mostrar o card de oferta —
    // prioridade absoluta, sem exceção, sem animação.
    fun forceHide() {
        handler.removeCallbacks(autoHideRunnable)
        countdownRunnable?.let { handler.removeCallbacks(it) }
        handler.post {
            container?.let { c -> try { wm?.removeView(c) } catch (_: Exception) {} }
            container = null
        }
    }

    // ── Categoria → label/cor ────────────────────────────────────────
    private val CAT_LABEL = mapOf(
        "aeroporto" to "Aeroporto", "terminal_rodoviario" to "Terminal Rodoviário",
        "shopping" to "Shopping", "hotel" to "Hotel", "turistico" to "Ponto turístico",
        "centro_convencoes" to "Centro de Convenções", "evento" to "Local de evento"
    )
    private val COMBO_CATEGORIES = setOf("aeroporto", "terminal_rodoviario")

    // ── Checagem principal (thread de fundo) ─────────────────────────
    private fun runCheck(state: String) {
        busy = true
        try {
            val prefs = appCtx?.getSharedPreferences(GpsService.PREFS_NAME, Context.MODE_PRIVATE) ?: return
            val userId = prefs.getString(GpsService.KEY_USER_ID, null) ?: return
            val authToken = prefs.getString(GpsService.KEY_ACCESS_TOKEN, null) ?: TripReaderService.SUPABASE_ANON
            val lat = GpsService.lastLat; val lng = GpsService.lastLng
            if (lat == 0.0 && lng == 0.0) return

            // Garante que a região já foi (ou está sendo) mapeada — não
            // bloqueia, só dispara o pedido pro Supabase resolver sozinho
            // em segundo plano (ver ensure_venues_for_region no banco).
            rpcCall(authToken, "ensure_venues_for_region", JSONObject().apply {
                put("p_lat", lat); put("p_lng", lng)
            })

            // CORRIGIDO (16/08/2026, o Yuri percebeu o erro): confirmação
            // de relato de outro motorista tinha ficado amarrada a
            // precisar de um local pré-setado por perto — errado, tem que
            // disparar perto do RELATO, independente de ter
            // aeroporto/shopping ali ou não. Checa relato PRIMEIRO, com
            // anti-spam próprio (can_prompt_report, chaveado por
            // report_id, não venue_id).
            val nearbyReport = findNearbyActiveReport(authToken, lat, lng)
            if (nearbyReport != null) {
                val allowedReport = canPromptReport(authToken, userId, nearbyReport.id)
                if (allowedReport) {
                    handler.post { showConfirmacaoCard(nearbyReport, state, userId, authToken, null) }
                    logPrompt(authToken, userId, null, nearbyReport.id, "confirmacao_fiscalizacao", state)
                    return
                }
                // Já perguntou sobre esse relato recentemente — não
                // bloqueia a checagem de local pré-setado por causa disso,
                // só não confirma de novo. Segue pro resto normal.
            }

            val venue = findNearestVenue(authToken, lat, lng) ?: return
            val allowed = canPromptVenue(authToken, userId, venue.id)
            if (!allowed) return

            handler.post {
                if (venue.category in COMBO_CATEGORIES) {
                    showComboCard(venue, state, userId, authToken)
                } else {
                    showLotacaoCard(venue, state, userId, authToken)
                }
            }
            logPrompt(authToken, userId, venue.id, null, if (venue.category in COMBO_CATEGORIES) "combo" else "lotacao", state)
        } catch (_: Exception) {
        } finally {
            busy = false
        }
    }

    private data class Venue(val id: String, val name: String, val category: String, val lat: Double, val lng: Double)
    private data class NearbyReport(val id: String, val type: String, val paramValue: String?, val paramDetail: String?, val address: String?, val minutesAgo: Int)

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1); val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat/2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon/2).pow(2)
        return R * 2 * atan2(sqrt(a), sqrt(1-a))
    }

    private fun findNearestVenue(authToken: String, lat: Double, lng: Double): Venue? {
        // Bounding box generoso (~200m) — filtra distância exata em Kotlin.
        val d = 0.002
        val url = "${TripReaderService.SUPABASE_URL}/rest/v1/venue_cache?" +
            "lat=gte.${lat-d}&lat=lte.${lat+d}&lng=gte.${lng-d}&lng=lte.${lng+d}&select=id,name,category,lat,lng"
        val arr = getJson(authToken, url) as? JSONArray ?: return null
        var best: Venue? = null; var bestDist = RADIUS_M
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val vlat = o.getDouble("lat"); val vlng = o.getDouble("lng")
            val dist = haversine(lat, lng, vlat, vlng)
            if (dist <= bestDist) { bestDist = dist; best = Venue(o.getString("id"), o.getString("name"), o.getString("category"), vlat, vlng) }
        }
        return best
    }

    // Relato ATIVO (não expirado) de fiscalização/alerta a menos de 100m —
    // se achar, vira card de confirmação em vez de pergunta fresca.
    private fun findNearbyActiveReport(authToken: String, lat: Double, lng: Double): NearbyReport? {
        val d = 0.002
        val nowIso = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date())
        val url = "${TripReaderService.SUPABASE_URL}/rest/v1/feed_posts?" +
            "lat=gte.${lat-d}&lat=lte.${lat+d}&lng=gte.${lng-d}&lng=lte.${lng+d}&" +
            "type=in.(blitz,risco)&status=eq.ativo&expires_at=gt.$nowIso&" +
            "select=id,type,param_value,param_detail,address,lat,lng,created_at&order=created_at.desc&limit=5"
        val arr = getJson(authToken, url) as? JSONArray ?: return null
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val dist = haversine(lat, lng, o.getDouble("lat"), o.getDouble("lng"))
            if (dist <= RADIUS_M) {
                val createdAt = o.optString("created_at")
                val minAgo = try {
                    val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                    val then = fmt.parse(createdAt.take(19))?.time ?: 0L
                    ((System.currentTimeMillis() - then) / 60000).toInt().coerceAtLeast(0)
                } catch (_: Exception) { 0 }
                return NearbyReport(o.getString("id"), o.getString("type"),
                    o.optString("param_value", null), o.optString("param_detail", null),
                    o.optString("address", null), minAgo)
            }
        }
        return null
    }

    private fun canPromptVenue(authToken: String, userId: String, venueId: String): Boolean {
        val res = rpcCall(authToken, "can_prompt_venue", JSONObject().apply {
            put("p_user_id", userId); put("p_venue_id", venueId)
        })
        return res == true
    }

    private fun canPromptReport(authToken: String, userId: String, reportId: String): Boolean {
        val res = rpcCall(authToken, "can_prompt_report", JSONObject().apply {
            put("p_user_id", userId); put("p_report_id", reportId)
        })
        return res == true
    }

    // venueId OU reportId — só um dos dois, nunca os dois (confirmação de
    // relato não depende de local pré-setado, ver correção 16/08/2026).
    private fun logPrompt(authToken: String, userId: String, venueId: String?, reportId: String?, type: String, state: String) {
        thread(isDaemon = true) {
            try {
                val body = JSONObject().apply {
                    put("user_id", userId)
                    put("venue_id", venueId ?: JSONObject.NULL)
                    put("report_id", reportId ?: JSONObject.NULL)
                    put("prompt_type", type); put("driver_state", state)
                }
                postJson(authToken, "${TripReaderService.SUPABASE_URL}/rest/v1/venue_prompt_log", body)
            } catch (_: Exception) {}
        }
    }

    // ── HTTP helpers (mesmo padrão de auth já usado no resto do app) ──
    private fun getJson(authToken: String, url: String): Any? {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 8000; conn.readTimeout = 8000
            conn.setRequestProperty("apikey", TripReaderService.SUPABASE_ANON)
            conn.setRequestProperty("Authorization", "Bearer $authToken")
            val text = conn.inputStream.bufferedReader().readText()
            if (text.trim().startsWith("[")) JSONArray(text) else JSONObject(text)
        } catch (_: Exception) { null }
    }

    private fun postJson(authToken: String, url: String, body: JSONObject): String? {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"; conn.doOutput = true
            conn.connectTimeout = 8000; conn.readTimeout = 8000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("apikey", TripReaderService.SUPABASE_ANON)
            conn.setRequestProperty("Authorization", "Bearer $authToken")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            if (code in 200..299) "ok" else null
        } catch (_: Exception) { null }
    }

    private fun rpcCall(authToken: String, fn: String, args: JSONObject): Any? {
        return try {
            val conn = URL("${TripReaderService.SUPABASE_URL}/rest/v1/rpc/$fn").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"; conn.doOutput = true
            conn.connectTimeout = 8000; conn.readTimeout = 8000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("apikey", TripReaderService.SUPABASE_ANON)
            conn.setRequestProperty("Authorization", "Bearer $authToken")
            conn.outputStream.use { it.write(args.toString().toByteArray()) }
            val text = conn.inputStream.bufferedReader().readText().trim()
            when {
                text == "true" -> true
                text == "false" -> false
                text.startsWith("\"") -> text.removeSurrounding("\"")
                else -> text
            }
        } catch (_: Exception) { null }
    }

    // ── Publicação real (feed_posts) ─────────────────────────────────
    private fun publish(authToken: String, userId: String, type: String, lat: Double, lng: Double,
                         address: String?, paramValue: String?, paramDetail: String?, nivel: Int?, expMin: Int) {
        thread(isDaemon = true) {
            val expiresAt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                .format(java.util.Date(System.currentTimeMillis() + expMin * 60000L))
            val body = JSONObject().apply {
                put("user_id", userId); put("type", type); put("audience", "comunidade"); put("source", "user")
                put("lat", lat); put("lng", lng)
                put("address", address ?: JSONObject.NULL); put("city", "rio")
                put("param_value", paramValue ?: JSONObject.NULL)
                put("param_detail", paramDetail ?: JSONObject.NULL)
                put("nivel", nivel ?: JSONObject.NULL)
                put("expires_at", expiresAt)
                put("votes_up", 1); put("votes_dn", 0); put("comments_count", 0); put("status", "ativo")
            }
            postJson(authToken, "${TripReaderService.SUPABASE_URL}/rest/v1/feed_posts", body)
        }
    }

    private fun markAnswered(authToken: String, userId: String, venueId: String? = null, reportId: String? = null) {
        thread(isDaemon = true) {
            try {
                val filter = if (venueId != null) "venue_id=eq.$venueId" else "report_id=eq.$reportId"
                val url = "${TripReaderService.SUPABASE_URL}/rest/v1/venue_prompt_log?" +
                    "user_id=eq.$userId&$filter&answered=eq.false&order=asked_at.desc&limit=1"
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod = "PATCH"; conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("apikey", TripReaderService.SUPABASE_ANON)
                conn.setRequestProperty("Authorization", "Bearer $authToken")
                conn.outputStream.use { it.write("""{"answered":true}""".toByteArray()) }
                conn.responseCode
            } catch (_: Exception) {}
        }
    }

    // ══════════════════════════════════════════════════════════════
    // UI — janela overlay nativa, mesmo padrão do FlashCard (sem XML,
    // View construída em código). Fica embaixo (Opção B aprovada,
    // alcance de polegar dirigindo).
    // ══════════════════════════════════════════════════════════════
    private fun dp(v: Int) = ((v * (appCtx?.resources?.displayMetrics?.density ?: 2.5f))).toInt()
    private fun dpf(v: Int) = (v * (appCtx?.resources?.displayMetrics?.density ?: 2.5f))

    private fun baseParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.BOTTOM; y = dp(90) }

    private fun startAutoHide(seconds: Int) {
        handler.removeCallbacks(autoHideRunnable)
        countdownRunnable?.let { handler.removeCallbacks(it) }
        handler.postDelayed(autoHideRunnable, seconds * 1000L)
    }

    // Sobe o card no ar. content = corpo específico de cada tipo de card,
    // montado por quem chama.
    private fun mount(ctx: Context, title: String, icColor: String,
                       locName: String, locSub: String,
                       seconds: Int, content: LinearLayout) {
        val w = wm ?: return
        container?.let { try { w.removeView(it) } catch (_: Exception) {} }
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadii = floatArrayOf(dp(22).toFloat(),dp(22).toFloat(),dp(22).toFloat(),dp(22).toFloat(),0f,0f,0f,0f)
                setColor(Color.WHITE)
            }
            setPadding(dp(18), dp(16), dp(18), dp(18))
            elevation = dp(12).toFloat()
        }
        // topo: ícone + título + contador
        val top = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val ic = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38)).apply { rightMargin = dp(10) }
            background = GradientDrawable().apply { cornerRadius = dpf(12); setColor(Color.parseColor(icColor)) }
        }
        top.addView(ic)
        val titleTv = TextView(ctx).apply {
            text = title; textSize = 15f; setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#0F172A"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        top.addView(titleTv)
        val timerTv = TextView(ctx).apply {
            text = "${seconds}s"; textSize = 11f; setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#64748B"))
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#F1F5F9")) }
            setPadding(dp(9), dp(6), dp(9), dp(6))
        }
        top.addView(timerTv)
        card.addView(top)

        // chip de local
        val locChip = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dpf(13); setColor(Color.parseColor("#EFF6FF"))
                setStroke(dp(1), Color.parseColor("#BFDBFE"))
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(12); bottomMargin = dp(14)
            }
        }
        val locTextCol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        locTextCol.addView(TextView(ctx).apply {
            text = locName; textSize = 12.5f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#1E40AF"))
        })
        if (locSub.isNotBlank()) {
            locTextCol.addView(TextView(ctx).apply {
                text = locSub; textSize = 10f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#60A5FA"))
            })
        }
        locChip.addView(locTextCol)
        card.addView(locChip)

        card.addView(content)
        container = FrameLayout(ctx).apply { addView(card) }

        try { w.addView(container, baseParams()) } catch (e: Exception) { e.printStackTrace(); return }

        // contador regressivo visível
        var remaining = seconds
        val tick = object : Runnable {
            override fun run() {
                remaining--
                if (remaining <= 0) { forceHide(); return }
                timerTv.text = "${remaining}s"
                handler.postDelayed(this, 1000L)
            }
        }
        countdownRunnable = tick
        handler.postDelayed(tick, 1000L)
        startAutoHide(seconds)
    }

    // Grade de opções — reaproveitada pelos 3 tipos de card (Quem/Tipo,
    // Lotação, Sim/Não). cols = colunas por linha.
    private fun optionRow(ctx: Context, options: List<Triple<String,String,() -> Unit>>, cols: Int): LinearLayout {
        val wrap = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        options.chunked(cols).forEach { rowItems ->
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(7) }
            }
            rowItems.forEach { (label, colorHex, onClick) ->
                val btn = TextView(ctx).apply {
                    text = label; textSize = 12f; setTypeface(null, Typeface.BOLD)
                    gravity = Gravity.CENTER
                    setPadding(dp(6), dp(13), dp(6), dp(13))
                    val bg = if (colorHex.isBlank()) "#F8FAFC" else colorHex
                    val fg = if (colorHex.isBlank()) "#0F172A" else "#FFFFFF"
                    background = GradientDrawable().apply {
                        cornerRadius = dpf(13)
                        setColor(Color.parseColor(bg))
                        if (colorHex.isBlank()) setStroke(dp(1), Color.parseColor("#E2E8F0"))
                    }
                    setTextColor(Color.parseColor(fg))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = dp(4); marginEnd = dp(4)
                    }
                    setOnClickListener { onClick() }
                }
                row.addView(btn)
            }
            wrap.addView(row)
        }
        return wrap
    }

    // ── Card 1: Fiscalização completa (Quem 2col + Tipo 3col juntos) ──
    private fun showFiscalizacaoCard(lat: Double, lng: Double, address: String?, locName: String,
                                       state: String, userId: String, authToken: String, venueId: String?) {
        val ctx = appCtx ?: return
        var orgao: String? = null; var acao: String? = null
        val content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val quemLbl = TextView(ctx).apply { text = "QUEM"; textSize = 9.5f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#94A3B8")) }
        content.addView(quemLbl)
        lateinit var quemRow: LinearLayout
        lateinit var acaoRow: LinearLayout
        fun tryPublish() {
            if (orgao != null && acao != null) {
                publish(authToken, userId, "blitz", lat, lng, address, orgao, acao, null, 60)
                if (venueId != null) markAnswered(authToken, userId, venueId)
                forceHide()
            }
        }
        quemRow = optionRow(ctx, listOf(
            Triple("Polícia Militar", "", { orgao = "Polícia Militar"; tryPublish() }),
            Triple("Guarda Municipal", "", { orgao = "Guarda Municipal"; tryPublish() }),
            Triple("Lei Seca", "", { orgao = "Lei Seca"; tryPublish() }),
            Triple("Detro", "", { orgao = "Detro"; tryPublish() })
        ), 2)
        content.addView(quemRow)
        val tipoLbl = TextView(ctx).apply { text = "TIPO"; textSize = 9.5f; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#94A3B8"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) } }
        content.addView(tipoLbl)
        acaoRow = optionRow(ctx, listOf(
            Triple("Multando", "", { acao = "Multando"; tryPublish() }),
            Triple("Abordando", "", { acao = "Abordando"; tryPublish() }),
            Triple("Posicionado", "", { acao = "Posicionado"; tryPublish() })
        ), 3)
        content.addView(acaoRow)
        mount(ctx, "Fiscalização por aqui?", "#2563EB", locName, "", if (state == "corrida") 10 else 5, content)
    }

    // ── Card 2: Lotação (5 opções) ────────────────────────────────────
    private fun showLotacaoCard(venue: Venue, state: String, userId: String, authToken: String) {
        val ctx = appCtx ?: return
        val opts = listOf(
            Triple("Vazio", "#16A34A", "Vazio"), Triple("Pouco", "#16A34A", "Pouco movimentado"),
            Triple("Médio", "#F59E0B", "Movimentado"), Triple("Muito", "#DC2626", "Muito movimentado"),
            Triple("Cheio", "#DC2626", "Cheio")
        )
        val content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        content.addView(optionRow(ctx, opts.map { (lbl, color, value) ->
            Triple(lbl, color, {
                publish(authToken, userId, "lotacao", venue.lat, venue.lng, venue.name, value, null, null, 60)
                markAnswered(authToken, userId, venue.id)
                forceHide()
            })
        }, 5))
        mount(ctx, "Como está ${venue.name}?", "#7C3AED", venue.name, CAT_LABEL[venue.category] ?: "", if (state == "corrida") 10 else 5, content)
    }

    // ── Card 3: combo Lotação + toggle Fiscalização (aeroporto/terminal) ──
    private fun showComboCard(venue: Venue, state: String, userId: String, authToken: String) {
        val ctx = appCtx ?: return
        val opts = listOf(
            Triple("Vazio", "#16A34A", "Vazio"), Triple("Pouco", "#16A34A", "Pouco movimentado"),
            Triple("Médio", "#F59E0B", "Movimentado"), Triple("Muito", "#DC2626", "Muito movimentado"),
            Triple("Cheio", "#DC2626", "Cheio")
        )
        val content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        content.addView(optionRow(ctx, opts.map { (lbl, color, value) ->
            Triple(lbl, color, {
                publish(authToken, userId, "lotacao", venue.lat, venue.lng, venue.name, value, null, null, 60)
                markAnswered(authToken, userId, venue.id)
                // Depois de responder lotação, oferece o toggle de
                // fiscalização — reaproveitando o mesmo card já aberto.
                showFiscalizacaoToggle(venue, state, userId, authToken)
            })
        }, 5))
        mount(ctx, "Como está ${venue.name}?", "#7C3AED", venue.name, CAT_LABEL[venue.category] ?: "", if (state == "corrida") 10 else 5, content)
    }

    private fun showFiscalizacaoToggle(venue: Venue, state: String, userId: String, authToken: String) {
        val ctx = appCtx ?: return
        val content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        content.addView(optionRow(ctx, listOf(
            Triple("Sim, tem fiscalização", "#DC2626", {
                showFiscalizacaoCard(venue.lat, venue.lng, venue.name, venue.name, state, userId, authToken, venue.id)
            }),
            Triple("Não", "#16A34A", { forceHide() })
        ), 2))
        mount(ctx, "Tem fiscalização aqui?", "#2563EB", venue.name, CAT_LABEL[venue.category] ?: "", if (state == "corrida") 10 else 5, content)
    }

    // ── Card 4/5: confirmação — repete exatamente o que foi relatado ──
    // venueId é opcional agora (16/08/2026) — confirmação não depende mais
    // de local pré-setado, usa report.id pra controlar anti-spam.
    private fun showConfirmacaoCard(report: NearbyReport, state: String, userId: String, authToken: String, venueId: String?) {
        val ctx = appCtx ?: return
        val isAlerta = report.type == "risco"
        val icColor = if (isAlerta) "#DC2626" else "#2563EB"
        val question = if (isAlerta) {
            "Ainda tem ${(report.paramValue ?: "alerta").lowercase()}${report.paramDetail?.let { " $it" } ?: ""}?"
        } else {
            "Ainda tem ${report.paramValue ?: "fiscalização"}${report.paramDetail?.let { " $it" } ?: ""} aqui?"
        }
        val content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        content.addView(optionRow(ctx, listOf(
            Triple("Sim, ainda", "#DC2626", {
                // Reforça o relato original — soma voto/cria uma linha nova
                // com os mesmos parâmetros (confirma, não sobrescreve).
                publish(authToken, userId, report.type, GpsService.lastLat, GpsService.lastLng,
                    report.address, report.paramValue, report.paramDetail, null,
                    if (isAlerta) 90 else 60)
                markAnswered(authToken, userId, venueId, report.id)
                forceHide()
            }),
            Triple(if (isAlerta) "Já foi liberado" else "Já saiu", "#16A34A", {
                markAnswered(authToken, userId, venueId, report.id)
                forceHide()
            })
        ), 2))
        mount(ctx, question, icColor, "Relatado há ${report.minutesAgo} min por outro motorista",
            report.address ?: "", if (state == "corrida") 10 else 5, content)
    }
}
