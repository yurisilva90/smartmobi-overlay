package io.github.yurisilva90.smartmobi

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.*

/**
 * Cards colaborativos exibidos durante a jornada.
 *
 * Regras 26/08/2026:
 * - nunca interrompe o estado "buscar";
 * - oferta Uber/99 sempre tem prioridade e fecha este overlay;
 * - raio físico de 100 m;
 * - aeroporto e terminal podem perguntar por presença;
 * - hotel, shopping, turístico, centro de convenções e evento só perguntam
 *   quando existe atividade/informe ativo próximo;
 * - card simples: 15 s; card combinado: 20 s;
 * - tempo é mostrado apenas por barra animada; X permite dispensar;
 * - resposta, fechamento manual e expiração são registrados separadamente.
 */
// Anel de contagem regressiva — troca a barra linear (implementação
// anterior) por um círculo que esvazia (sweep 360°→0°) ao redor do X,
// mockup aprovado 27/08/2026. Vira vermelho nos últimos 3s.
private class CountdownRing(ctx: Context) : View(ctx) {
    var fraction = 1f
    var ringColor: Int = Color.parseColor("#7C3AED")
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#E2E8F0")
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val rect = RectF()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val strokeW = w * 0.09f
        trackPaint.strokeWidth = strokeW
        arcPaint.strokeWidth = strokeW
        rect.set(strokeW / 2f, strokeW / 2f, w - strokeW / 2f, h - strokeW / 2f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawArc(rect, 0f, 360f, false, trackPaint)
        arcPaint.color = ringColor
        canvas.drawArc(rect, -90f, 360f * fraction, false, arcPaint)
    }
}

object ProactiveAlert {

    private const val CHECK_INTERVAL_MS = 2 * 60 * 1000L
    private const val RADIUS_M = 100.0
    private const val SIMPLE_SECONDS = 15
    private const val COMBO_SECONDS = 20

    private val handler = Handler(Looper.getMainLooper())
    private val wm by lazy { appCtx?.getSystemService(Context.WINDOW_SERVICE) as? WindowManager }
    private var appCtx: Context? = null
    private var container: FrameLayout? = null
    private var countdownRunnable: Runnable? = null
    private var currentDismissCallback: ((String) -> Unit)? = null
    @Volatile private var busy = false

