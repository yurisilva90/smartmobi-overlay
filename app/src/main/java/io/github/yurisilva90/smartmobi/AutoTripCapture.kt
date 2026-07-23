package io.github.yurisilva90.smartmobi

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.thread

// ══════════════════════════════════════════════════════════════════
// Monta o registro de "corrida automática" durante todo o ciclo
// (aceite → buscar → embarque → corrida → fim) e só grava no Supabase
// (tabela auto_trips) uma vez, no final. Nunca fecha o registro na
// oferta — vai se retroalimentando com leituras melhores conforme a
// corrida acontece (endereço mais completo, nome do passageiro, etc),
// igual combinado: "mais completo vence", não "mais recente vence".
//
// Hook único de entrada: onStateTransition(), chamado pelo
// TripReaderService toda vez que o estado confirmado (online/buscar/
// corrida) muda de verdade (já passou pelo debounce). Km/tempo REAIS
// vêm sempre do GpsService.totalKm (fonte autoritativa), nunca de
// leitura de tela.
// ══════════════════════════════════════════════════════════════════
object AutoTripCapture {

    data class OfferSnapshot(
        val value: Double?,
        val dinamico: Double,
        val kmPickup: Double?,
        val kmTrip: Double?,
        val durPickupSec: Int?,
        val durTripSec: Int?,
        val origin: String?,
        val dest: String?,
        // NOVO (22/07/2026, cache de oferta pedido pelo Yuri): carimbo de
        // quando essa oferta foi vista pela última vez — é o que permite
        // invalidar oferta velha antes de grudar numa corrida errada (ver
        // OFFER_MAX_AGE_MS abaixo).
        val seenAt: Long = System.currentTimeMillis()
    )

    // Janela máxima entre ver a oferta e ela virar Buscar de verdade. Cobre
    // com folga tanto o aceite normal (segundos) quanto o caso de
    // sobreposição (aceitar a próxima corrida ainda dentro da atual — pode
    // levar alguns minutos até a anterior fechar). Acima disso, a oferta é
    // velha demais pra confiar — melhor não vincular valor nenhum do que
    // vincular o errado.
    private const val OFFER_MAX_AGE_MS = 10 * 60 * 1000L

    private data class Buffer(
        val platform: String,
        var offerValue: Double?,
        var offerDinamico: Double,
        var offerKmPickup: Double?,
        var offerKmTrip: Double?,
        var offerDurPickupSec: Int?,
        var offerDurTripSec: Int?,
        var originAddress: String?,
        var destAddress: String?,
        var passengerName: String? = null,
        var dinheiro: Boolean = false,
        val acceptedAt: Long,
        val pickupStartedAt: Long,
        val pickupStartKm: Double,
        var tripStartedAt: Long = 0L,
        var tripStartKm: Double = 0.0,
        var tripEndedAt: Long = 0L,
        var tripEndKm: Double = 0.0,
        // Posição real (GpsService) no instante do embarque e do
        // desembarque — geocodificada (reversa) só no push(), já em
        // background, pra comparar com o endereço que veio da tela.
        var gpsOriginLat: Double = 0.0,
        var gpsOriginLng: Double = 0.0,
        var gpsDestLat: Double = 0.0,
        var gpsDestLng: Double = 0.0
    )

    // Última oferta válida vista por plataforma, ANTES do aceite — vira o
    // ponto de partida do registro assim que a corrida é aceita (online→buscar).
    private val lastOfferByPlat = HashMap<String, OfferSnapshot>()

    @Volatile private var buffer: Buffer? = null

    // "mais completo vence": prioriza quem tem número de casa (\d{2,5}); em
    // empate, o mais longo. Uma leitura de OCR ruim (mais curta/sem número)
    // nunca substitui uma leitura boa só por ter chegado depois.
    private fun betterAddress(old: String?, new: String?): String? {
        if (new.isNullOrBlank()) return old
        if (old.isNullOrBlank()) return new
        val newHasNum = Regex("""\d{2,5}""").containsMatchIn(new)
        val oldHasNum = Regex("""\d{2,5}""").containsMatchIn(old)
        return when {
            newHasNum && !oldHasNum -> new
            !newHasNum && oldHasNum -> old
            new.trim().length > old.trim().length -> new
            else -> old
        }
    }

