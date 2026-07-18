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

// ══════════════════════════════════════════════════════════════════
// Cenário de notificação: 2 perguntas sequenciais sem botão enviar
// ══════════════════════════════════════════════════════════════════
data class Pergunta(val tipo: String, val titulo: String, val opcoes: List<Pair<String,String>>)
data class Cenario(val nomeLocal: String, val p1: Pergunta, val p2: Pergunta)

class GpsService : Service(), LocationListener {

    companion object {
        const val CHANNEL_ID       = "smartmobi_gps"
        const val CHANNEL_ALERT_ID = "smartmobi_alert"
        const val NOTIF_ID         = 1
        const val NOTIF_ALERT_ID   = 2
        const val PREFS_NAME       = "SmartMobiPrefs"
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_USER_ID      = "user_id"
        // Estado pendente da 1ª pergunta (aguardando 2ª)
        // Estado persistido da sessão GPS — sobrevive ao kill do processo
        // pelo Android (START_STICKY reinicia o serviço, mas o companion
        // object volta zerado; estas chaves permitem retomar a jornada).
        const val KEY_GPS_RUNNING  = "gps_running"
        const val KEY_GPS_START    = "gps_start_ms"
        const val KEY_GPS_KM       = "gps_total_km"
        const val KEY_GPS_PAUSED   = "gps_paused_ms"
        const val KEY_GPS_SAVED_AT = "gps_saved_at"

        // Snapshot do km exato no instante em que a jornada cruza a meia-noite
        // (virada de dia) — usado pra dividir a jornada certinha entre os dois
        // dias sem depender de estimativa por tempo. -1.0 = não capturado ainda
        // nesta jornada (ou já consumido/limpo pelo app).
        const val KEY_GPS_KM_MIDNIGHT   = "gps_km_midnight"
        const val KEY_GPS_MIDNIGHT_DATE = "gps_midnight_date"

        // Limpa o estado salvo — chamado quando a jornada é encerrada de
        // verdade (stopFloating) ou resetada (RESET). Sem isso, uma jornada
        // NOVA herdaria km/start da anterior no restart do serviço.
        fun clearSavedState(ctx: Context) {
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_GPS_RUNNING, false)
                .remove(KEY_GPS_START).remove(KEY_GPS_KM)
                .remove(KEY_GPS_PAUSED).remove(KEY_GPS_SAVED_AT)
                .remove(KEY_GPS_KM_MIDNIGHT).remove(KEY_GPS_MIDNIGHT_DATE).apply()
        }

        // Limpa só o snapshot da meia-noite — chamado pelo JS depois que o app
        // já leu e aplicou a divisão (pra não reaplicar de novo se reabrir).
        fun clearMidnightSnapshot(ctx: Context) {
            kmAtMidnight = -1.0
            midnightSnapshotDate = ""
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .remove(KEY_GPS_KM_MIDNIGHT).remove(KEY_GPS_MIDNIGHT_DATE).apply()
        }

        const val KEY_PENDING_LOC  = "pending_loc"
        const val KEY_PENDING_TYPE = "pending_type"
        const val KEY_PENDING_VAL  = "pending_val"
        const val KEY_PENDING_LAT  = "pending_lat"
        const val KEY_PENDING_LON  = "pending_lon"

        const val SUPABASE_URL  = "https://jlsrpptslwfhmkvelaro.supabase.co"
        const val SUPABASE_ANON = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Impsc3JwcHRzbHdmaG1rdmVsYXJvIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzM4NjYxNzIsImV4cCI6MjA4OTQ0MjE3Mn0.4gD4dKx05QaOAAkY1gAx2HuH_CN31Xg3kkDMdvZ4kh0"

        // Locais especiais: lat, lon, raioM, tipo (0=aero, 1=term, 2=tur)
        val LOCAIS = listOf(
            doubleArrayOf(-22.8100, -43.2506, 800.0, 0.0),   // GIG
            doubleArrayOf(-22.9104, -43.1636, 400.0, 0.0),   // SDU
            doubleArrayOf(-22.8970, -43.1889, 300.0, 1.0),   // Rodoviária
            doubleArrayOf(-22.9519, -43.1661, 200.0, 2.0),   // Pão de Açúcar
            doubleArrayOf(-22.9519, -43.2105, 200.0, 2.0),   // Cristo
            doubleArrayOf(-22.9013, -43.1761, 150.0, 2.0),   // Museu do Amanhã
            doubleArrayOf(-22.9122, -43.2302, 500.0, 2.0),   // Maracanã
            doubleArrayOf(-22.9691, -43.1742, 100.0, 2.0),   // Jardim Botânico
            doubleArrayOf(-22.9146, -43.1765, 300.0, 2.0),   // Lapa / Arcos
            doubleArrayOf(-22.8037, -43.2499, 200.0, 2.0)    // Quinta da Boa Vista
        )

