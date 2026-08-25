package io.github.yurisilva90.smartmobi

import android.app.Notification
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Captura dados estruturados das notificações oficiais da Uber/99.
 * Não persiste o texto bruto nem nome do passageiro; preserva os endereços de
 * origem/destino/paradas quando a própria notificação os expõe, além dos sinais
 * operacionais necessários para antecipar a leitura do Flash.
 */
class DriverNotificationListenerService : NotificationListenerService() {

    companion object {
        private val UBER = setOf("com.ubercab.driver", "com.ubercab")
        private val NN99 = setOf("com.app99.driver", "com.taxis99.driver")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        capture(sbn, "posted")
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        capture(sbn, "removed")
    }

    private fun platformFor(pkg: String): String? = when {
        UBER.contains(pkg) -> "UBER"
        NN99.contains(pkg) -> "99"
        else -> null
    }

    private fun capture(sbn: StatusBarNotification, eventType: String) {
        val platform = platformFor(sbn.packageName) ?: return
        val n = sbn.notification ?: return
        val extras = n.extras

        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val big = extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val sub = extras?.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
        val info = extras?.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString().orEmpty()
        val summary = extras?.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString().orEmpty()
        val lines = try {
            extras?.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.map { it.toString() } ?: emptyList()
        } catch (_: Exception) { emptyList() }

        val parts = ArrayList<String>()
        listOf(title, text, big, sub, info, summary).filterTo(parts) { it.isNotBlank() }
        lines.filterTo(parts) { it.isNotBlank() }
        val joined = parts.joinToString(" ").replace(Regex("\\s+"), " ").trim()
        val low = joined.lowercase(Locale("pt", "BR"))

        val money = extractMoney(joined)
        val kms = extractKm(low)
        val mins = extractMinutes(low)
        val routeInfo = extractRouteInfo(parts, mins)
        val keywords = extractKeywords(low)
        val actionLabels = ArrayList<String>()
        try {
            n.actions?.forEach { a -> operationalAction(a.title?.toString().orEmpty())?.let { actionLabels.add(it) } }
        } catch (_: Exception) {}

        if (eventType == "posted") {
            TripReaderService.onOfficialOperationalNotification(platform, keywords.distinct(), actionLabels.distinct())
            if (routeInfo.origin != null || routeInfo.dest != null || routeInfo.stops.isNotEmpty()) {
                TripReaderService.onOfficialRouteInfo(platform, routeInfo.origin, routeInfo.dest, routeInfo.stops)
            }
        }

        // Só vira gatilho forte quando a própria notificação traz sinais de
        // oferta. Uma notificação genérica de status nunca troca Uber↔99.
        val hasMoney = money.any { it >= 5.0 }
        val actionOffer = actionLabels.any { it == "aceitar" || it == "recusar" }
        val keywordOffer = keywords.any { it in setOf("oferta", "nova_corrida", "nova_viagem", "aceitar", "recusar") }
        val offerHint = eventType == "posted" && (n.flags and Notification.FLAG_ONGOING_EVENT) == 0 && (actionOffer || (hasMoney && (keywordOffer || kms.isNotEmpty() || mins.isNotEmpty())))

        if (offerHint) {
            // O texto não é persistido. Só é passado em memória ao leitor
            // para fixar a plataforma e antecipar uma captura prioritária.
            TripReaderService.onOfficialNotification(platform, parts, true)
        }

        persistStructured(
            sbn, n, platform, eventType,
            title.isNotBlank(), text.isNotBlank(), big.isNotBlank(), joined.length,
            money, kms, mins, keywords.distinct(), actionLabels.distinct(), offerHint, routeInfo
        )
    }

    private fun operationalAction(raw: String): String? {
        val s = raw.lowercase(Locale("pt", "BR"))
        return when {
            "aceit" in s -> "aceitar"
            "recus" in s || "rejeit" in s -> "recusar"
            "cheguei" in s || "chegar" in s -> "cheguei"
            "iniciar" in s -> "iniciar"
            "finalizar" in s || "encerrar" in s -> "finalizar"
            "abrir" in s || "ver" == s.trim() -> "abrir"
            else -> null
        }
    }