    // Chamado toda vez que um card de oferta válido é parseado (antes do
    // aceite). Funde com o que já tinha da MESMA oferta (mesmo valor) pelo
    // critério acima; troca de valor = oferta nova, substitui tudo.
    fun onOfferSeen(plat: String, snap: OfferSnapshot) {
        val existing = lastOfferByPlat[plat]
        lastOfferByPlat[plat] = if (existing == null || existing.value != snap.value) {
            snap
        } else {
            OfferSnapshot(
                value = snap.value ?: existing.value,
                dinamico = if (snap.dinamico > 0) snap.dinamico else existing.dinamico,
                kmPickup = snap.kmPickup ?: existing.kmPickup,
                kmTrip = snap.kmTrip ?: existing.kmTrip,
                durPickupSec = snap.durPickupSec ?: existing.durPickupSec,
                durTripSec = snap.durTripSec ?: existing.durTripSec,
                origin = betterAddress(existing.origin, snap.origin),
                dest = betterAddress(existing.dest, snap.dest),
                // Releitura da MESMA oferta = evidência de que ainda está na
                // tela agora — atualiza o carimbo pra essa oferta continuar
                // "fresca" enquanto o motorista está de fato olhando ela.
                seenAt = snap.seenAt
            )
        }
    }

    // Endereço mais completo lido DURANTE buscar/corrida (não só na oferta) —
    // é o que garante a retroalimentação: número de casa que só aparece na
    // tela de navegação, por exemplo, substitui o endereço mais genérico da
    // oferta.
    fun updateAddresses(plat: String, origin: String?, dest: String?) {
        val b = buffer ?: return
        if (b.platform != plat) return
        if (origin != null) b.originAddress = betterAddress(b.originAddress, origin)
        if (dest != null) b.destAddress = betterAddress(b.destAddress, dest)
    }

    // Nome do passageiro: one-shot — só preenche se ainda estava vazio (não
    // existe "nome mais completo", é uma substituição única quando aparece).
    fun setPassengerNameIfEmpty(plat: String, name: String?) {
        val b = buffer ?: return
        if (b.platform != plat) return
        if (b.passengerName.isNullOrBlank() && !name.isNullOrBlank()) b.passengerName = name.trim()
    }

    fun markCash(plat: String) {
        val b = buffer ?: return
        if (b.platform != plat) return
        b.dinheiro = true
    }