        fun detectarTipoLocal(lat: Double, lon: Double): Int {
            for (l in LOCAIS) {
                val dlat = Math.toRadians(l[0] - lat); val dlon = Math.toRadians(l[1] - lon)
                val a = Math.sin(dlat/2).let{it*it} + Math.cos(Math.toRadians(lat)) *
                    Math.cos(Math.toRadians(l[0])) * Math.sin(dlon/2).let{it*it}
                if (6371000 * 2 * Math.asin(Math.sqrt(a)) <= l[2]) return l[3].toInt()
            }
            return -1  // rua comum
        }

        fun escalaParaValor(n: Int) = when(n) {
            0,1 -> "Parado"; 2,3 -> "Lento"; else -> "Fluindo"
        }

        // ══ REGRAS DE CENÁRIO ══════════════════════════════════════════
        // Define as 2 perguntas baseado em: local, se está parado, horário
        fun escolherCenario(nomeLocal: String, tipoLocal: Int, isParado: Boolean, hora: Int): Cenario {

            val transitoP = Pergunta("transito", "Trânsito em $nomeLocal?",
                listOf("0🔴" to "Parado", "2🟠" to "Lento", "5🟢" to "Fluindo"))

            val demandaP = Pergunta("demanda", "Tocando em $nomeLocal?",
                listOf("Tocando" to "Tocando bem", "Esporádico" to "Esporádico", "Não toca" to "Não toca nada"))

            val filaP = Pergunta("fila", "Fila em $nomeLocal?",
                listOf("Vazia" to "Vazia", "Média" to "Média", "Grande" to "Grande"))

            val fiscP = Pergunta("fiscalizacao", "Fiscalização em $nomeLocal?",
                listOf("PM" to "PM", "Lei Seca" to "Lei Seca", "Nenhuma" to "Ausente"))

            val dinamP = Pergunta("dinamico", "Dinâmico em $nomeLocal?",
                listOf("+R\$10+" to "+R\$15", "+R\$5" to "+R\$5", "R\$0" to "R\$ 0"))

            val noite = hora in 21..23 || hora in 0..4

            return when {
                // ── PARADO 5min+ ──────────────────────────────────────────
                isParado && (tipoLocal == 0 || tipoLocal == 1) ->
                    // Aero/terminal parado: fila + demanda
                    Cenario(nomeLocal, filaP, demandaP)

                isParado ->
                    // Em qualquer lugar parado: demanda + dinâmico
                    Cenario(nomeLocal, demandaP, dinamP)

                // ── ANDANDO ───────────────────────────────────────────────
                tipoLocal == 0 || tipoLocal == 1 ->
                    // Aeroporto/terminal: fiscal + fila (sempre)
                    Cenario(nomeLocal, fiscP, filaP)

                tipoLocal == 2 ->
                    // Turístico: trânsito + fiscal
                    Cenario(nomeLocal, transitoP, fiscP)

                noite ->
                    // Noite: fiscal (Lei Seca) + demanda
                    Cenario(nomeLocal,
                        Pergunta("fiscalizacao", "Fiscal em $nomeLocal? (noite)",
                            listOf("Lei Seca" to "Lei Seca", "PM" to "PM", "Nenhuma" to "Ausente")),
                        demandaP)

                else ->
                    // Rua comum dia: trânsito + demanda
                    Cenario(nomeLocal, transitoP, demandaP)
            }
        }


