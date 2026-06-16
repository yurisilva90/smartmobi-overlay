package io.github.yurisilva90.smartmobi.model

import java.text.NumberFormat
import java.util.Locale

data class TripData(
    val value: Double,
    val km: Double,
    val duration: Int,      // minutes
    val platform: String,   // "uber" or "99"
    val origin: String = "",
    val dest: String = "",
    val category: String = "",
    val surge: Boolean = false,
    val airport: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
) {
    val id: String get() = "${platform}_${timestamp}"

    val rpmKm: Double get() = if (km > 0.1) value / km else 0.0
    val rpmHour: Double get() = if (duration > 0) value / (duration / 60.0) else 0.0

    val isValid: Boolean get() = value > 0 && (km > 0.1 || duration > 0)

    fun formatValue(): String = formatBRL(value)
    fun formatRpmKm(): String = if (rpmKm > 0) formatBRL(rpmKm) + "/km" else "—"
    fun formatRpmHour(): String = if (rpmHour > 0) formatBRL(rpmHour) + "/h" else "—"
    fun formatKm(): String = if (km > 0) String.format("%.1f km", km) else "—"
    fun formatDuration(): String = if (duration > 0) "${duration}min" else "—"

    companion object {
        private val brFmt = NumberFormat.getCurrencyInstance(Locale("pt", "BR"))
        fun formatBRL(v: Double): String = brFmt.format(v)
    }
}