    fun onStateTransition(ctx: Context, plat: String, prev: String, next: String) {
        val now = System.currentTimeMillis()
        val km = GpsService.totalKm

        fun startBuffer(startKm: Double, alsoStartTrip: Boolean) {
            // Só usa a oferta em cache se ainda estiver dentro da janela de
            // validade — oferta velha demais (ver OFFER_MAX_AGE_MS) não
            // gruda em corrida nenhuma; fica sem dado de oferta (igual a
            // hoje quando nenhuma oferta foi vista), nunca com dado errado.
            val cached = lastOfferByPlat[plat]
            val offer = cached?.takeIf { System.currentTimeMillis() - it.seenAt <= OFFER_MAX_AGE_MS }
            // Consome (limpa) o cache nesse ponto, usada ou não — uma vez
            // que um "buscar" nasceu, essa oferta já cumpriu seu papel (ou
            // expirou); nunca deve poder grudar numa corrida futura.
            lastOfferByPlat.remove(plat)
            buffer = Buffer(
                platform = plat,
                offerValue = offer?.value,
                offerDinamico = offer?.dinamico ?: 0.0,
                offerKmPickup = offer?.kmPickup,
                offerKmTrip = offer?.kmTrip,
                offerDurPickupSec = offer?.durPickupSec,
                offerDurTripSec = offer?.durTripSec,
                originAddress = offer?.origin,
                destAddress = offer?.dest,
                acceptedAt = now,
                pickupStartedAt = now,
                pickupStartKm = startKm,
                tripStartedAt = if (alsoStartTrip) now else 0L,
                tripStartKm = if (alsoStartTrip) startKm else 0.0
            )
        }

        when {
            prev == "online" && next == "buscar" -> {
                startBuffer(km, alsoStartTrip = false)
            }
            prev == "buscar" && next == "corrida" -> {
                val b = buffer
                if (b != null && b.platform == plat) {
                    b.tripStartedAt = now
                    b.tripStartKm = km
                    b.gpsOriginLat = GpsService.lastLat
                    b.gpsOriginLng = GpsService.lastLng
                } else {
                    // Rede de segurança: chegou em "corrida" sem termos visto o
                    // "buscar" (debounce pode ter engolido o passo intermediário).
                    // Sem km de buscar pra comparar, mas não perde o resto.
                    startBuffer(km, alsoStartTrip = true)
                    buffer?.gpsOriginLat = GpsService.lastLat
                    buffer?.gpsOriginLng = GpsService.lastLng
                }
            }
            prev == "corrida" && next == "online" -> {
                val b = buffer
                buffer = null
                if (b != null && b.platform == plat) {
                    b.tripEndedAt = now
                    b.tripEndKm = km
                    b.gpsDestLat = GpsService.lastLat
                    b.gpsDestLng = GpsService.lastLng
                    push(ctx, b)
                }
            }
            prev == "buscar" && next == "online" -> {
                // Cancelado antes de embarcar — não é uma corrida, descarta.
                buffer = null
            }
            prev == "corrida" && next == "buscar" -> {
                // Overlap: próxima corrida aceita antes da anterior fechar de
                // vez (confirmado em log real). Fecha a anterior com o que já
                // tem (melhor esforço) e começa a nova do zero.
                val b = buffer
                if (b != null && b.platform == plat) {
                    b.tripEndedAt = now
                    b.tripEndKm = km
                    b.gpsDestLat = GpsService.lastLat
                    b.gpsDestLng = GpsService.lastLng
                    push(ctx, b)
                }
                startBuffer(km, alsoStartTrip = false)
            }
        }
    }

    // ── Validação por GPS — pedido do Yuri (23/07/2026) ──────────────────
    // Confirma se o endereço vindo da tela (oferta/navegação) bate com o
    // endereço geocodificado a partir da posição REAL do GPS no instante da
    // transição (embarque = buscar->corrida, desembarque = fim de corrida).
    // Compara só a rua (primeiro segmento antes da vírgula/hífen), sem
    // acento, sem prefixo tipo "rua"/"av" — mesmo espírito do blacklist de
    // endereço em TripReaderService, mas isolado aqui pra não acoplar os
    // dois arquivos por um regex.
    private val streetPrefixRe = Regex(
        """^(rua|r\.|avenida|av\.?|travessa|trav\.?|estrada|est\.?|alameda|al\.?|rodovia|rod\.?|""" +
        """pra[cç]a|p[cç]a\.?|largo|jardim|jd\.?|parque|pq\.?|vila|vl\.?|conjunto|cj\.?|""" +
        """loteamento|residencial|res\.?)\s+""",
        RegexOption.IGNORE_CASE
    )

    private fun normalizedStreet(addr: String?): String? {
        if (addr.isNullOrBlank()) return null
        val firstSegment = addr.split(",", " - ").firstOrNull()?.trim() ?: return null
        val noAccent = java.text.Normalizer.normalize(firstSegment, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "")
        val stripped = noAccent.replace(streetPrefixRe, "").trim().lowercase(Locale.getDefault())
        // Menos de 4 caracteres não é confiável pra comparar (ruído de OCR
        // vira falso match) — nesse caso, resultado é "não deu pra comparar".
        return stripped.take(12).takeIf { it.length >= 4 }
    }

    // Retorna null quando não dá pra comparar (endereço faltando de um dos
    // lados) — diferente de false (comparou e não bateu). Essa distinção
    // importa: "não sei" não é a mesma coisa que "sei que é diferente".
    private fun addressesLikelyMatch(screenAddr: String?, gpsAddr: String?): Boolean? {
        val a = normalizedStreet(screenAddr) ?: return null
        val b = normalizedStreet(gpsAddr) ?: return null
        return a == b || a.startsWith(b) || b.startsWith(a)
    }