    private val autoHideRunnable = Runnable { hideWithOutcome("expired") }

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
        handler.postDelayed(checkRunnable, 30_000L)
    }

    fun stopLoop() {
        handler.removeCallbacks(checkRunnable)
        forceHide()
    }

    /** Fecha sem registrar recusa/expiração. Usado quando chega uma oferta. */
    fun forceHide() {
        handler.removeCallbacks(autoHideRunnable)
        countdownRunnable?.let { handler.removeCallbacks(it) }
        countdownRunnable = null
        currentDismissCallback = null
        handler.post {
            container?.let { c -> try { wm?.removeView(c) } catch (_: Exception) {} }
            container = null
        }
    }

    private fun hideWithOutcome(outcome: String) {
        val cb = currentDismissCallback
        currentDismissCallback = null
        try { cb?.invoke(outcome) } catch (_: Exception) {}
        forceHide()
    }

    private val CAT_LABEL = mapOf(
        "aeroporto" to "Aeroporto",
        "terminal_rodoviario" to "Terminal Rodoviário",
        "shopping" to "Shopping",
        "hotel" to "Hotel",
        "turistico" to "Ponto turístico",
        "centro_convencoes" to "Centro de Convenções",
        "evento" to "Local de evento"
    )

    private val ALWAYS_ELIGIBLE = setOf("aeroporto", "terminal_rodoviario")
    private val COMBO_CATEGORIES = ALWAYS_ELIGIBLE

    private fun runCheck(state: String) {
        busy = true
        try {
            val prefs = appCtx?.getSharedPreferences(GpsService.PREFS_NAME, Context.MODE_PRIVATE) ?: return
            val userId = prefs.getString(GpsService.KEY_USER_ID, null) ?: return
            val authToken = prefs.getString(GpsService.KEY_ACCESS_TOKEN, null) ?: TripReaderService.SUPABASE_ANON
            val lat = GpsService.lastLat
            val lng = GpsService.lastLng
            if (lat == 0.0 && lng == 0.0) return

            rpcCall(authToken, "ensure_venues_for_region", JSONObject().apply {
                put("p_lat", lat)
                put("p_lng", lng)
            })

            val nearbyReport = findNearbyActiveReport(authToken, lat, lng)
            if (nearbyReport != null && canPromptReport(authToken, userId, nearbyReport.id)) {
                logPrompt(authToken, userId, null, nearbyReport.id, "confirmacao", state)
                handler.post { showConfirmacaoCard(nearbyReport, userId, authToken) }
                return
            }

            val venue = findNearestVenue(authToken, lat, lng) ?: return
            if (!isVenueRelevantNow(authToken, venue)) return
            // PEDIDO (27/08/2026, Yuri): Lotação/Combo só pergunta se o local
            // é origem OU destino da corrida em andamento — "só passar em
            // frente" não é sinal confiável de lotação. Consequência real:
            // como só existe origem/destino de corrida DURANTE uma corrida,
            // esse gate faz Lotação/Combo nunca disparar no estado "online"
            // puro (sem corrida aceita) — só a Confirmação (relato de outro
            // motorista) continua funcionando em online, porque não depende
            // de trajeto.
            if (!isTripEndpointAtVenue(venue)) return
            if (!canPromptVenue(authToken, userId, venue.id)) return

            val promptType = if (venue.category in COMBO_CATEGORIES) "combo" else "lotacao"
            logPrompt(authToken, userId, venue.id, null, promptType, state)
            handler.post {
                if (venue.category in COMBO_CATEGORIES) showComboCard(venue, userId, authToken)
                else showLotacaoCard(venue, userId, authToken)
            }
        } catch (_: Exception) {
        } finally {
            busy = false
        }
    }

    private data class Venue(
        val id: String,
        val name: String,
        val category: String,
        val lat: Double,
        val lng: Double,
        val city: String?
    )

    private data class NearbyReport(
        val id: String,
        val type: String,
        val paramValue: String?,
        val paramDetail: String?,
        val address: String?,
        val minutesAgo: Int
    )

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    // Gate de origem/destino pra Lotação (pedido 27/08/2026). Dois sinais,
    // qualquer um basta: nome do local aparece no texto de origem/destino
    // da oferta (ex.: "VillageMall" lido na tela da Uber/99); OU o texto é
    // um endereço escrito em vez do nome do local — geocodifica esse texto
    // (Nominatim forward, uma vez só, com cache) e compara com a
    // coordenada do venue, margem de 20m.
    private val geocodeCache = HashMap<String, Pair<Double, Double>?>()

    private fun forwardGeocode(address: String): Pair<Double, Double>? {
        if (geocodeCache.containsKey(address)) return geocodeCache[address]
        val result = try {
            val q = URLEncoder.encode("$address, Rio de Janeiro, Brasil", "UTF-8")
            val url = URL("https://nominatim.openstreetmap.org/search?q=$q&format=json&limit=1&countrycodes=br")
            val conn = url.openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "MoB-App/1.0 (contato: yurisilva1990@gmail.com)")
            conn.connectTimeout = 5000; conn.readTimeout = 5000
            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            val arr = JSONArray(body)
            if (arr.length() > 0) {
                val o = arr.getJSONObject(0)
                o.getString("lat").toDouble() to o.getString("lon").toDouble()
            } else null
        } catch (_: Exception) { null }
        geocodeCache[address] = result
        // Respeita rate limit 1 req/s do Nominatim (mesmo padrão usado no
        // reverseGeocodeFull do GpsService) — só chama de verdade quando não
        // tinha cache, então não penaliza checagens repetidas do mesmo texto.
        try { Thread.sleep(1100) } catch (_: Exception) {}
        return result
    }

    private fun isTripEndpointAtVenue(venue: Venue): Boolean {
        val endpoints = AutoTripCapture.currentTripEndpointTexts()
        if (endpoints.isEmpty()) return false
        val venueName = venue.name.lowercase(Locale.getDefault())
        for (addr in endpoints) {
            val a = addr.lowercase(Locale.getDefault())
            if (a.contains(venueName) || venueName.contains(a)) return true
            val geo = forwardGeocode(addr) ?: continue
            if (haversine(venue.lat, venue.lng, geo.first, geo.second) <= 20.0) return true
        }
        return false
    }

    private fun findNearestVenue(authToken: String, lat: Double, lng: Double): Venue? {
        val d = 0.002
        val url = "${TripReaderService.SUPABASE_URL}/rest/v1/venue_cache?" +
            "lat=gte.${lat-d}&lat=lte.${lat+d}&lng=gte.${lng-d}&lng=lte.${lng+d}&" +
            "select=id,name,category,lat,lng,city"
        val arr = getJson(authToken, url) as? JSONArray ?: return null
        var best: Venue? = null
        var bestDist = RADIUS_M
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val vlat = o.getDouble("lat")
            val vlng = o.getDouble("lng")
            val dist = haversine(lat, lng, vlat, vlng)
            if (dist <= bestDist) {
                bestDist = dist
                best = Venue(
                    o.getString("id"),
                    o.getString("name"),
                    o.getString("category"),
                    vlat,
                    vlng,
                    o.optString("city", null)
                )
            }
        }
        return best
    }

    /** Aeroporto/terminal são sempre elegíveis; demais exigem atividade real ativa. */
    private fun isVenueRelevantNow(authToken: String, venue: Venue): Boolean {
        if (venue.category in ALWAYS_ELIGIBLE) return true
        return hasActiveInformNear(authToken, venue.lat, venue.lng) || hasActiveFeedNear(authToken, venue.lat, venue.lng)
    }

    private fun hasActiveInformNear(authToken: String, lat: Double, lng: Double): Boolean {
        val d = 0.0012
        val now = utcIso(System.currentTimeMillis())
        val url = "${TripReaderService.SUPABASE_URL}/rest/v1/informes?" +
            "location_lat=gte.${lat-d}&location_lat=lte.${lat+d}&" +
            "location_lng=gte.${lng-d}&location_lng=lte.${lng+d}&" +
            "is_active=eq.true&expires_at=gt.$now&select=id&limit=1"
        return ((getJson(authToken, url) as? JSONArray)?.length() ?: 0) > 0
    }

    private fun hasActiveFeedNear(authToken: String, lat: Double, lng: Double): Boolean {
        val d = 0.0012
        val now = utcIso(System.currentTimeMillis())
        val url = "${TripReaderService.SUPABASE_URL}/rest/v1/feed_posts?" +
            "lat=gte.${lat-d}&lat=lte.${lat+d}&lng=gte.${lng-d}&lng=lte.${lng+d}&" +
            "status=eq.ativo&expires_at=gt.$now&select=id&limit=1"
        return ((getJson(authToken, url) as? JSONArray)?.length() ?: 0) > 0
    }

    private fun findNearbyActiveReport(authToken: String, lat: Double, lng: Double): NearbyReport? {
        val d = 0.002
        val now = utcIso(System.currentTimeMillis())
        val url = "${TripReaderService.SUPABASE_URL}/rest/v1/feed_posts?" +
            "lat=gte.${lat-d}&lat=lte.${lat+d}&lng=gte.${lng-d}&lng=lte.${lng+d}&" +
            "type=in.(blitz,risco)&status=eq.ativo&expires_at=gt.$now&" +
            "select=id,type,param_value,param_detail,address,lat,lng,created_at&order=created_at.desc&limit=5"
        val arr = getJson(authToken, url) as? JSONArray ?: return null
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (haversine(lat, lng, o.getDouble("lat"), o.getDouble("lng")) <= RADIUS_M) {
                val minutesAgo = try {
                    val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                    val then = fmt.parse(o.optString("created_at").take(19))?.time ?: 0L
                    ((System.currentTimeMillis() - then) / 60000).toInt().coerceAtLeast(0)
                } catch (_: Exception) { 0 }
                return NearbyReport(
                    o.getString("id"),
                    o.getString("type"),
                    o.optString("param_value", null),
                    o.optString("param_detail", null),
                    o.optString("address", null),
                    minutesAgo
                )
            }
        }
        return null
    }

    private fun canPromptVenue(authToken: String, userId: String, venueId: String): Boolean =
        rpcCall(authToken, "can_prompt_venue", JSONObject().apply {
            put("p_user_id", userId)
            put("p_venue_id", venueId)
        }) == true

    private fun canPromptReport(authToken: String, userId: String, reportId: String): Boolean =
        rpcCall(authToken, "can_prompt_report", JSONObject().apply {
            put("p_user_id", userId)
            put("p_report_id", reportId)
        }) == true

    private fun logPrompt(authToken: String, userId: String, venueId: String?, reportId: String?, type: String, state: String) {
        thread(isDaemon = true) {
            val body = JSONObject().apply {
                put("user_id", userId)
                put("venue_id", venueId ?: JSONObject.NULL)
                put("report_id", reportId ?: JSONObject.NULL)
                put("prompt_type", type)
                put("driver_state", state)
            }
            postJson(authToken, "${TripReaderService.SUPABASE_URL}/rest/v1/venue_prompt_log", body)
        }
    }

    private fun markPromptOutcome(authToken: String, userId: String, venueId: String? = null, reportId: String? = null, outcome: String) {
        thread(isDaemon = true) {
            try {
                val filter = when {
                    venueId != null -> "venue_id=eq.$venueId"
                    reportId != null -> "report_id=eq.$reportId"
                    else -> return@thread
                }
                val url = "${TripReaderService.SUPABASE_URL}/rest/v1/venue_prompt_log?" +
                    "user_id=eq.$userId&$filter&interaction_result=is.null&order=asked_at.desc&limit=1"
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod = "PATCH"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("apikey", TripReaderService.SUPABASE_ANON)
                conn.setRequestProperty("Authorization", "Bearer $authToken")
                val body = JSONObject().apply {
                    put("answered", outcome == "answered")
                    put("interaction_result", outcome)
                    put("resolved_at", utcIso(System.currentTimeMillis()))
                }
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                conn.responseCode
            } catch (_: Exception) {}
        }
    }

    private fun getJson(authToken: String, url: String): Any? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("apikey", TripReaderService.SUPABASE_ANON)
        conn.setRequestProperty("Authorization", "Bearer $authToken")
        val text = conn.inputStream.bufferedReader().readText()
        if (text.trim().startsWith("[")) JSONArray(text) else JSONObject(text)
    } catch (_: Exception) { null }

    private fun postJson(authToken: String, url: String, body: JSONObject): String? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("apikey", TripReaderService.SUPABASE_ANON)
        conn.setRequestProperty("Authorization", "Bearer $authToken")
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        if (conn.responseCode in 200..299) "ok" else null
    } catch (_: Exception) { null }

    private fun rpcCall(authToken: String, fn: String, args: JSONObject): Any? = try {
        val conn = URL("${TripReaderService.SUPABASE_URL}/rest/v1/rpc/$fn").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("apikey", TripReaderService.SUPABASE_ANON)
        conn.setRequestProperty("Authorization", "Bearer $authToken")
        conn.outputStream.use { it.write(args.toString().toByteArray()) }
        when (val text = conn.inputStream.bufferedReader().readText().trim()) {
            "true" -> true
            "false" -> false
            else -> if (text.startsWith("\"")) text.removeSurrounding("\"") else text
        }
    } catch (_: Exception) { null }

    private fun utcIso(ms: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(java.util.Date(ms))

    private fun publish(
        authToken: String,
        userId: String,
        type: String,
        lat: Double,
        lng: Double,
        address: String?,
        paramValue: String?,
        paramDetail: String?,
        nivel: Int?,
        expMin: Int,
        city: String? = null
    ) {
        thread(isDaemon = true) {
            val body = JSONObject().apply {
                put("user_id", userId)
                put("type", type)
                put("audience", "comunidade")
                put("source", "user")
                put("lat", lat)
                put("lng", lng)
                put("address", address ?: JSONObject.NULL)
                put("city", city ?: JSONObject.NULL)
                put("param_value", paramValue ?: JSONObject.NULL)
                put("param_detail", paramDetail ?: JSONObject.NULL)
                put("nivel", nivel ?: JSONObject.NULL)
                put("expires_at", utcIso(System.currentTimeMillis() + expMin * 60000L))
                put("votes_up", 1)
                put("votes_dn", 0)
                put("comments_count", 0)
                put("status", "ativo")
            }
            postJson(authToken, "${TripReaderService.SUPABASE_URL}/rest/v1/feed_posts", body)
        }
    }

    private fun dp(v: Int) = (v * (appCtx?.resources?.displayMetrics?.density ?: 2.5f)).toInt()
    private fun dpf(v: Int) = v * (appCtx?.resources?.displayMetrics?.density ?: 2.5f)

    private fun baseParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.BOTTOM
        y = dp(90)
    }

    private fun mount(
        ctx: Context,
        title: String,
        icColor: String,
        locName: String,
        locSub: String,
        seconds: Int,
        content: LinearLayout,
        onDismiss: ((String) -> Unit)? = null
    ) {
        val w = wm ?: return
        handler.removeCallbacks(autoHideRunnable)
        countdownRunnable?.let { handler.removeCallbacks(it) }
        container?.let { try { w.removeView(it) } catch (_: Exception) {} }
        currentDismissCallback = onDismiss

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadii = floatArrayOf(dpf(22), dpf(22), dpf(22), dpf(22), 0f, 0f, 0f, 0f)
                setColor(Color.WHITE)
            }
            setPadding(dp(18), dp(16), dp(18), dp(18))
            elevation = dp(12).toFloat()
        }

        val top = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38)).apply { rightMargin = dp(10) }
            background = GradientDrawable().apply {
                cornerRadius = dpf(12)
                setColor(Color.parseColor(icColor))
            }
        })
        top.addView(TextView(ctx).apply {
            text = title
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#0F172A"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val ringWrap = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
            setOnClickListener { hideWithOutcome("closed") }
        }
        val ring = CountdownRing(ctx).apply {
            ringColor = Color.parseColor(icColor)
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        ringWrap.addView(ring)
        ringWrap.addView(TextView(ctx).apply {
            text = "×"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#64748B"))
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        })
        top.addView(ringWrap)
        card.addView(top)

        val locChip = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dpf(13)
                setColor(Color.parseColor("#EFF6FF"))
                setStroke(dp(1), Color.parseColor("#BFDBFE"))
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(12)
                bottomMargin = dp(14)
            }
        }
        val locText = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        locText.addView(TextView(ctx).apply {
            text = locName
            textSize = 12.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#1E40AF"))
        })
        if (locSub.isNotBlank()) {
            locText.addView(TextView(ctx).apply {
                text = locSub
                textSize = 10f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#60A5FA"))
            })
        }
        locChip.addView(locText)
        card.addView(locChip)
        card.addView(content)

        container = FrameLayout(ctx).apply { addView(card) }
        try { w.addView(container, baseParams()) }
        catch (_: Exception) { currentDismissCallback = null; return }

        val started = System.currentTimeMillis()
        val duration = seconds * 1000L
        val tick = object : Runnable {
            override fun run() {
                val elapsed = System.currentTimeMillis() - started
                ring.fraction = (1f - elapsed.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                val msLeft = duration - elapsed
                ring.ringColor = if (msLeft in 0..3000) Color.parseColor("#DC2626") else Color.parseColor(icColor)
                ring.invalidate()
                if (elapsed < duration) handler.postDelayed(this, 100L)
            }
        }
        countdownRunnable = tick
        handler.post(tick)
        handler.postDelayed(autoHideRunnable, duration)
    }

    private fun optionRow(ctx: Context, options: List<Triple<String, String, () -> Unit>>, cols: Int): LinearLayout {
        val wrap = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        options.chunked(cols).forEach { rowItems ->
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(7) }
            }
            rowItems.forEach { (label, colorHex, onClick) ->
                row.addView(TextView(ctx).apply {
                    text = label
                    textSize = 12f
                    setTypeface(null, Typeface.BOLD)
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
                        marginStart = dp(4)
                        marginEnd = dp(4)
                    }
                    setOnClickListener { onClick() }
                })
            }
            wrap.addView(row)
        }
        return wrap
    }

    private fun showFiscalizacaoCard(venue: Venue, userId: String, authToken: String) {
        val ctx = appCtx ?: return
        var orgao: String? = null
        var acao: String? = null
        val content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        fun tryPublish() {
            if (orgao != null && acao != null) {
                publish(authToken, userId, "blitz", venue.lat, venue.lng, venue.name, orgao, acao, null, 60, venue.city)
                markPromptOutcome(authToken, userId, venueId = venue.id, outcome = "answered")
                forceHide()
            }
        }

        content.addView(TextView(ctx).apply {
            text = "QUEM"
            textSize = 9.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#94A3B8"))
        })
        content.addView(optionRow(ctx, listOf(
            Triple("Polícia Militar", "", { orgao = "Polícia Militar"; tryPublish() }),
            Triple("Guarda Municipal", "", { orgao = "Guarda Municipal"; tryPublish() }),
            Triple("Lei Seca", "", { orgao = "Lei Seca"; tryPublish() }),
            Triple("Detro", "", { orgao = "Detro"; tryPublish() })
        ), 2))

        content.addView(TextView(ctx).apply {
            text = "TIPO"
            textSize = 9.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#94A3B8"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
        })
        content.addView(optionRow(ctx, listOf(
            Triple("Multando", "", { acao = "Multando"; tryPublish() }),
            Triple("Abordando", "", { acao = "Abordando"; tryPublish() }),
            Triple("Posicionado", "", { acao = "Posicionado"; tryPublish() })
        ), 3))

        mount(ctx, "Fiscalização por aqui?", "#2563EB", venue.name, CAT_LABEL[venue.category] ?: "", COMBO_SECONDS, content) {
            markPromptOutcome(authToken, userId, venueId = venue.id, outcome = it)
        }
    }

    private fun showLotacaoCard(venue: Venue, userId: String, authToken: String) {
        val ctx = appCtx ?: return
        val opts = listOf(
            Triple("Vazio", "#16A34A", "Vazio"),
            Triple("Pouco", "#16A34A", "Pouco movimentado"),
            Triple("Médio", "#F59E0B", "Movimentado"),
            Triple("Muito", "#DC2626", "Muito movimentado"),
            Triple("Cheio", "#DC2626", "Cheio")
        )
        val content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        content.addView(optionRow(ctx, opts.map { (lbl, color, value) ->
            Triple(lbl, color, {
                publish(authToken, userId, "lotacao", venue.lat, venue.lng, venue.name, value, null, null, 60, venue.city)
                markPromptOutcome(authToken, userId, venueId = venue.id, outcome = "answered")
                forceHide()
            })
        }, 5))
        mount(ctx, "Como está ${venue.name}?", "#7C3AED", venue.name, CAT_LABEL[venue.category] ?: "", SIMPLE_SECONDS, content) {
            markPromptOutcome(authToken, userId, venueId = venue.id, outcome = it)
        }
    }

    private fun showComboCard(venue: Venue, userId: String, authToken: String) {
        val ctx = appCtx ?: return
        val opts = listOf(
            Triple("Vazio", "#16A34A", "Vazio"),
            Triple("Pouco", "#16A34A", "Pouco movimentado"),
            Triple("Médio", "#F59E0B", "Movimentado"),
            Triple("Muito", "#DC2626", "Muito movimentado"),
            Triple("Cheio", "#DC2626", "Cheio")
        )
        val content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        content.addView(optionRow(ctx, opts.map { (lbl, color, value) ->
            Triple(lbl, color, {
                publish(authToken, userId, "lotacao", venue.lat, venue.lng, venue.name, value, null, null, 60, venue.city)
                markPromptOutcome(authToken, userId, venueId = venue.id, outcome = "answered")
                showFiscalizacaoToggle(venue, userId, authToken)
            })
        }, 5))
        mount(ctx, "Como está ${venue.name}?", "#7C3AED", venue.name, CAT_LABEL[venue.category] ?: "", COMBO_SECONDS, content) {
            markPromptOutcome(authToken, userId, venueId = venue.id, outcome = it)
        }
    }

    private fun showFiscalizacaoToggle(venue: Venue, userId: String, authToken: String) {
        val ctx = appCtx ?: return
        val content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        content.addView(optionRow(ctx, listOf(
            Triple("Sim, tem fiscalização", "#DC2626", { showFiscalizacaoCard(venue, userId, authToken) }),
            Triple("Não", "#16A34A", { forceHide() })
        ), 2))
        mount(ctx, "Tem fiscalização aqui?", "#2563EB", venue.name, CAT_LABEL[venue.category] ?: "", COMBO_SECONDS, content)
    }

    private fun showConfirmacaoCard(report: NearbyReport, userId: String, authToken: String) {
        val ctx = appCtx ?: return
        val isAlert = report.type == "risco"
        val color = if (isAlert) "#DC2626" else "#2563EB"
        val question = if (isAlert) {
            "Ainda tem ${(report.paramValue ?: "alerta").lowercase()}${report.paramDetail?.let { " $it" } ?: ""}?"
        } else {
            "Ainda tem ${report.paramValue ?: "fiscalização"}${report.paramDetail?.let { " $it" } ?: ""} aqui?"
        }
        val content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        content.addView(optionRow(ctx, listOf(
            Triple("Sim, ainda", "#DC2626", {
                publish(authToken, userId, report.type, GpsService.lastLat, GpsService.lastLng, report.address, report.paramValue, report.paramDetail, null, if (isAlert) 90 else 60)
                markPromptOutcome(authToken, userId, reportId = report.id, outcome = "answered")
                forceHide()
            }),
            Triple(if (isAlert) "Já foi liberado" else "Já saiu", "#16A34A", {
                markPromptOutcome(authToken, userId, reportId = report.id, outcome = "answered")
                forceHide()
            })
        ), 2))
        mount(ctx, question, color, "Relatado há ${report.minutesAgo} min por outro motorista", report.address ?: "", SIMPLE_SECONDS, content) {
            markPromptOutcome(authToken, userId, reportId = report.id, outcome = it)
        }
    }
}
