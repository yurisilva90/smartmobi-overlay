package io.github.yurisilva90.smartmobi

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

class GpsService : Service(), LocationListener {

    companion object {
        const val CHANNEL_ID       = "smartmobi_gps"
        const val CHANNEL_ALERT_ID = "smartmobi_alert"
        const val NOTIF_ID         = 1
        const val NOTIF_ALERT_ID   = 2
        const val PREFS_NAME       = "SmartMobiPrefs"
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_USER_ID      = "user_id"
        const val SUPABASE_URL     = "https://jlsrpptslwfhmkvelaro.supabase.co"
        const val SUPABASE_ANON    = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Impsc3JwcHRzbHdmaG1rdmVsYXJvIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzM4NjYxNzIsImV4cCI6MjA4OTQ0MjE3Mn0.4gD4dKx05QaOAAkY1gAx2HuH_CN31Xg3kkDMdvZ4kh0"

        // Locais especiais: lat, lon, raio(m), tipo ("aero"/"term"/"tur")
        val LOCAIS_ESPECIAIS = listOf(
            doubleArrayOf(-22.8100, -43.2506, 800.0, 0.0),  // GIG (aero=0)
            doubleArrayOf(-22.9104, -43.1636, 400.0, 0.0),  // SDU (aero=0)
            doubleArrayOf(-22.8970, -43.1889, 300.0, 1.0),  // Rodoviária (term=1)
            doubleArrayOf(-22.9519, -43.1661, 200.0, 2.0),  // Pão de Açúcar (tur=2)
            doubleArrayOf(-22.9519, -43.2105, 200.0, 2.0),  // Cristo Redentor (tur=2)
            doubleArrayOf(-22.8037, -43.2499, 200.0, 2.0),  // Quinta da Boa Vista (tur=2)
            doubleArrayOf(-22.9013, -43.1761, 150.0, 2.0),  // Museu do Amanhã (tur=2)
            doubleArrayOf(-22.9691, -43.1742, 100.0, 2.0),  // Jardim Botânico (tur=2)
            doubleArrayOf(-22.9122, -43.2302, 500.0, 2.0),  // Maracanã (tur=2)
            doubleArrayOf(-22.9146, -43.1765, 300.0, 2.0)   // Lapa/Arcos (tur=2)
        )
        // tipo 0=aero, 1=term, 2=tur, -1=rua comum

        fun detectarLocalEspecial(lat: Double, lon: Double): Int {
            for (local in LOCAIS_ESPECIAIS) {
                val dlat = Math.toRadians(local[0] - lat)
                val dlon = Math.toRadians(local[1] - lon)
                val a = Math.sin(dlat/2).let { it*it } +
                    Math.cos(Math.toRadians(lat)) * Math.cos(Math.toRadians(local[0])) *
                    Math.sin(dlon/2).let { it*it }
                val distM = 6371000 * 2 * Math.asin(Math.sqrt(a))
                if (distM <= local[2]) return local[3].toInt()
            }
            return -1  // rua comum
        }

        // Converte número 0-5 → texto param_value consistente com o app
        fun escalaParaValor(n: Int) = when(n) {
            0 -> "Parado"; 1 -> "Parado"; 2 -> "Lento"
            3 -> "Lento";  4 -> "Fluindo"; 5 -> "Fluindo"
            else -> "Fluindo"
        }

        // Estado compartilhado com MainActivity/FloatingWidget
        var totalKm      = 0.0
        var startTimeMs  = 0L
        var isRunning    = false
        var isPaused     = false
        var pausedMs     = 0L
        var pauseStartMs = 0L
        var lastGpsFixTime = 0L

        // Salva credenciais do usuário chamado pelo bridge JS
        fun saveUserCredentials(ctx: Context, userId: String, accessToken: String) {
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(KEY_USER_ID, userId)
                .putString(KEY_ACCESS_TOKEN, accessToken)
                .apply()
        }
    }

    private lateinit var locationManager: LocationManager
    private var lastLocation: Location? = null
    private var lastFixTime: Long = 0L
    private val alertHandler = Handler(Looper.getMainLooper())
    private var lastAlertTime = 0L
    private val ALERT_INTERVAL_MS = 10 * 60 * 1000L  // 10 minutos

