package io.github.yurisilva90.smartmobi

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class GpsService : Service() {

    companion object {
        const val CHANNEL_ID = "smartmobi_gps"
        const val NOTIF_ID   = 1
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SmartMobi")
            .setContentText("GPS ativo — jornada em andamento")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(
                PendingIntent.getActivity(this, 0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE)
            )
            .build()
        startForeground(NOTIF_ID, notif)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "SmartMobi GPS",
                NotificationManager.IMPORTANCE_LOW)
            ch.description = "Mantém o GPS ativo durante a jornada"
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }
}