        // ── Estado compartilhado com FloatingWidget/MainActivity ──────
        var totalKm      = 0.0
        var startTimeMs  = 0L
        var isRunning    = false
        var isPaused     = false
        var pausedMs     = 0L
        var pauseStartMs = 0L
        var lastGpsFixTime = 0L
        // km acumulado no instante exato da última virada de dia capturada
        // (00:00:00 local) durante a jornada atual, e a data (yyyy-MM-dd) do
        // dia que começou ali. -1.0/"" = nenhuma virada capturada ainda.
        var kmAtMidnight: Double = -1.0
        var midnightSnapshotDate: String = ""
        // Última posição válida conhecida — exposta pro AutoTripCapture
        // geocodificar (reverso) o endereço real de embarque/desembarque.
        // 0.0/0.0 = nenhum fix ainda.
        var lastLat: Double = 0.0
        var lastLng: Double = 0.0

        // Geocodificação reversa completa (rua + número + bairro), pro
        // AutoTripCapture comparar com o endereço que veio da tela. Mais
        // rica que geocodificarRua() (que só devolve o nome da rua, usado
        // no recurso de alertas) — roda numa thread já em background
        // (chamada de dentro de AutoTripCapture.push, que já é async).
        fun reverseGeocodeFull(lat: Double, lon: Double): String? {
            if (lat == 0.0 && lon == 0.0) return null
            return try {
                val url = URL("https://nominatim.openstreetmap.org/reverse?lat=$lat&lon=$lon&zoom=18&format=json&accept-language=pt&addressdetails=1")
                val conn = url.openConnection() as HttpURLConnection
                conn.setRequestProperty("User-Agent", "SmartMobi/1.0")
                conn.connectTimeout = 6000; conn.readTimeout = 6000
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                val addr = json.optJSONObject("address") ?: return json.optString("display_name").takeIf { it.isNotBlank() }
                val rua = addr.optString("road").takeIf { it.isNotBlank() }
                    ?: addr.optString("pedestrian").takeIf { it.isNotBlank() }
                val numero = addr.optString("house_number").takeIf { it.isNotBlank() }
                val bairro = addr.optString("suburb").takeIf { it.isNotBlank() }
                    ?: addr.optString("neighbourhood").takeIf { it.isNotBlank() }
                listOfNotNull(
                    if (rua != null && numero != null) "$rua, $numero" else rua,
                    bairro
                ).joinToString(" - ").takeIf { it.isNotBlank() }
                    ?: json.optString("display_name").takeIf { it.isNotBlank() }
            } catch (e: Exception) { null }
        }