    private fun push(ctx: Context, b: Buffer) {
        thread(isDaemon = true) {
            try {
                val prefs = ctx.getSharedPreferences(GpsService.PREFS_NAME, Context.MODE_PRIVATE)
                val userId = prefs.getString(GpsService.KEY_USER_ID, null) ?: return@thread

                val realKmPickup = (b.tripStartKm - b.pickupStartKm).coerceAtLeast(0.0)
                val realKmTrip = (b.tripEndKm - b.tripStartKm).coerceAtLeast(0.0)
                val status = if (b.offerValue != null && b.tripStartedAt > 0 && b.tripEndedAt > 0)
                    "confirmada" else "capturada"

                // Já estamos numa thread em background (isDaemon) — pode
                // bloquear aqui sem travar a leitura de tela. Endereço real,
                // pra comparar com o que veio da oferta/navegação (mesmo
                // espírito do km/tempo real x previsto).
                val gpsOriginAddress = GpsService.reverseGeocodeFull(b.gpsOriginLat, b.gpsOriginLng)
                val gpsDestAddress = GpsService.reverseGeocodeFull(b.gpsDestLat, b.gpsDestLng)

                // NOVO (23/07/2026, pedido do Yuri): confirma pelo GPS se a
                // oferta vinculada bate com o local físico real de embarque/
                // desembarque — sem isso, o app confia cegamente que a
                // última oferta vista era mesmo a da corrida que aconteceu.
                // Compara só o nome da rua (normalizado, sem acento, prefixo
                // "rua"/"av"/etc removido — mesmo espírito do blacklist de
                // endereço); não decide nada sozinho, só grava o resultado
                // pra dar visibilidade e validação financeira.
                val gpsMatchOrigin = addressesLikelyMatch(b.originAddress, gpsOriginAddress)
                val gpsMatchDest = addressesLikelyMatch(b.destAddress, gpsDestAddress)

                val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
                    .apply { timeZone = TimeZone.getTimeZone("UTC") }
                fun iso(ms: Long): Any = if (ms > 0) sdf.format(Date(ms)) else JSONObject.NULL

                val body = JSONObject().apply {
                    put("user_id", userId)
                    put("platform", if (b.platform == "UBER") "uber" else "99")
                    put("passenger_name", b.passengerName ?: JSONObject.NULL)
                    put("origin_address", b.originAddress ?: JSONObject.NULL)
                    put("dest_address", b.destAddress ?: JSONObject.NULL)
                    put("gps_origin_address", gpsOriginAddress ?: JSONObject.NULL)
                    put("gps_dest_address", gpsDestAddress ?: JSONObject.NULL)
                    put("gps_match_origin", gpsMatchOrigin?.let { it } ?: JSONObject.NULL)
                    put("gps_match_dest", gpsMatchDest?.let { it } ?: JSONObject.NULL)
                    put("offer_value", b.offerValue ?: JSONObject.NULL)
                    put("offer_dinamico", b.offerDinamico)
                    put("offer_km_pickup", b.offerKmPickup ?: JSONObject.NULL)
                    put("offer_km_trip", b.offerKmTrip ?: JSONObject.NULL)
                    put("offer_duration_pickup_sec", b.offerDurPickupSec ?: JSONObject.NULL)
                    put("offer_duration_trip_sec", b.offerDurTripSec ?: JSONObject.NULL)
                    put("real_km_pickup", realKmPickup)
                    put("real_km_trip", realKmTrip)
                    put("dinheiro", b.dinheiro)
                    put("accepted_at", iso(b.acceptedAt))
                    put("pickup_started_at", iso(b.pickupStartedAt))
                    put("trip_started_at", iso(b.tripStartedAt))
                    put("trip_ended_at", iso(b.tripEndedAt))
                    put("status", status)
                }

                val url = URL("${TripReaderService.SUPABASE_URL}/rest/v1/auto_trips")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 8000; conn.readTimeout = 8000
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("apikey", TripReaderService.SUPABASE_ANON)
                conn.setRequestProperty("Authorization", "Bearer ${TripReaderService.SUPABASE_ANON}")
                conn.setRequestProperty("Prefer", "return=minimal")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                conn.responseCode
                conn.disconnect()
            } catch (_: Exception) {
                // sem rede/erro — a corrida real não é perdida (o motorista já a
                // fez), só não vira registro automático desta vez.
            }
        }
    }
}