    // ── Runnable: verifica a cada 10min se há informes na área ───────────────
    private val checkInformesRunnable = object : Runnable {
        override fun run() {
            if (isRunning && !isPaused && lastLocation != null) {
                Thread { verificarEDispararAlerta(lastLocation!!) }.start()
            }
            alertHandler.postDelayed(this, ALERT_INTERVAL_MS)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "PAUSE"  -> { isPaused = true;  pauseStartMs = System.currentTimeMillis(); updateNotif("Pausado"); return START_STICKY }
            "RESUME" -> { isPaused = false; pausedMs += System.currentTimeMillis() - pauseStartMs; updateNotif("GPS ativo"); return START_STICKY }
            "RESET"  -> { totalKm = 0.0; startTimeMs = System.currentTimeMillis(); pausedMs = 0; lastLocation = null; lastFixTime = 0L; lastGpsFixTime = 0L }
        }

        createChannels()
        if (!isRunning) {
            startTimeMs = System.currentTimeMillis()
            totalKm = 0.0
            pausedMs = 0
            lastLocation = null
            lastFixTime = 0L
            lastGpsFixTime = 0L
        }
        isRunning = true

        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        startForeground(NOTIF_ID, buildNotif("GPS ativo — 0.0 km", pi))

        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER))
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 4000L, 8f, this)
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 10000L, 20f, this)
        } catch (e: SecurityException) { e.printStackTrace() }

        // Inicia monitor de informes (começa após 5 min)
        alertHandler.postDelayed(checkInformesRunnable, 5 * 60 * 1000L)

        return START_STICKY
    }

    override fun onLocationChanged(location: Location) {
        if (isPaused) return
        if (location.accuracy > 25f) return
        val now = System.currentTimeMillis()
        val isGps = location.provider == LocationManager.GPS_PROVIDER
        if (isGps) lastGpsFixTime = now
        if (!isGps && (lastGpsFixTime == 0L || now - lastGpsFixTime < 45000)) return
        val prev = lastLocation
        if (prev != null) {
            val dist = prev.distanceTo(location) / 1000.0
            val distM = dist * 1000.0
            val noiseFloorM = maxOf(10.0, location.accuracy.toDouble())
            if (distM >= noiseFloorM) {
                val dtH = if (lastFixTime > 0) (now - lastFixTime) / 3600000.0 else -1.0
                val speedKmh = if (dtH > 0) dist / dtH else 0.0
                if (dtH <= 0 || speedKmh < 180) {
                    totalKm += dist
                    MainActivity.floatingWidget?.updateKm(totalKm)
                    updateNotif("GPS ativo — ${"%.1f".format(totalKm)} km")
                }
                lastLocation = location
                lastFixTime = now
            }
        } else {
            lastLocation = location
            lastFixTime = System.currentTimeMillis()
        }
    }

    // ── Ciclo de tipos de pergunta ────────────────────────────────────────────
    private var cicloTipo = 0  // 0=transito, 1=demanda, 2=dinamico

    // ── Verifica Supabase e dispara alerta se não tiver informes recentes ─────
    private fun verificarEDispararAlerta(location: Location) {
        try {
            if (System.currentTimeMillis() - lastAlertTime < ALERT_INTERVAL_MS) return

            val lat = location.latitude; val lon = location.longitude
            val tipoLocal = detectarLocalEspecial(lat, lon)

            // Para locais especiais, nome do local; para ruas, nome da rua via Nominatim
            val nomeLoc = geocodificarRua(lat, lon) ?: return

            // Lógica de pergunta por tipo de local:
            // aero/term (0,1): SEMPRE fiscalização (ciclo entre fisc e fila)
            // tur (2): trânsito obrigatório + fiscalização opcional (alterna)
            // rua (-1): ciclo transito→demanda→dinamico
            val (paramTipo, modalTipo) = when {
                tipoLocal == 0 || tipoLocal == 1 -> {
                    // Aeroporto/Terminal: alterna fiscal e fila
                    if (cicloTipo % 2 == 0) Pair("fiscalizacao", 10) else Pair("fila", 11)
                }
                tipoLocal == 2 -> {
                    // Turístico: trânsito sempre, fiscal no ciclo par
                    if (cicloTipo % 2 == 0) Pair("transito", 20) else Pair("fiscalizacao", 21)
                }
                else -> when (cicloTipo % 3) {
                    0 -> Pair("transito", 0)
                    1 -> Pair("demanda", 1)
                    else -> Pair("dinamico", 2)
                }
            }

            val temInforme = verificarInformes(nomeLoc, paramTipo)
            if (!temInforme) {
                dispararAlertaReporte(nomeLoc, lat, lon, modalTipo)
                lastAlertTime = System.currentTimeMillis()
                cicloTipo++
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun geocodificarRua(lat: Double, lon: Double): String? {
        return try {
            // zoom=16 para pegar a rua, não o bairro
            val url = URL("https://nominatim.openstreetmap.org/reverse?lat=$lat&lon=$lon&zoom=16&format=json&accept-language=pt")
            val conn = url.openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "SmartMobi/1.0")
            conn.connectTimeout = 6000
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val addr = json.optJSONObject("address")
            // Preferir nome da rua, cair no bairro só se não tiver rua
            addr?.optString("road")?.takeIf { it.isNotBlank() }
                ?: addr?.optString("pedestrian")?.takeIf { it.isNotBlank() }
                ?: addr?.optString("suburb")
                ?: addr?.optString("neighbourhood")
        } catch (e: Exception) { null }
    }

    private fun verificarInformes(nomeRua: String, paramTipo: String = "transito"): Boolean {
        return try {
            val expiry = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(Date(System.currentTimeMillis() - 30 * 60 * 1000))
            val encodedNome = nomeRua.replace(" ", "%20")
            val url = URL("$SUPABASE_URL/rest/v1/informes?location_name=eq.$encodedNome&param_type=eq.$paramTipo&is_active=eq.true&expires_at=gt.$expiry&select=id&limit=1")
            val conn = url.openConnection() as HttpURLConnection
            conn.setRequestProperty("apikey", SUPABASE_ANON)
            conn.setRequestProperty("Authorization", "Bearer $SUPABASE_ANON")
            conn.connectTimeout = 5000
            val text = conn.inputStream.bufferedReader().readText()
            JSONArray(text).length() > 0
        } catch (e: Exception) { true } // em caso de erro, não incomoda o motorista
    }

    private fun dispararAlertaReporte(nomeRua: String, lat: Double, lon: Double, tipo: Int) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        fun makeAction(label: String, paramType: String, valor: String, code: Int): NotificationCompat.Action {
            val i = Intent(this, ReporteQuickReceiver::class.java).apply {
                putExtra("location_name", nomeRua)
                putExtra("param_type", paramType)
                putExtra("param_value", valor)
                putExtra("lat", lat)
                putExtra("lon", lon)
            }
            val pi = PendingIntent.getBroadcast(this, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            return NotificationCompat.Action.Builder(0, label, pi).build()
        }

        val (titulo, subtitulo, a1, a2, a3) = when (tipo) {
            0 -> listOf( // trânsito rua: 3 pontos da escala
                "Trânsito em $nomeRua?",
                "0 = parado  ·  5 = fluindo",
                makeAction("0 🔴", "transito", "Parado",  101),
                makeAction("2 🟠", "transito", "Lento",   102),
                makeAction("5 🟢", "transito", "Fluindo", 103)
            )
            1 -> listOf( // demanda
                "Demanda em $nomeRua?",
                "Como estão os chamados agora?",
                makeAction("📲 Tocando",    "demanda", "Tocando bem",   201),
                makeAction("💤 Esporádico", "demanda", "Esporádico",    202),
                makeAction("❌ Não toca",   "demanda", "Não toca nada", 203)
            )
            2 -> listOf( // dinâmico
                "Dinâmico em $nomeRua?",
                "Qual o valor agora?",
                makeAction("⚡ +R\$10+", "dinamico", "+R\$15", 301),
                makeAction("⚡ +R\$5",   "dinamico", "+R\$5",  302),
                makeAction("⚡ R\$ 0",   "dinamico", "R\$ 0",  303)
            )
            10 -> listOf( // aeroporto/term: fiscalização
                "Fiscalização no terminal?",
                "Há alguma abordagem agora?",
                makeAction("🚔 PM",       "fiscalizacao", "PM",       401),
                makeAction("🚔 GM",       "fiscalizacao", "GM",       402),
                makeAction("🚫 Nenhuma",  "fiscalizacao", "Ausente",  403)
            )
            11 -> listOf( // aeroporto/term: fila
                "Como está a fila?",
                "No desembarque/terminal agora",
                makeAction("✅ Vazia",   "fila", "Vazia",   501),
                makeAction("⚠️ Média",   "fila", "Média",   502),
                makeAction("🔴 Grande",  "fila", "Grande",  503)
            )
            20 -> listOf( // turístico: trânsito
                "Trânsito em $nomeRua?",
                "0 = parado  ·  5 = fluindo",
                makeAction("0 🔴", "transito", "Parado",  601),
                makeAction("2 🟠", "transito", "Lento",   602),
                makeAction("5 🟢", "transito", "Fluindo", 603)
            )
            else -> listOf( // turístico: fiscal
                "Fiscalização no local?",
                "Há abordagem aqui agora?",
                makeAction("🚔 PM",      "fiscalizacao", "PM",      701),
                makeAction("🚫 Lei Seca","fiscalizacao", "Lei Seca",702),
                makeAction("✅ Nenhuma", "fiscalizacao", "Ausente", 703)
            )
        }

        val notif = NotificationCompat.Builder(this, CHANNEL_ALERT_ID)
            .setContentTitle(titulo as String)
            .setContentText(subtitulo as String)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setTimeoutAfter(12_000)
            .addAction(a1 as NotificationCompat.Action)
            .addAction(a2 as NotificationCompat.Action)
            .addAction(a3 as NotificationCompat.Action)
            .setContentIntent(
                PendingIntent.getActivity(this, 0,
                    Intent(this, MainActivity::class.java)
                        .putExtra("open_informes", true)
                        .putExtra("location", nomeRua),
                    PendingIntent.FLAG_IMMUTABLE)
            )
            .build()

        nm.notify(NOTIF_ALERT_ID, notif)
    }

    // ── Notificação persistente (GPS) ─────────────────────────────────────────
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

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            // Canal GPS (persistente, baixa importância)
            if (nm.getNotificationChannel(CHANNEL_ID) == null)
                nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "SmartMobi GPS", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Rastreamento de jornada" })
            // Canal Alertas (heads-up, alta importância)
            if (nm.getNotificationChannel(CHANNEL_ALERT_ID) == null)
                nm.createNotificationChannel(NotificationChannel(CHANNEL_ALERT_ID, "SmartMobi Alertas", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "Pedidos de informe e novidades"; enableVibration(true) })
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        alertHandler.removeCallbacks(checkInformesRunnable)
        try { locationManager.removeUpdates(this) } catch (e: Exception) {}
        super.onDestroy()
    }

    @Deprecated("") override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
    override fun onProviderEnabled(p: String) {}
    override fun onProviderDisabled(p: String) {}
}

