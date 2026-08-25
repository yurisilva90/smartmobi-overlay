package io.github.yurisilva90.smartmobi

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Registra Online / Buscar / Corrida de forma independente de auto_trips.
 * Cada trecho guarda tempo, km e GPS de início/fim e é sincronizado com
 * journey_status_segments. Assim a distribuição da Jornada sobrevive mesmo
 * quando a oferta/corrida financeira não é capturada de ponta a ponta.
 */
object JourneyStatusTracker {
    private const val PREFS = "journey_status_tracker"
    private const val KEY_SESSION_ID = "session_id"
    private const val KEY_DATE = "date"
    private const val KEY_SEGMENTS = "segments"
    private const val KEY_PAUSED = "paused"
    private const val LOCAL_CHECKPOINT_MS = 5_000L
    private const val REMOTE_CHECKPOINT_MS = 15_000L

    data class Segment(
        val id: String,
        val sessionId: String,
        val date: String,
        var platform: String?,
        val status: String,
        val startMs: Long,
        val startKm: Double,
        val startLat: Double?,
        val startLng: Double?,
        var endMs: Long? = null,
        var endKm: Double? = null,
        var endLat: Double? = null,
        var endLng: Double? = null,
        var lastSeenMs: Long = startMs,
        var lastKm: Double = startKm,
        var lastLat: Double? = startLat,
        var lastLng: Double? = startLng
    ) {
        fun toJson() = JSONObject().apply {
            put("id", id)
            put("session_id", sessionId)
            put("date", date)
            put("platform", platform ?: JSONObject.NULL)
            put("status", status)
            put("start_ms", startMs)
            put("end_ms", endMs ?: JSONObject.NULL)
            put("start_km", startKm)
            put("end_km", endKm ?: JSONObject.NULL)
            put("start_lat", startLat ?: JSONObject.NULL)
            put("start_lng", startLng ?: JSONObject.NULL)
            put("end_lat", endLat ?: JSONObject.NULL)
            put("end_lng", endLng ?: JSONObject.NULL)
            put("last_seen_ms", lastSeenMs)
            put("last_km", lastKm)
            put("last_lat", lastLat ?: JSONObject.NULL)
            put("last_lng", lastLng ?: JSONObject.NULL)
        }

        companion object {
            fun fromJson(o: JSONObject): Segment = Segment(
                id = o.getString("id"),
                sessionId = o.getString("session_id"),
                date = o.getString("date"),
                platform = o.optString("platform").takeIf { it.isNotBlank() && it != "null" },
                status = o.getString("status"),
                startMs = o.getLong("start_ms"),
                startKm = o.optDouble("start_km", 0.0),
                startLat = o.optDouble("start_lat").takeIf { !it.isNaN() },
                startLng = o.optDouble("start_lng").takeIf { !it.isNaN() },
                endMs = if (o.isNull("end_ms")) null else o.optLong("end_ms"),
                endKm = if (o.isNull("end_km")) null else o.optDouble("end_km"),
                endLat = if (o.isNull("end_lat")) null else o.optDouble("end_lat"),
                endLng = if (o.isNull("end_lng")) null else o.optDouble("end_lng"),
                lastSeenMs = o.optLong("last_seen_ms", o.getLong("start_ms")),
                lastKm = o.optDouble("last_km", o.optDouble("start_km", 0.0)),
                lastLat = if (o.isNull("last_lat")) null else o.optDouble("last_lat"),
                lastLng = if (o.isNull("last_lng")) null else o.optDouble("last_lng")
            )
        }
    }

    private var sessionId: String? = null
    private var date: String? = null
    private var paused = false
    private val segments = mutableListOf<Segment>()
    private var lastLocalCheckpoint = 0L
    private var lastRemoteCheckpoint = 0L
    private var restored = false