    private fun extractKeywords(low: String): List<String> {
        val out = ArrayList<String>()
        fun add(key: String, vararg needles: String) { if (needles.any { low.contains(it) }) out.add(key) }
        add("oferta", "oferta")
        add("nova_corrida", "nova corrida", "nova solicitação", "nova solicitacao")
        add("nova_viagem", "nova viagem")
        add("aceitar", "aceitar", "aceite")
        add("recusar", "recusar", "rejeitar")
        add("passageiro", "passageiro")
        add("embarque", "embarque")
        add("cheguei", "cheguei", "chegar")
        add("iniciar", "iniciar viagem", "iniciar corrida")
        add("finalizar", "finalizar viagem", "encerrar viagem", "encerrar corrida")
        add("online", "online")
        add("offline", "offline")
        add("corrida", "corrida")
        add("viagem", "viagem")
        return out
    }

    private fun extractMoney(s: String): List<Double> {
        val out = ArrayList<Double>()
        Regex("""(?i)r\$\s*([\d.]+(?:,\d{1,2})?)""").findAll(s).forEach { m ->
            m.groupValues[1].replace(".", "").replace(",", ".").toDoubleOrNull()?.let { if (it in 0.01..5000.0) out.add(it) }
        }
        return out.distinct()
    }

    private fun extractKm(s: String): List<Double> {
        val out = ArrayList<Double>()
        Regex("""(\d{1,3}(?:[.,]\d+)?)\s*km\b""", RegexOption.IGNORE_CASE).findAll(s).forEach { m ->
            m.groupValues[1].replace(",", ".").toDoubleOrNull()?.let { if (it in 0.01..500.0) out.add(it) }
        }
        return out.distinct()
    }

    private fun extractMinutes(s: String): List<Int> {
        val out = ArrayList<Int>()
        Regex("""(\d{1,3})\s*min(?:uto|utos)?\b""", RegexOption.IGNORE_CASE).findAll(s).forEach { m ->
            m.groupValues[1].toIntOrNull()?.let { if (it in 1..600) out.add(it) }
        }
        return out.distinct()
    }

    private data class RouteInfo(
        val origin: String?, val dest: String?, val stopCount: Int,
        val stops: List<String>, val routeDurationSec: Int?
    )

    private fun extractRouteInfo(parts: List<String>, mins: List<Int>): RouteInfo {
        val cleaned = parts.flatMap { it.split('\n') }.map { it.replace(Regex("\\s+"), " ").trim() }.filter { it.isNotBlank() }
        fun noise(line: String): Boolean {
            val low = line.lowercase(Locale("pt", "BR")).trim()
            if (low.length < 5 || low.contains("r$")) return true
            if (Regex("""^\(?\s*\d{1,3}(?:[.,]\d+)?\s*(?:min(?:utos)?|km|m)\b""", RegexOption.IGNORE_CASE).containsMatchIn(low)) return true
            if (low in setOf("online", "offline", "buscando", "aceitar", "recusar")) return true
            return false
        }
        fun addressLike(line: String): Boolean {
            if (noise(line)) return false
            val street = Regex("""\b(rua|r\.?|av\.?|avenida|estrada|travessa|alameda|rodovia|pra[çc]a|largo|ladeira|via)\b""", RegexOption.IGNORE_CASE).containsMatchIn(line)
            val numbered = Regex("""[^\d],\s*\d{1,5}\b""").containsMatchIn(line)
            return street || numbered
        }
        fun labeled(labels: List<String>): String? {
            for (i in cleaned.indices) {
                val line = cleaned[i]; val low = line.lowercase(Locale("pt", "BR"))
                for (label in labels) {
                    val pos = low.indexOf(label); if (pos < 0) continue
                    val tail = line.substring(pos + label.length).trim().trimStart(':','-','–','—',' ').trim()
                    if (!noise(tail)) return tail
                    for (j in (i + 1)..minOf(i + 2, cleaned.size - 1)) if (!noise(cleaned[j]) && (addressLike(cleaned[j]) || cleaned[j].length >= 5)) return cleaned[j]
                }
            }
            return null
        }
        var origin = labeled(listOf("origem", "embarque", "buscar em", "pickup", "de:"))
        var dest = labeled(listOf("destino", "desembarque", "dropoff", "para:"))
        val stops = ArrayList<String>(); var declaredStops = 0
        cleaned.forEachIndexed { i, line ->
            Regex("""\b(\d{1,2})\s*paradas?\b""", RegexOption.IGNORE_CASE).find(line)?.let { declaredStops = maxOf(declaredStops, it.groupValues[1].toIntOrNull() ?: 0) }
            Regex("""(?i)(?:parada|stop)\s*\d*\s*[:\-–—]\s*(.+)$""").find(line)?.groupValues?.getOrNull(1)?.trim()?.let { if (!noise(it)) stops.add(it) }
            if (Regex("""(?i)^\s*(?:parada|stop)\s*\d*\s*[:\-–—]?\s*$""").matches(line)) cleaned.getOrNull(i + 1)?.let { if (!noise(it)) stops.add(it) }
        }
        val candidates = cleaned.filter { addressLike(it) }.distinct()
        if (origin == null) origin = candidates.firstOrNull()
        if (dest == null) dest = candidates.lastOrNull()?.takeIf { it != origin }
        if (stops.isEmpty() && declaredStops > 0) stops.addAll(candidates.filter { it != origin && it != dest }.take(declaredStops))
        return RouteInfo(origin, dest, maxOf(declaredStops, stops.size), stops.distinct(), mins.lastOrNull()?.times(60))
    }