        fun saveUserCredentials(ctx: Context, userId: String, accessToken: String) {
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(KEY_USER_ID, userId).putString(KEY_ACCESS_TOKEN, accessToken).apply()
        }
    }

    // Persiste o estado atual da sessão — barato (apply é assíncrono),
    // chamado a cada fix válido e nas transições de pausa.
    private fun saveState() {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_GPS_RUNNING, isRunning)
            .putLong(KEY_GPS_START, startTimeMs)
            .putLong(KEY_GPS_KM, java.lang.Double.doubleToRawLongBits(totalKm))
            .putLong(KEY_GPS_PAUSED, pausedMs)
            .putLong(KEY_GPS_SAVED_AT, System.currentTimeMillis())
            .apply()
    }

    private lateinit var locationManager: LocationManager
    private var lastLocation: Location? = null
    private var lastFixTime = 0L
    private val alertHandler = Handler(Looper.getMainLooper())
    private var lastAlertTime = 0L
    private val ALERT_INTERVAL_MS = 10 * 60 * 1000L

    // Rastreamento de velocidade para detectar parado
    private var stoppedSinceMs = 0L
    private val STOPPED_THRESHOLD_MS = 5 * 60 * 1000L
    private var lastSpeedKmh = 0.0

    // Ciclo simples para alternar entre perguntas
    private var cicloAlerta = 0

    private val checkRunnable = object : Runnable {
        override fun run() {
            if (isRunning && !isPaused && lastLocation != null) {
                Thread { verificarEDispararAlerta(lastLocation!!) }.start()
            }
            alertHandler.postDelayed(this, ALERT_INTERVAL_MS)
        }
    }

    // ── Lifecycle ───────────────────────────────────────────────────────
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "PAUSE"  -> { isPaused = true;  pauseStartMs = System.currentTimeMillis(); updateNotif("Pausado"); saveState(); return START_STICKY }
            "RESUME" -> { isPaused = false; pausedMs += System.currentTimeMillis() - pauseStartMs; updateNotif("GPS ativo"); saveState(); return START_STICKY }
            "RESET"  -> { totalKm = 0.0; startTimeMs = System.currentTimeMillis(); pausedMs = 0; lastLocation = null; lastFixTime = 0L; lastGpsFixTime = 0L; kmAtMidnight = -1.0; midnightSnapshotDate = ""; clearSavedState(this) }
            // JS já leu e aplicou a divisão da jornada — limpa o snapshot pra
            // não reaplicar de novo se o app reabrir antes da próxima meia-noite.
            "CLEAR_MIDNIGHT" -> { clearMidnightSnapshot(this); return START_STICKY }
        }
        createChannels()
        // O JS pode semear a sessão via extras (reanexar jornada em aberto):
        // startFloating(startMs, km) → EXTRA_START_MS / EXTRA_KM_BITS.
        val seedStart = intent?.getLongExtra("EXTRA_START_MS", 0L) ?: 0L
        val seedKmRaw = intent?.getLongExtra("EXTRA_KM_BITS", -1L) ?: -1L
        val seedKm = if (seedKmRaw >= 0) java.lang.Double.longBitsToDouble(seedKmRaw) else -1.0
        if (isRunning && seedStart > 0) {
            if (seedStart < startTimeMs - 60_000L) {
                // O serviço foi morto/reiniciado e está rodando com start novo,
                // mas o JS conhece o início REAL da jornada — readota, mantendo
                // o maior km conhecido.
                startTimeMs = seedStart
                if (seedKm >= 0 && seedKm > totalKm) totalKm = seedKm
            } else if (seedStart > startTimeMs + 60_000L) {
                // JS está iniciando jornada NOVA com o serviço ainda vivo de uma
                // anterior — zera o estado herdado (evita km órfão).
                startTimeMs = seedStart; totalKm = if (seedKm >= 0) seedKm else 0.0
                pausedMs = 0; lastLocation = null; lastFixTime = 0L
            } else if (seedKm >= 0 && seedKm > totalKm) {
                totalKm = seedKm
            }
            MainActivity.floatingWidget?.updateKm(totalKm)
            saveState()
        }
        if (!isRunning) {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val savedRunning = prefs.getBoolean(KEY_GPS_RUNNING, false)
            val savedStart   = prefs.getLong(KEY_GPS_START, 0L)
            val savedKm      = java.lang.Double.longBitsToDouble(prefs.getLong(KEY_GPS_KM, java.lang.Double.doubleToRawLongBits(0.0)))
            val savedPaused  = prefs.getLong(KEY_GPS_PAUSED, 0L)
            val savedAt      = prefs.getLong(KEY_GPS_SAVED_AT, 0L)
            val savedFresh   = savedRunning && savedStart > 0 &&
                               (System.currentTimeMillis() - savedAt) < 16 * 3600 * 1000L
            if (seedStart > 0) {
                // Semente explícita do JS. Se o estado salvo pertence à MESMA
                // jornada (starts a menos de 5 min de distância), preserva o
                // maior km — o serviço pode ter andado mais que o JS sabia.
                startTimeMs = seedStart
                totalKm = if (savedFresh && Math.abs(savedStart - seedStart) < 5 * 60 * 1000L)
                              maxOf(if (seedKm >= 0) seedKm else 0.0, savedKm)
                          else (if (seedKm >= 0) seedKm else 0.0)
                pausedMs = if (savedFresh && Math.abs(savedStart - seedStart) < 5 * 60 * 1000L) savedPaused else 0L
            } else if (savedFresh) {
                // Restart do Android (START_STICKY, intent nulo) com jornada em
                // aberto — RETOMA em vez de zerar. Este era o bug que resetava
                // o km depois de horas de uso.
                startTimeMs = savedStart; totalKm = savedKm; pausedMs = savedPaused
            } else {
                startTimeMs = System.currentTimeMillis(); totalKm = 0.0; pausedMs = 0
            }
            lastLocation = null; lastFixTime = 0L; lastGpsFixTime = 0L
        }
        isRunning = true
        saveState()
        // Restaura o snapshot da meia-noite salvo (se o serviço foi morto e
        // reiniciado pelo Android DEPOIS de capturar, mas ANTES do JS consumir).
        // Só restaura se ainda não tem um em memória — não sobrescreve um
        // snapshot mais novo capturado nesta mesma execução.
        if (kmAtMidnight < 0.0) {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val savedDate = prefs.getString(KEY_GPS_MIDNIGHT_DATE, "") ?: ""
            if (savedDate.isNotEmpty()) {
                kmAtMidnight = java.lang.Double.longBitsToDouble(prefs.getLong(KEY_GPS_KM_MIDNIGHT, -1L))
                midnightSnapshotDate = savedDate
            }
        }
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        startForeground(NOTIF_ID, buildNotif("GPS ativo — 0.0 km", pi))
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER))
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 4000L, 8f, this)
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 10000L, 20f, this)
        } catch (e: SecurityException) { e.printStackTrace() }
        alertHandler.postDelayed(checkRunnable, 5 * 60 * 1000L)
        scheduleMidnightCheck()
        return START_STICKY
    }

    // ── Snapshot de km na virada de dia (meia-noite local) ────────────────
    // Handler separado do checkRunnable (esse é de alerta de trânsito, com
    // intervalo fixo de 10min — meia-noite precisa de um alvo exato, não um
    // polling). Agenda o disparo pro instante exato do próximo 00:00:01
    // (1s de folga de segurança), captura o km ali, e já reagenda pro dia
    // seguinte — assim uma jornada de vários dias (teoricamente) continua
    // capturando toda virada, não só a primeira.
    private val midnightHandler = Handler(Looper.getMainLooper())
    private val midnightRunnable = Runnable {
        if (isRunning && !isPaused) captureMidnightSnapshot()
        scheduleMidnightCheck()
    }

    private fun scheduleMidnightCheck() {
        midnightHandler.removeCallbacks(midnightRunnable)
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 1); cal.set(Calendar.MILLISECOND, 0)
        val delay = cal.timeInMillis - System.currentTimeMillis()
        midnightHandler.postDelayed(midnightRunnable, delay)
    }

    private fun captureMidnightSnapshot() {
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        kmAtMidnight = totalKm
        midnightSnapshotDate = todayStr
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putLong(KEY_GPS_KM_MIDNIGHT, java.lang.Double.doubleToRawLongBits(kmAtMidnight))
            .putString(KEY_GPS_MIDNIGHT_DATE, midnightSnapshotDate)
            .apply()
    }

    override fun onLocationChanged(location: Location) {
        if (isPaused) return
        if (location.accuracy > 25f) return
        val now = System.currentTimeMillis()
        val isGps = location.provider == LocationManager.GPS_PROVIDER
        if (isGps) lastGpsFixTime = now
        if (!isGps && (lastGpsFixTime == 0L || now - lastGpsFixTime < 45000)) return
        lastLat = location.latitude; lastLng = location.longitude

        // Rastrear velocidade para detectar parado
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
                    saveState()
                }
                lastSpeedKmh = if (dtH > 0) speedKmh else lastSpeedKmh
                lastLocation = location; lastFixTime = now
            }
        } else {
            lastLocation = location; lastFixTime = System.currentTimeMillis()
        }

        // Detectar parado (< 3 km/h por tempo)
        val velAtual = location.speed * 3.6  // m/s → km/h
        if (velAtual < 3.0) {
            if (stoppedSinceMs == 0L) stoppedSinceMs = now
        } else {
            stoppedSinceMs = 0L  // andou → reseta contador
        }
    }

    // ── Lógica de alerta ─────────────────────────────────────────────────
    private fun verificarEDispararAlerta(location: Location) {
        try {
            if (System.currentTimeMillis() - lastAlertTime < ALERT_INTERVAL_MS) return
            val lat = location.latitude; val lon = location.longitude
            val tipoLocal = detectarTipoLocal(lat, lon)
            val nomeLocal = geocodificarRua(lat, lon) ?: return
            val isParado = stoppedSinceMs > 0 && (System.currentTimeMillis() - stoppedSinceMs) > STOPPED_THRESHOLD_MS
            val hora = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            val cenario = escolherCenario(nomeLocal, tipoLocal, isParado, hora)

            // Verificar se já há informe recente do tipo da 1ª pergunta
            if (verificarInformes(nomeLocal, cenario.p1.tipo)) return

            dispararPrimeiraPergunta(cenario, lat, lon)
            lastAlertTime = System.currentTimeMillis()
            cicloAlerta++
        } catch (e: Exception) { e.printStackTrace() }
    }

    // ── Pergunta 1: salva no broadcast, mostra notificação ─────────────
    private fun dispararPrimeiraPergunta(cenario: Cenario, lat: Double, lon: Double) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        fun makeBtn(label: String, value: String, code: Int): NotificationCompat.Action {
            val i = Intent(this, ReporteQuickReceiver::class.java).apply {
                putExtra("stage",     "1")
                putExtra("loc_name",  cenario.nomeLocal)
                putExtra("p1_type",   cenario.p1.tipo)
                putExtra("p1_value",  value)
                putExtra("p2_type",   cenario.p2.tipo)
                putExtra("p2_titulo", cenario.p2.titulo)
                putExtra("p2_opts",   cenario.p2.opcoes.map{"${it.first}||${it.second}"}.toTypedArray())
                putExtra("lat",       lat)
                putExtra("lon",       lon)
            }
            val pi = PendingIntent.getBroadcast(this, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            return NotificationCompat.Action.Builder(0, label, pi).build()
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ALERT_ID)
            .setContentTitle(cenario.p1.titulo)
            .setContentText("1 de 2 · Toque para responder")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(false)
            .setTimeoutAfter(15_000)

        // Adiciona até 3 botões das opções
        cenario.p1.opcoes.take(3).forEachIndexed { i, (lbl, val_) ->
            builder.addAction(makeBtn(lbl, val_, 100 + i))
        }

        nm.notify(NOTIF_ALERT_ID, builder.build())
    }

    private fun geocodificarRua(lat: Double, lon: Double): String? {
        return try {
            val url = URL("https://nominatim.openstreetmap.org/reverse?lat=$lat&lon=$lon&zoom=16&format=json&accept-language=pt")
            val conn = url.openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "SmartMobi/1.0")
            conn.connectTimeout = 6000
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val addr = json.optJSONObject("address")
            addr?.optString("road")?.takeIf { it.isNotBlank() }
                ?: addr?.optString("pedestrian")?.takeIf { it.isNotBlank() }
                ?: addr?.optString("suburb")
        } catch (e: Exception) { null }
    }

    private fun verificarInformes(nome: String, tipo: String): Boolean {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            val expiry = sdf.format(Date(System.currentTimeMillis() - 30 * 60 * 1000))
            val enc = nome.replace(" ", "%20")
            val url = URL("$SUPABASE_URL/rest/v1/informes?location_name=eq.$enc&param_type=eq.$tipo&is_active=eq.true&expires_at=gt.$expiry&select=id&limit=1")
            val conn = url.openConnection() as HttpURLConnection
            conn.setRequestProperty("apikey", SUPABASE_ANON)
            conn.setRequestProperty("Authorization", "Bearer $SUPABASE_ANON")
            conn.connectTimeout = 5000
            JSONArray(conn.inputStream.bufferedReader().readText()).length() > 0
        } catch (e: Exception) { true }
    }

    private fun updateNotif(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(NOTIF_ID, buildNotif(text, pi))
    }

    private fun buildNotif(text: String, pi: PendingIntent) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SmartMobi").setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true).setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pi).build()

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null)
                nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "SmartMobi GPS", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Rastreamento de jornada" })
            if (nm.getNotificationChannel(CHANNEL_ALERT_ID) == null)
                nm.createNotificationChannel(NotificationChannel(CHANNEL_ALERT_ID, "SmartMobi Alertas", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "Pedidos de informe"; enableVibration(true) })
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        // NÃO limpa o estado salvo aqui: onDestroy também roda quando o sistema
        // recicla o serviço. O encerramento real limpa via clearSavedState()
        // (chamado no stopGpsService do MainActivity). isRunning permanece true
        // nas prefs para o restart do START_STICKY retomar a jornada.
        isRunning = false; alertHandler.removeCallbacks(checkRunnable)
        midnightHandler.removeCallbacks(midnightRunnable)
        try { locationManager.removeUpdates(this) } catch (e: Exception) {}
        super.onDestroy()
    }
    @Deprecated("") override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
    override fun onProviderEnabled(p: String) {}
    override fun onProviderDisabled(p: String) {}


}

