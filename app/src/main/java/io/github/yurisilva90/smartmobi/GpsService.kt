package io.github.yurisilva90.smartmobi

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.core.app.NotificationCompat

class GpsService : Service(), LocationListener {

    companion object {
        const val CHANNEL_ID = "smartmobi_gps"
        const val NOTIF_ID   = 1

        // Estado compartilhado com MainActivity/FloatingWidget
        var totalKm    = 0.0
        var startTimeMs = 0L
        var isRunning  = false
        var isPaused   = false
        var pausedMs   = 0L
        var pauseStartMs = 0L
    }

    private lateinit var locationManager: LocationManager
    private var lastLocation: Location? = null
    private var lastFixTime: Long = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "PAUSE"  -> { isPaused = true;  pauseStartMs = System.currentTimeMillis(); updateNotif("Pausado"); return START_STICKY }
            "RESUME" -> { isPaused = false; pausedMs += System.currentTimeMillis() - pauseStartMs; updateNotif("GPS ativo"); return START_STICKY }
            "RESET"  -> { totalKm = 0.0; startTimeMs = System.currentTimeMillis(); pausedMs = 0; lastLocation = null; lastFixTime = 0L }
        }

        createChannel()
        startTimeMs = System.currentTimeMillis()
        isRunning = true
        totalKm = 0.0
        pausedMs = 0

        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notif = buildNotif("GPS ativo — 0.0 km", pi)
        startForeground(NOTIF_ID, notif)

        // Inicia rastreamento nativo
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    4000L,  // a cada 4 segundos
                    8f,     // mínimo 8 metros
                    this
                )
            }
            // Fallback: network
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    10000L, 20f, this
                )
            }
        } catch (e: SecurityException) {
            e.printStackTrace()
        }

        return START_STICKY
    }

    override fun onLocationChanged(location: Location) {
        if (isPaused) return
        // Fix de baixa precisão (túnel, garagem, prédios altos) — ignora totalmente,
        // não usa como referência pra não distorcer a próxima medição.
        if (location.accuracy > 35f) return

        val prev = lastLocation
        if (prev != null) {
            val dist = prev.distanceTo(location) / 1000.0 // km
            val distM = dist * 1000.0
            // Piso de ruído: GPS "tremido" parado ou no trânsito não pode contar como deslocamento.
            val noiseFloorM = maxOf(8.0, location.accuracy.toDouble())
            if (distM >= noiseFloorM) {
                // Rejeita saltos com velocidade implausível (glitch/teleporte de GPS)
                val now = System.currentTimeMillis()
                val dtH = if (lastFixTime > 0) (now - lastFixTime) / 3600000.0 else -1.0
                val speedKmh = if (dtH > 0) dist / dtH else 0.0
                if (dtH <= 0 || speedKmh < 180) {
                    totalKm += dist
                    // Atualiza floating widget em tempo real
                    MainActivity.floatingWidget?.updateKm(totalKm)
                    // Atualiza notificação
                    updateNotif("GPS ativo — ${"%.1f".format(totalKm)} km")
                }
                // Só avança a referência quando o movimento foi validado — assim deslocamento
                // lento (trânsito parado andando aos poucos) ainda acumula corretamente.
                lastLocation = location
                lastFixTime = now
            }
        } else {
            lastLocation = location
            lastFixTime = System.currentTimeMillis()
        }
    }

    private fun updateNotif(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(NOTIF_ID, buildNotif(text, pi))
    }

    private fun buildNotif(text: String, pi: PendingIntent) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SmartMobi")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pi)
            .build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "SmartMobi GPS", NotificationManager.IMPORTANCE_LOW)
                        .apply { description = "Rastreamento de jornada" }
                )
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        try { locationManager.removeUpdates(this) } catch (e: Exception) {}
        super.onDestroy()
    }

    // Callbacks obrigatórios pre-API 29
    @Deprecated("") override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
    override fun onProviderEnabled(p: String) {}
    override fun onProviderDisabled(p: String) {}
}
