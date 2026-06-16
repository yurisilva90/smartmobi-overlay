package io.github.yurisilva90.smartmobi.storage

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.github.yurisilva90.smartmobi.model.TripData
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TripStorage {

    private const val PREFS_NAME = "smartmobi_trips"
    private val gson = Gson()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveTrip(context: Context, trip: TripData) {
        val today = dateFormat.format(Date())
        val key = "day_$today"
        val existing = getTripsForDate(context, today).toMutableList()

        // Deduplication: same value within 5 minutes
        val isDuplicate = existing.any { ex ->
            Math.abs(ex.value - trip.value) < 0.02 &&
            Math.abs(ex.timestamp - trip.timestamp) < 5 * 60 * 1000
        }
        if (isDuplicate || !trip.isValid) return

        existing.add(trip)
        prefs(context).edit().putString(key, gson.toJson(existing)).apply()
    }

    fun getTripsForDate(context: Context, date: String): List<TripData> {
        val json = prefs(context).getString("day_$date", null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<TripData>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) { emptyList() }
    }

    fun getTodayTrips(context: Context): List<TripData> =
        getTripsForDate(context, dateFormat.format(Date()))

    fun getTodayTotal(context: Context): Double =
        getTodayTrips(context).sumOf { it.value }

    fun getTodayCount(context: Context): Int =
        getTodayTrips(context).size

    fun getTodayKm(context: Context): Double =
        getTodayTrips(context).sumOf { it.km }

    // Export to JSON format compatible with SmartMobi web app localStorage
    fun exportToSmartMobiFormat(context: Context): String {
        val today = dateFormat.format(Date())
        val trips = getTodayTrips(context)
        val dayData = mapOf(
            "date" to today,
            "trips" to trips.map { t ->
                mapOf(
                    "id" to t.id,
                    "platform" to t.platform,
                    "time" to SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(t.timestamp)),
                    "duration" to t.duration,
                    "origin" to t.origin,
                    "dest" to t.dest,
                    "value" to t.value,
                    "km" to t.km,
                    "category" to t.category,
                    "surge" to t.surge,
                    "airport" to t.airport,
                    "_source" to "overlay"
                )
            },
            "expenses" to emptyList<Any>(),
            "revenues" to emptyList<Any>(),
            "kmTotal" to null
        )
        return gson.toJson(mapOf("sm_day_$today" to dayData))
    }
}