    private fun hash(s: String?): String? {
        if (s.isNullOrBlank()) return null
        return MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun persistStructured(
        sbn: StatusBarNotification,
        n: Notification,
        platform: String,
        eventType: String,
        titlePresent: Boolean,
        textPresent: Boolean,
        bigTextPresent: Boolean,
        contentLength: Int,
        money: List<Double>,
        kms: List<Double>,
        mins: List<Int>,
        keywords: List<String>,
        actionLabels: List<String>,
        offerHint: Boolean,
        routeInfo: RouteInfo
    ) {
        thread(isDaemon = true) {
            try {
                val prefs = getSharedPreferences(GpsService.PREFS_NAME, MODE_PRIVATE)
                val userId = prefs.getString(GpsService.KEY_USER_ID, null) ?: return@thread
                val token = prefs.getString(GpsService.KEY_ACCESS_TOKEN, null) ?: return@thread
                val deviceId = try { Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) } catch (_: Exception) { null }
                val channel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) n.channelId else null
                val extrasKeys = try { n.extras?.keySet()?.sorted()?.take(80) ?: emptyList() } catch (_: Exception) { emptyList() }
                // Hash somente do formato operacional já sanitizado; texto bruto/PII não entra.
                val shape = listOf(platform, channel ?: "", n.category ?: "", money.joinToString(","), kms.joinToString(","), mins.joinToString(","), keywords.joinToString(","), actionLabels.joinToString(",")).joinToString("|")

                val body = JSONObject().apply {
                    put("user_id", userId)
                    put("device_id", deviceId ?: JSONObject.NULL)
                    put("platform", platform)
                    put("package", sbn.packageName)
                    put("event_type", eventType)
                    put("notification_id", sbn.id)
                    put("tag_hash", hash(sbn.tag) ?: JSONObject.NULL)
                    put("key_hash", hash(sbn.key) ?: JSONObject.NULL)
                    put("channel_id", channel ?: JSONObject.NULL)
                    put("category", n.category ?: JSONObject.NULL)
                    put("post_time_ms", sbn.postTime)
                    put("is_ongoing", (n.flags and Notification.FLAG_ONGOING_EVENT) != 0)
                    put("flags", n.flags)
                    put("title_present", titlePresent)
                    put("text_present", textPresent)
                    put("big_text_present", bigTextPresent)
                    put("content_length", contentLength)
                    put("content_hash", hash(shape) ?: JSONObject.NULL)
                    put("extras_keys", JSONArray(extrasKeys))
                    put("action_labels", JSONArray(actionLabels))
                    put("money_values", JSONArray(money))
                    put("km_values", JSONArray(kms))
                    put("minute_values", JSONArray(mins))
                    put("keywords", JSONArray(keywords))
                    put("offer_hint", offerHint)
                    put("origin_address", routeInfo.origin ?: JSONObject.NULL)
                    put("dest_address", routeInfo.dest ?: JSONObject.NULL)
                    put("stop_count", routeInfo.stopCount)
                    put("stop_addresses", JSONArray(routeInfo.stops))
                    put("route_duration_sec", routeInfo.routeDurationSec ?: JSONObject.NULL)
                }

                val conn = URL("${TripReaderService.SUPABASE_URL}/rest/v1/driver_notification_events").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 7000
                conn.readTimeout = 7000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("apikey", TripReaderService.SUPABASE_ANON)
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.setRequestProperty("Prefer", "return=minimal")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                conn.responseCode
                conn.disconnect()
            } catch (_: Exception) {}
        }
    }
}