// ══════════════════════════════════════════════════════════════════
// Receiver: processa toque nos botões de ação rápida
// STAGE 1 → mostra 2ª pergunta na mesma notificação
// STAGE 2 → envia ambos os reportes e dispensa notificação
// ══════════════════════════════════════════════════════════════════
class ReporteQuickReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val stage = intent.getStringExtra("stage") ?: "1"

        if (stage == "1") {
            // ── 1ª pergunta respondida ─────────────────────────────────
            val locName = intent.getStringExtra("loc_name") ?: return
            val p1Type  = intent.getStringExtra("p1_type")  ?: return
            val p1Value = intent.getStringExtra("p1_value") ?: return
            val p2Type  = intent.getStringExtra("p2_type")  ?: return
            val p2Titulo= intent.getStringExtra("p2_titulo") ?: "Mais uma?"
            val p2Opts  = intent.getStringArrayExtra("p2_opts") ?: return
            val lat     = intent.getDoubleExtra("lat", 0.0)
            val lon     = intent.getDoubleExtra("lon", 0.0)

            // Envia a 1ª resposta imediatamente em background
            Thread { enviarSupabase(ctx, locName, p1Type, p1Value, lat, lon) }.start()

            // Atualiza a mesma notificação com a 2ª pergunta
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            fun makeBtn2(lbl: String, val_: String, code: Int): NotificationCompat.Action {
                val i2 = Intent(ctx, ReporteQuickReceiver::class.java).apply {
                    putExtra("stage",    "2")
                    putExtra("loc_name", locName)
                    putExtra("p2_type",  p2Type)
                    putExtra("p2_value", val_)
                    putExtra("lat", lat); putExtra("lon", lon)
                }
                val pi2 = PendingIntent.getBroadcast(ctx, code, i2, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                return NotificationCompat.Action.Builder(0, lbl, pi2).build()
            }

            val builder2 = NotificationCompat.Builder(ctx, GpsService.CHANNEL_ALERT_ID)
                .setContentTitle(p2Titulo)
                .setContentText("2 de 2 · Resposta enviada! Mais uma?")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .setTimeoutAfter(15_000)

            p2Opts.take(3).forEachIndexed { i, opt ->
                val parts = opt.split("||")
                val lbl = parts.getOrNull(0) ?: opt
                val value = parts.getOrNull(1) ?: opt
                builder2.addAction(makeBtn2(lbl, value, 200 + i))
            }

            nm.notify(GpsService.NOTIF_ALERT_ID, builder2.build())

        } else {
            // ── 2ª pergunta respondida → envia e fecha notificação ──────
            val locName  = intent.getStringExtra("loc_name")  ?: return
            val p2Type   = intent.getStringExtra("p2_type")   ?: return
            val p2Value  = intent.getStringExtra("p2_value")  ?: return
            val lat      = intent.getDoubleExtra("lat", 0.0)
            val lon      = intent.getDoubleExtra("lon", 0.0)

            Thread { enviarSupabase(ctx, locName, p2Type, p2Value, lat, lon) }.start()

            // Fecha a notificação automaticamente
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(GpsService.NOTIF_ALERT_ID)
        }
    }

    private fun enviarSupabase(ctx: Context, locName: String, paramType: String, paramValue: String, lat: Double, lon: Double) {
        try {
            val prefs     = ctx.getSharedPreferences(GpsService.PREFS_NAME, Context.MODE_PRIVATE)
            val userId    = prefs.getString(GpsService.KEY_USER_ID, null)
            val authToken = prefs.getString(GpsService.KEY_ACCESS_TOKEN, GpsService.SUPABASE_ANON)

            val expMin = when (paramType) {
                "dinamico" -> 15; "fila" -> 20; "demanda", "transito", "acidente" -> 30; else -> 60
            }
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            val body = JSONObject().apply {
                if (userId != null) put("user_id", userId)
                put("location_name", locName); put("location_type", "bairro")
                put("location_lat", lat); put("location_lng", lon)
                put("city", "rio"); put("param_type", paramType); put("param_value", paramValue)
                put("expires_at", sdf.format(Date(System.currentTimeMillis() + expMin * 60000)))
                put("votes_up", 1); put("votes_dn", 0); put("is_active", true)
            }.toString()

            val url  = URL("${GpsService.SUPABASE_URL}/rest/v1/informes")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("apikey", GpsService.SUPABASE_ANON)
            conn.setRequestProperty("Authorization", "Bearer ${authToken ?: GpsService.SUPABASE_ANON}")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Prefer", "return=minimal")
            conn.doOutput = true; conn.connectTimeout = 8000
            conn.outputStream.write(body.toByteArray())
            conn.responseCode
        } catch (e: Exception) { e.printStackTrace() }
    }
}