// ── BroadcastReceiver: toque nos botões da notificação de alerta ─────────────
class ReporteQuickReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val nome   = intent.getStringExtra("location_name") ?: return
        val valor  = intent.getStringExtra("param_value")   ?: return
        val lat    = intent.getDoubleExtra("lat", 0.0)
        val lon    = intent.getDoubleExtra("lon", 0.0)

        Thread {
            try {
                val prefs      = ctx.getSharedPreferences(GpsService.PREFS_NAME, Context.MODE_PRIVATE)
                val userId     = prefs.getString(GpsService.KEY_USER_ID, null)
                val authToken  = prefs.getString(GpsService.KEY_ACCESS_TOKEN, GpsService.SUPABASE_ANON)

                // Expira em 30 minutos
                val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                val expiresAt = sdf.format(Date(System.currentTimeMillis() + 30 * 60 * 1000))

                val body = JSONObject().apply {
                    if (userId != null) put("user_id", userId)
                    put("location_name", nome)
                    put("location_type", "bairro")
                    put("location_lat", lat)
                    put("location_lng", lon)
                    put("city", "rio")
                    put("param_type", intent.getStringExtra("param_type") ?: "transito")
                    put("param_value", valor)
                    put("expires_at", expiresAt)
                    put("votes_up", 1)
                    put("votes_dn", 0)
                    put("is_active", true)
                }.toString()

                val url  = URL("${GpsService.SUPABASE_URL}/rest/v1/informes")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("apikey", GpsService.SUPABASE_ANON)
                conn.setRequestProperty("Authorization", "Bearer ${authToken ?: GpsService.SUPABASE_ANON}")
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Prefer", "return=minimal")
                conn.doOutput = true
                conn.connectTimeout = 8000
                conn.outputStream.write(body.toByteArray())
                conn.responseCode // dispara a requisição
            } catch (e: Exception) { e.printStackTrace() }
        }.start()
    }
}