    @Synchronized
    fun restore(ctx: Context) {
        if (restored) return
        restored = true
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sessionId = p.getString(KEY_SESSION_ID, null)
        date = p.getString(KEY_DATE, null)
        paused = p.getBoolean(KEY_PAUSED, false)
        val raw = p.getString(KEY_SEGMENTS, null)
        if (!raw.isNullOrBlank()) {
            try {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) segments.add(Segment.fromJson(arr.getJSONObject(i)))
            } catch (_: Exception) {}
        }
    }

    @Synchronized
    fun setSession(ctx: Context, newSessionId: String, newDate: String, startMs: Long, startKm: Double, isNew: Boolean) {
        restore(ctx)
        if (sessionId == newSessionId) return
        sessionId = newSessionId
        date = newDate
        paused = false
        segments.clear()
        if (isNew) {
            startSegment("online", null, startMs, startKm, GpsService.lastLat, GpsService.lastLng)
        }
        persistLocal(ctx)
    }

    @Synchronized
    fun onStateTransition(ctx: Context, platform: String, prev: String, next: String) {
        restore(ctx)
        if (sessionId.isNullOrBlank() || paused) return
        val normalizedNext = normalize(next) ?: return
        val now = System.currentTimeMillis()
        val km = GpsService.totalKm
        val lat = GpsService.lastLat.takeIf { it != 0.0 }
        val lng = GpsService.lastLng.takeIf { it != 0.0 }
        val current = segments.lastOrNull { it.endMs == null }
        if (current?.status == normalizedNext) {
            current.platform = if (platform.isBlank()) current.platform else platform.lowercase(Locale.getDefault())
            updateCurrent(now, km, lat, lng)
            persistLocal(ctx)
            syncSegment(ctx, current)
            return
        }
        if (current != null) {
            closeSegment(current, now, km, lat, lng)
            syncSegment(ctx, current)
        }
        val s = startSegment(normalizedNext, platform, now, km, lat ?: 0.0, lng ?: 0.0)
        persistLocal(ctx)
        syncSegment(ctx, s)
    }

    @Synchronized
    fun checkpoint(ctx: Context) {
        restore(ctx)
        if (sessionId.isNullOrBlank() || paused) return
        val current = segments.lastOrNull { it.endMs == null } ?: return
        val now = System.currentTimeMillis()
        if (now - lastLocalCheckpoint < LOCAL_CHECKPOINT_MS) return
        val lat = GpsService.lastLat.takeIf { it != 0.0 }
        val lng = GpsService.lastLng.takeIf { it != 0.0 }
        updateCurrent(now, GpsService.totalKm, lat, lng)
        persistLocal(ctx)
        lastLocalCheckpoint = now
        if (now - lastRemoteCheckpoint >= REMOTE_CHECKPOINT_MS) {
            syncSegment(ctx, current)
            lastRemoteCheckpoint = now
        }
    }

    @Synchronized
    fun pause(ctx: Context) {
        restore(ctx)
        if (paused) return
        paused = true
        val current = segments.lastOrNull { it.endMs == null }
        if (current != null) {
            val now = System.currentTimeMillis()
            closeSegment(current, now, GpsService.totalKm,
                GpsService.lastLat.takeIf { it != 0.0 }, GpsService.lastLng.takeIf { it != 0.0 })
            syncSegment(ctx, current)
        }
        persistLocal(ctx)
    }

    @Synchronized
    fun resume(ctx: Context) {
        restore(ctx)
        if (!paused || sessionId.isNullOrBlank()) return
        paused = false
        val status = normalize(TripReaderService.confirmedTripSubState) ?: "online"
        val s = startSegment(status, null, System.currentTimeMillis(), GpsService.totalKm, GpsService.lastLat, GpsService.lastLng)
        persistLocal(ctx)
        syncSegment(ctx, s)
    }

    @Synchronized
    fun endSession(ctx: Context) {
        restore(ctx)
        val current = segments.lastOrNull { it.endMs == null }
        if (current != null) {
            val now = System.currentTimeMillis()
            closeSegment(current, now, GpsService.totalKm,
                GpsService.lastLat.takeIf { it != 0.0 }, GpsService.lastLng.takeIf { it != 0.0 })
            syncSegment(ctx, current)
        }
        paused = false
        persistLocal(ctx)
    }

    @Synchronized
    fun timelineJson(ctx: Context): String {
        restore(ctx)
        checkpoint(ctx)
        return JSONArray().apply { segments.forEach { put(it.toJson()) } }.toString()
    }

    private fun normalize(v: String): String? = when (v.lowercase(Locale.getDefault())) {
        "online" -> "online"
        "buscar", "buscando" -> "buscar"
        "corrida", "em_corrida" -> "corrida"
        else -> null
    }

    private fun startSegment(status: String, platform: String?, startMs: Long, startKm: Double, lat: Double, lng: Double): Segment {
        val sid = sessionId ?: ""
        val d = date ?: ""
        val s = Segment(
            id = UUID.randomUUID().toString(),
            sessionId = sid,
            date = d,
            platform = platform?.lowercase(Locale.getDefault()),
            status = status,
            startMs = startMs,
            startKm = startKm,
            startLat = lat.takeIf { it != 0.0 },
            startLng = lng.takeIf { it != 0.0 }
        )
        segments.add(s)
        return s
    }

    private fun updateCurrent(now: Long, km: Double, lat: Double?, lng: Double?) {
        val current = segments.lastOrNull { it.endMs == null } ?: return
        current.lastSeenMs = now
        current.lastKm = km
        if (lat != null) current.lastLat = lat
        if (lng != null) current.lastLng = lng
    }

    private fun closeSegment(s: Segment, now: Long, km: Double, lat: Double?, lng: Double?) {
        s.endMs = now
        s.endKm = km
        s.endLat = lat
        s.endLng = lng
        s.lastSeenMs = now
        s.lastKm = km
        if (lat != null) s.lastLat = lat
        if (lng != null) s.lastLng = lng
    }

    private fun persistLocal(ctx: Context) {
        try {
            val arr = JSONArray().apply { segments.forEach { put(it.toJson()) } }
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_SESSION_ID, sessionId)
                .putString(KEY_DATE, date)
                .putBoolean(KEY_PAUSED, paused)
                .putString(KEY_SEGMENTS, arr.toString())
                .apply()
        } catch (_: Exception) {}
    }

    private fun syncSegment(ctx: Context, s: Segment) {
        thread(isDaemon = true) {
            try {
                val prefs = ctx.getSharedPreferences(GpsService.PREFS_NAME, Context.MODE_PRIVATE)
                val userId = prefs.getString(GpsService.KEY_USER_ID, null) ?: return@thread
                val authToken = prefs.getString(GpsService.KEY_ACCESS_TOKEN, null) ?: TripReaderService.SUPABASE_ANON
                val body = s.toJson().apply { put("user_id", userId) }
                val conn = URL("${TripReaderService.SUPABASE_URL}/rest/v1/journey_status_segments").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("apikey", TripReaderService.SUPABASE_ANON)
                conn.setRequestProperty("Authorization", "Bearer $authToken")
                conn.setRequestProperty("Prefer", "resolution=merge-duplicates,return=minimal")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                conn.responseCode
                conn.disconnect()
            } catch (_: Exception) {}
        }
    }
}
