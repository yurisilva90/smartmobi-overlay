package io.github.yurisilva90.smartmobi.parser

import android.view.accessibility.AccessibilityNodeInfo
import io.github.yurisilva90.smartmobi.model.TripData

object ScreenParser {

    private val FARE_PATTERN    = Regex("""R\$\s*(\d+)[,.](\d{2})""")
    private val KM_PATTERN      = Regex("""(\d+)[,.](\d{1,2})\s*km""", RegexOption.IGNORE_CASE)
    private val MIN_PATTERN     = Regex("""(\d+)\s*min""", RegexOption.IGNORE_CASE)
    private val SEC_PATTERN     = Regex("""(\d+)\s*(?:segundo|seg)""", RegexOption.IGNORE_CASE)

    // Uber: keywords that appear on trip-completed screen
    private val UBER_COMPLETE_KEYWORDS = listOf(
        "viagem concluída", "corrida concluída", "trip completed",
        "você chegou", "destino alcançado"
    )

    // 99: keywords on trip-completed screen
    private val NINETYNINE_COMPLETE_KEYWORDS = listOf(
        "corrida finalizada", "viagem finalizada", "chegamos",
        "corrida concluída", "trip ended"
    )

    fun collectAllText(node: AccessibilityNodeInfo): List<String> {
        val texts = mutableListOf<String>()
        collectTexts(node, texts)
        return texts
    }

    private fun collectTexts(node: AccessibilityNodeInfo, out: MutableList<String>) {
        val text = node.text?.toString()?.trim()
        if (!text.isNullOrBlank()) out.add(text)
        val desc = node.contentDescription?.toString()?.trim()
        if (!desc.isNullOrBlank() && desc != text) out.add(desc)
        for (i in 0 until node.childCount) {
            try { node.getChild(i)?.let { collectTexts(it, out) } } catch (e: Exception) {}
        }
    }

    fun isUberTripCompleted(texts: List<String>): Boolean {
        val combined = texts.joinToString(" ").lowercase()
        return UBER_COMPLETE_KEYWORDS.any { combined.contains(it) }
    }

    fun is99TripCompleted(texts: List<String>): Boolean {
        val combined = texts.joinToString(" ").lowercase()
        return NINETYNINE_COMPLETE_KEYWORDS.any { combined.contains(it) }
    }

    fun extractTripData(texts: List<String>, platform: String): TripData? {
        var value = 0.0
        var km    = 0.0
        var durationMin = 0

        for (text in texts) {
            // Fare: R$ 28,50
            FARE_PATTERN.find(text)?.let { m ->
                val candidate = "${m.groupValues[1]}.${m.groupValues[2]}".toDoubleOrNull() ?: 0.0
                if (candidate > value) value = candidate
            }
            // Distance: 8,9 km
            KM_PATTERN.find(text)?.let { m ->
                val candidate = "${m.groupValues[1]}.${m.groupValues[2]}".toDoubleOrNull() ?: 0.0
                if (candidate > km) km = candidate
            }
            // Duration: "16 min" or "1 min 23 segundos"
            MIN_PATTERN.find(text)?.let { m ->
                val mins = m.groupValues[1].toIntOrNull() ?: 0
                if (mins > durationMin) durationMin = mins
            }
        }

        // Add seconds to duration if present
        val combined = texts.joinToString(" ")
        SEC_PATTERN.find(combined)?.let { m ->
            val secs = m.groupValues[1].toIntOrNull() ?: 0
            if (secs >= 30) durationMin += 1 // round up half-minute
        }

        if (value <= 0) return null

        // Detect category from text
        val lower = combined.lowercase()
        val category = when {
            lower.contains("black")   -> "Black"
            lower.contains("comfort") -> "Comfort"
            lower.contains("green")   -> "Green"
            lower.contains("flash")   -> "Flash"
            lower.contains("99pop")   -> "99Pop"
            lower.contains("turbo")   -> "Turbo"
            else -> if (platform == "uber") "UberX" else "99Pop"
        }

        val airport = lower.contains("aeroporto") ||
                      lower.contains("airport") ||
                      lower.contains("sdu") ||
                      lower.contains("gig") ||
                      lower.contains("santos dumont") ||
                      lower.contains("galeão")

        return TripData(
            value    = value,
            km       = km,
            duration = durationMin,
            platform = platform,
            category = category,
            airport  = airport
        )
    }
}
