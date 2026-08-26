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
        val multiplicador: Double? = null,
        val kmPickup: Double?,
        val kmTrip: Double?,
        val durPickupSec: Int?,
        val durTripSec: Int?,
        val origin: String?,
        val dest: String?,
        val stopCount: Int = 0,
        val stopAddresses: List<String> = emptyList(),
        // NOVO (22/07/2026, cache de oferta pedido pelo Yuri): carimbo de
        // quando essa oferta foi vista pela última vez — é o que permite
        // invalidar oferta velha antes de grudar numa corrida errada (ver
        // OFFER_MAX_AGE_NORMAL_MS / OFFER_MAX_AGE_OVERLAP_MS abaixo).
        val seenAt: Long = System.currentTimeMillis()
    )

    // Janela curta — aceite normal: oferta→aceite→buscar é questão de
    // segundos. 30s já dá folga generosa pra qualquer atraso de leitura
    // sem deixar uma oferta de minutos atrás grudar por engano.
    private const val OFFER_MAX_AGE_NORMAL_MS = 30 * 1000L

    // Janela longa — só pro caso de sobreposição (aceitar a próxima corrida
    // ainda dentro da atual): aqui o intervalo real entre ver a oferta nova
    // e a corrida anterior fechar pode ser de vários minutos.
    private const val OFFER_MAX_AGE_OVERLAP_MS = 10 * 60 * 1000L

    // CONFIRMADO EM LOG REAL (16/08/2026, corrida das 01:37 do dia 12/08):
    // uma leitura ISOLADA de "buscar" apareceu 6s depois de já estar
    // firmemente em "corrida" (a leitura seguinte já voltou a "corrida"
    // normalmente) — ruído pontual de OCR, não um motorista de verdade
    // saindo da corrida. Isso disparou o tratamento de overlap (corrida
    // fechada + nova corrida aberta do zero), criando um registro fantasma
    // com a mesma origem/valor da corrida real, poucos segundos de duração
    // e ~0km rodados. Corrida de verdade nunca fecha em menos de 1 minuto
    // depois do embarque — abaixo desse tempo, trata "corrida"→"buscar"
    // como ruído e ignora a transição (mantém o buffer da corrida atual
    // intacto) em vez de fechar+reabrir.
    private const val MIN_RIDE_BEFORE_OVERLAP_MS = 60 * 1000L

    private data class Buffer(
        val platform: String,
        var offerValue: Double?,
        var offerDinamico: Double,
        var offerMultiplicador: Double?,
        var offerKmPickup: Double?,
        var offerKmTrip: Double?,
        var offerDurPickupSec: Int?,
        var offerDurTripSec: Int?,
        var originAddress: String?,
        var destAddress: String?,
        var stopCount: Int = 0,
        var stopAddresses: List<String> = emptyList(),
        var passengerName: String? = null,
        var dinheiro: Boolean = false,
        val acceptedAt: Long,
        val pickupStartedAt: Long,
        val pickupStartKm: Double,
        var pickupArrivedAt: Long = 0L,
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

    // v1.3.21 — captura automática 100% separada por plataforma.
    // Uber e 99 podem ficar ativos ao mesmo tempo; uma transição da Uber nunca
    // pode apagar ou substituir a corrida em andamento da 99 (e vice-versa).
    private val buffersByPlat = HashMap<String, Buffer>()

    // Snapshot leve para a tela Jornada. Não grava nada e não altera a
    // máquina de estados: apenas expõe os marcos que o AutoTripCapture já
    // mantém em memória, junto com o km autoritativo do GpsService.
    fun liveStateJson(): String {
        val b = buffersByPlat.values.firstOrNull { it.tripStartedAt > 0L }
            ?: buffersByPlat.values.firstOrNull()
        return JSONObject().apply {
            put("active", b != null)
            put("state", TripReaderService.confirmedTripSubState)
            put("nowMs", System.currentTimeMillis())
            put("gpsKm", GpsService.totalKm)
            put("gpsRunning", GpsService.isRunning)
            if (b != null) {
                put("platform", b.platform.lowercase(Locale.getDefault()))
                put("offerValue", b.offerValue ?: JSONObject.NULL)
                put("acceptedAt", b.acceptedAt)
                put("pickupStartedAt", b.pickupStartedAt)
                put("pickupStartKm", b.pickupStartKm)
                put("pickupArrivedAt", b.pickupArrivedAt)
                val waitEnd = if (b.tripStartedAt > 0) b.tripStartedAt else System.currentTimeMillis()
                put("passengerWaitSec", if (b.pickupArrivedAt > 0 && waitEnd >= b.pickupArrivedAt) ((waitEnd - b.pickupArrivedAt) / 1000L).toInt() else JSONObject.NULL)
                put("tripStartedAt", b.tripStartedAt)
                put("tripStartKm", b.tripStartKm)
            }
        }.toString()
    }

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

    // Janela de "esperar mais um pouco" antes de considerar uma oferta
    // parada há tempo como recusada/expirada — evita logar cedo demais uma
    // oferta que ainda está sendo decidida.
    private const val OFFER_STALE_MS = 45 * 1000L

    // Grava UMA oferta recusada/expirada (pedido do Yuri, 24/07/2026) — só
    // chamada quando temos certeza de que ela NÃO virou corrida: ou foi
    // substituída por uma oferta diferente sem nunca ter sido aceita, ou
    // ficou tempo demais na tela sem novidade nenhuma (flushStaleOffers).
    // Nunca é chamada no caminho de aceite (startBuffer já consome/remove
    // do cache antes disso), então tudo que cai aqui é seguramente "não
    // virou corrida" — sem precisar comparar com auto_trips depois.
    private fun logOfferSeen(ctx: Context, plat: String, snap: OfferSnapshot) {
        thread(isDaemon = true) {
            try {
                val prefs = ctx.getSharedPreferences(GpsService.PREFS_NAME, Context.MODE_PRIVATE)
                val userId = prefs.getString(GpsService.KEY_USER_ID, null) ?: return@thread
                val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
                    .apply { timeZone = TimeZone.getTimeZone("UTC") }
                val body = JSONObject().apply {
                    put("user_id", userId)
                    put("platform", if (plat == "UBER") "uber" else "99")
                    put("offer_value", snap.value ?: JSONObject.NULL)
                    put("offer_dinamico", snap.dinamico)
                    put("offer_km_pickup", snap.kmPickup ?: JSONObject.NULL)
                    put("offer_km_trip", snap.kmTrip ?: JSONObject.NULL)
                    put("offer_duration_pickup_sec", snap.durPickupSec ?: JSONObject.NULL)
                    put("offer_duration_trip_sec", snap.durTripSec ?: JSONObject.NULL)
                    put("origin_address", snap.origin ?: JSONObject.NULL)
                    put("dest_address", snap.dest ?: JSONObject.NULL)
                    put("stop_count", snap.stopCount)
                    put("stop_addresses", org.json.JSONArray(snap.stopAddresses))
                    put("seen_at", sdf.format(Date(snap.seenAt)))
                }
                val url = URL("${TripReaderService.SUPABASE_URL}/rest/v1/declined_offers")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 8000; conn.readTimeout = 8000
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("apikey", TripReaderService.SUPABASE_ANON)
                val authToken = prefs.getString(GpsService.KEY_ACCESS_TOKEN, null) ?: TripReaderService.SUPABASE_ANON
                conn.setRequestProperty("Authorization", "Bearer $authToken")
                conn.setRequestProperty("Prefer", "return=minimal")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                conn.responseCode
                conn.disconnect()
            } catch (_: Exception) {}
        }
    }

    // Chamado periodicamente (TripReaderService) — oferta que ficou parada
    // no cache por tempo demais sem nada acontecer (nem aceite, nem oferta
    // nova substituindo) também conta como recusada/expirada.
    fun flushStaleOffers(ctx: Context) {
        val now = System.currentTimeMillis()
        // Se já existe uma corrida em andamento nessa plataforma, qualquer
        // nova oferta vista pode ser justamente a PRÓXIMA corrida (overlap).
        // Não podemos apagá-la com o timeout normal de 45s: a 99 pode oferecer
        // a próxima viagem vários minutos antes da atual terminar. Mantém a
        // mesma janela de 10min usada por corrida->buscar. Fora de corrida,
        // continua usando 45s para não grudar oferta velha em aceite normal.
        val stale = lastOfferByPlat.filter { (plat, snap) ->
            val current = buffersByPlat[plat]
            val overlapCandidate = current != null && current.tripStartedAt > 0L && current.tripEndedAt == 0L
            val maxAge = if (overlapCandidate) OFFER_MAX_AGE_OVERLAP_MS else OFFER_STALE_MS
            now - snap.seenAt > maxAge
        }
        stale.forEach { (plat, snap) ->
            logOfferSeen(ctx, plat, snap)
            lastOfferByPlat.remove(plat)
        }
    }

    // Chamado toda vez que um card de oferta válido é parseado (antes do
    // aceite). Funde com o que já tinha da MESMA oferta (mesmo valor) pelo
    // critério acima; troca de valor = oferta nova, substitui tudo.
    fun onOfferSeen(ctx: Context, plat: String, snap: OfferSnapshot) {
        val existing = lastOfferByPlat[plat]
        // CORRIGIDO (16/08/2026, confirmado em log real — 35 corridas do 99
        // com valor errado): antes, QUALQUER diferença de valor entre duas
        // leituras já bastava pra tratar como "oferta nova", descartando
        // por completo a leitura anterior (que podia ser a boa) e ficando
        // só com a nova (que podia ser a ruidosa — ex: km não leu nessa
        // passada, e o fallback de valor pegou "R$2,33 Tarifa base
        // dinâmica" em vez do "R$14,70" real). Endereço não muda de uma
        // leitura pra outra da MESMA oferta — só quando é oferta de
        // verdade diferente. Agora só considera "oferta diferente" quando
        // o valor E o endereço (rua) mudam junto; se só o valor mudou,
        // trata como releitura ruidosa da mesma oferta e faz merge normal
        // (mantendo o que já tinha de bom).
        val sameAddress = existing != null && (
            (existing.origin == null && snap.origin == null) ||
            (normalizedStreet(existing.origin) != null &&
                normalizedStreet(existing.origin) == normalizedStreet(snap.origin))
        )
        val isReallyDifferentOffer = existing != null && existing.value != snap.value && !sameAddress
        if (isReallyDifferentOffer) {
            // Oferta diferente chegou por cima de uma que nunca foi
            // aceita (aceite já teria removido do cache antes disso) —
            // a anterior era mesmo recusada/expirada.
            logOfferSeen(ctx, plat, existing!!)
        }
        lastOfferByPlat[plat] = if (existing == null || isReallyDifferentOffer) {
            snap
        } else {
            OfferSnapshot(
                value = snap.value ?: existing.value,
                dinamico = if (snap.dinamico > 0) snap.dinamico else existing.dinamico,
                multiplicador = snap.multiplicador ?: existing.multiplicador,
                kmPickup = snap.kmPickup ?: existing.kmPickup,
                kmTrip = snap.kmTrip ?: existing.kmTrip,
                durPickupSec = snap.durPickupSec ?: existing.durPickupSec,
                durTripSec = snap.durTripSec ?: existing.durTripSec,
                origin = betterAddress(existing.origin, snap.origin),
                dest = betterAddress(existing.dest, snap.dest),
                stopCount = maxOf(existing.stopCount, snap.stopCount),
                stopAddresses = if (snap.stopAddresses.size >= existing.stopAddresses.size) snap.stopAddresses else existing.stopAddresses,
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
        val b = buffersByPlat[plat] ?: return
        if (b.platform != plat) return
        // CORRIGIDO (24/07/2026, confirmado em varredura de dado real — 7
        // corridas num único dia com origem e destino IDÊNTICOS): a leitura
        // ao vivo da navegação roteia o endereço pra origem ou destino
        // usando o status confirmado NO MOMENTO — bem na hora exata da
        // troca buscar→corrida, esse status pode estar um passo atrasado
        // (debounce não é instantâneo), fazendo o endereço de destino (já
        // na tela) ser gravado como se fosse origem, sobrescrevendo o
        // endereço correto que já estava lá. Trava de segurança: nunca
        // deixa a rua da origem ficar igual à rua do destino já registrado
        // (e vice-versa) — corrida de verdade nunca embarca e desembarca
        // na mesma rua.
        val destStreet = normalizedStreet(b.destAddress)
        val originStreet = normalizedStreet(b.originAddress)
        if (origin != null) {
            val newStreet = normalizedStreet(origin)
            val matchesDest = newStreet != null && destStreet != null && newStreet == destStreet
            if (!matchesDest) b.originAddress = betterAddress(b.originAddress, origin)
        }
        if (dest != null) {
            val newStreet = normalizedStreet(dest)
            val matchesOrigin = newStreet != null && originStreet != null && newStreet == originStreet
            if (!matchesOrigin) b.destAddress = betterAddress(b.destAddress, dest)
        }
    }

    // Nome do passageiro: one-shot — só preenche se ainda estava vazio (não
    // existe "nome mais completo", é uma substituição única quando aparece).
    // Marca a chegada física ao passageiro apenas quando existe uma corrida em Buscar.
    // É one-shot: releituras/notificações repetidas nunca reiniciam o cronômetro.
    fun markPickupArrived(plat: String, atMs: Long = System.currentTimeMillis()) {
        val b = buffersByPlat[plat] ?: return
        if (b.platform != plat || b.tripStartedAt > 0L || b.pickupArrivedAt > 0L) return
        b.pickupArrivedAt = atMs
    }

    fun setPassengerNameIfEmpty(plat: String, name: String?) {
        val b = buffersByPlat[plat] ?: return
        if (b.platform != plat) return
        if (b.passengerName.isNullOrBlank() && !name.isNullOrBlank()) b.passengerName = name.trim()
    }

    fun markCash(plat: String) {
        val b = buffersByPlat[plat] ?: return
        if (b.platform != plat) return
        b.dinheiro = true
    }

    fun onStateTransition(ctx: Context, plat: String, prev: String, next: String) {
        val now = System.currentTimeMillis()
        val km = GpsService.totalKm

        fun startBuffer(startKm: Double, alsoStartTrip: Boolean, maxAgeMs: Long) {
            // Só usa a oferta em cache se ainda estiver dentro da janela de
            // validade pra esse tipo de transição — oferta velha demais não
            // gruda em corrida nenhuma; fica sem dado de oferta (igual a
            // hoje quando nenhuma oferta foi vista), nunca com dado errado.
            val cached = lastOfferByPlat[plat]
            val offer = cached?.takeIf { System.currentTimeMillis() - it.seenAt <= maxAgeMs }
            // Consome (limpa) o cache nesse ponto, usada ou não — uma vez
            // que um "buscar" nasceu, essa oferta já cumpriu seu papel (ou
            // expirou); nunca deve poder grudar numa corrida futura.
            lastOfferByPlat.remove(plat)
            buffersByPlat[plat] = Buffer(
                platform = plat,
                offerValue = offer?.value,
                offerDinamico = offer?.dinamico ?: 0.0,
                offerMultiplicador = offer?.multiplicador,
                offerKmPickup = offer?.kmPickup,
                offerKmTrip = offer?.kmTrip,
                offerDurPickupSec = offer?.durPickupSec,
                offerDurTripSec = offer?.durTripSec,
                originAddress = offer?.origin,
                destAddress = offer?.dest,
                stopCount = offer?.stopCount ?: 0,
                stopAddresses = offer?.stopAddresses ?: emptyList(),
                acceptedAt = now,
                pickupStartedAt = now,
                pickupStartKm = startKm,
                tripStartedAt = if (alsoStartTrip) now else 0L,
                tripStartKm = if (alsoStartTrip) startKm else 0.0
            )
        }

        when {
            prev == "online" && next == "buscar" -> {
                startBuffer(km, alsoStartTrip = false, maxAgeMs = OFFER_MAX_AGE_NORMAL_MS)
            }
            prev == "buscar" && next == "corrida" -> {
                val b = buffersByPlat[plat]
                if (b != null && b.platform == plat) {
                    b.tripStartedAt = now
                    b.tripStartKm = km
                    b.gpsOriginLat = GpsService.lastLat
                    b.gpsOriginLng = GpsService.lastLng
                } else {
                    // Rede de segurança: chegou em "corrida" sem termos visto o
                    // "buscar" (debounce pode ter engolido o passo intermediário).
                    // Ainda é um aceite normal (não sobreposição) — janela curta.
                    startBuffer(km, alsoStartTrip = true, maxAgeMs = OFFER_MAX_AGE_NORMAL_MS)
                    buffersByPlat[plat]?.gpsOriginLat = GpsService.lastLat
                    buffersByPlat[plat]?.gpsOriginLng = GpsService.lastLng
                }
            }
            prev == "corrida" && next == "online" -> {
                val b = buffersByPlat[plat]
                buffersByPlat.remove(plat)
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
                buffersByPlat.remove(plat)
            }
            prev == "corrida" && next == "buscar" -> {
                val b = buffersByPlat[plat]
                val elapsedSinceStart = if (b != null && b.tripStartedAt > 0) now - b.tripStartedAt else Long.MAX_VALUE
                if (b != null && b.platform == plat && elapsedSinceStart < MIN_RIDE_BEFORE_OVERLAP_MS) {
                    // Ruído — trata como se a transição nunca tivesse
                    // acontecido, buffer da corrida atual continua intacto.
                    return
                }
                // Overlap: próxima corrida aceita antes da anterior fechar de
                // vez (confirmado em log real). Fecha a anterior com o que já
                // tem (melhor esforço) e começa a nova do zero.
                if (b != null && b.platform == plat) {
                    b.tripEndedAt = now
                    b.tripEndKm = km
                    b.gpsDestLat = GpsService.lastLat
                    b.gpsDestLng = GpsService.lastLng
                    push(ctx, b)
                }
                startBuffer(km, alsoStartTrip = false, maxAgeMs = OFFER_MAX_AGE_OVERLAP_MS)
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

    // Endereço da TELA continua prioritário sempre (pedido do Yuri,
    // 24/07/2026) — isso aqui só COMPLETA quando falta o bairro, nunca
    // substitui. "Falta bairro" = nem tem " - Bairro" (formato do GPS) nem
    // tem pelo menos 3 pedaços separados por vírgula (padrão comum:
    // Local/Rua, Número, Bairro) — sinal de que só veio rua/número crus.
    // Bairro vem do endereço geocodificado pelo GPS (sempre no formato
    // "Rua - Bairro"), pegando só o pedaço depois do último " - ".
    private fun addBairroIfMissing(screenAddr: String?, gpsAddr: String?): String? {
        if (screenAddr.isNullOrBlank()) return screenAddr
        val hasDashBairro = screenAddr.contains(" - ")
        val commaParts = screenAddr.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (hasDashBairro || commaParts.size >= 3) return screenAddr
        val gpsBairro = gpsAddr?.substringAfterLast(" - ")?.trim()
        if (gpsBairro.isNullOrBlank()) return screenAddr
        return "$screenAddr - $gpsBairro"
    }

    private fun push(ctx: Context, b: Buffer) {
        // ÚLTIMA rede de segurança antes de gravar (16/08/2026, confirmado
        // em log real — corrida com 5s de duração e 0,02km rodados, gerada
        // por ruído de OCR oscilando corrida/buscar). Além da trava lá em
        // onStateTransition (que evita a maioria dos casos na origem), essa
        // aqui pega qualquer outro caminho que eu não tenha coberto: nunca
        // grava como corrida "de verdade" algo com menos de 1 min de
        // duração TOTAL (aceite até fim) e praticamente nenhum km rodado —
        // corrida real não existe nesse formato. Descarta silenciosamente
        // (não sobe pro Supabase) em vez de virar um card enganoso na tela.
        if (b.tripEndedAt > 0) {
            val totalDurationMs = b.tripEndedAt - b.acceptedAt
            val totalKm = (b.tripEndKm - b.pickupStartKm).coerceAtLeast(0.0)
            if (totalDurationMs < MIN_RIDE_BEFORE_OVERLAP_MS && totalKm < 0.05) {
                return
            }
        }
        thread(isDaemon = true) {
            try {
                val prefs = ctx.getSharedPreferences(GpsService.PREFS_NAME, Context.MODE_PRIVATE)
                val userId = prefs.getString(GpsService.KEY_USER_ID, null) ?: return@thread

                val realKmPickup = (b.tripStartKm - b.pickupStartKm).coerceAtLeast(0.0)
                val realKmTrip = (b.tripEndKm - b.tripStartKm).coerceAtLeast(0.0)
                // MUDOU (16/08/2026, pedido do Yuri): antes virava "confirmada"
                // só por ter fechado o ciclo aceite→embarque→destino com um
                // valor — mas esse valor é só o da OFERTA, nunca visto o
                // histórico real da plataforma (que pode ter gorjeta,
                // correção de tarifa, etc. que a oferta não mostra). Agora
                // "confirmada" só nasce depois que o vídeo do histórico
                // confirma/corrige o valor (ver confirmImport() no PWA) —
                // aqui o ciclo completo vira "estimada".
                // Status principal agora representa somente confirmação:
                // toda captura automática nasce aguardando confirmação e só o
                // vídeo do histórico pode promovê-la a "confirmada" no PWA.
                // A completude da captura continua guardada separadamente para
                // diagnóstico/aperfeiçoamento do OCR, sem criar outro status.
                val captureComplete = b.offerValue != null && b.tripStartedAt > 0 && b.tripEndedAt > 0
                val status = "estimada"

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

                // Endereço final: o da TELA continua sendo a base (prioridade
                // do Yuri, 24/07/2026) — só ganha o bairro complementado pelo
                // GPS quando ele mesmo não trouxe nenhum.
                val finalOriginAddress = addBairroIfMissing(b.originAddress, gpsOriginAddress)
                val finalDestAddress = addBairroIfMissing(b.destAddress, gpsDestAddress)

                val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
                    .apply { timeZone = TimeZone.getTimeZone("UTC") }
                fun iso(ms: Long): Any = if (ms > 0) sdf.format(Date(ms)) else JSONObject.NULL

                val body = JSONObject().apply {
                    put("user_id", userId)
                    put("platform", if (b.platform == "UBER") "uber" else "99")
                    put("passenger_name", b.passengerName ?: JSONObject.NULL)
                    put("origin_address", finalOriginAddress ?: JSONObject.NULL)
                    put("dest_address", finalDestAddress ?: JSONObject.NULL)
                    put("stop_count", b.stopCount)
                    put("stop_addresses", org.json.JSONArray(b.stopAddresses))
                    put("gps_origin_address", gpsOriginAddress ?: JSONObject.NULL)
                    put("gps_dest_address", gpsDestAddress ?: JSONObject.NULL)
                    put("gps_match_origin", gpsMatchOrigin?.let { it } ?: JSONObject.NULL)
                    put("gps_match_dest", gpsMatchDest?.let { it } ?: JSONObject.NULL)
                    put("offer_value", b.offerValue ?: JSONObject.NULL)
                    put("offer_dinamico", b.offerDinamico)
                    put("offer_multiplicador", b.offerMultiplicador ?: JSONObject.NULL)
                    put("offer_km_pickup", b.offerKmPickup ?: JSONObject.NULL)
                    put("offer_km_trip", b.offerKmTrip ?: JSONObject.NULL)
                    put("offer_duration_pickup_sec", b.offerDurPickupSec ?: JSONObject.NULL)
                    put("offer_duration_trip_sec", b.offerDurTripSec ?: JSONObject.NULL)
                    put("real_km_pickup", realKmPickup)
                    put("real_km_trip", realKmTrip)
                    put("dinheiro", b.dinheiro)
                    put("accepted_at", iso(b.acceptedAt))
                    put("pickup_started_at", iso(b.pickupStartedAt))
                    put("pickup_arrived_at", iso(b.pickupArrivedAt))
                    put("passenger_wait_sec", if (b.pickupArrivedAt > 0L && b.tripStartedAt >= b.pickupArrivedAt) ((b.tripStartedAt - b.pickupArrivedAt) / 1000L).toInt() else JSONObject.NULL)
                    put("trip_started_at", iso(b.tripStartedAt))
                    put("trip_ended_at", iso(b.tripEndedAt))
                    put("status", status)
                    put("capture_source", "automatica")
                    put("value_needs_review", true)
                    put("data_quality_flag", if (captureComplete) JSONObject.NULL else "captura_incompleta")
                }

                val url = URL("${TripReaderService.SUPABASE_URL}/rest/v1/auto_trips")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 8000; conn.readTimeout = 8000
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("apikey", TripReaderService.SUPABASE_ANON)
                val authToken = prefs.getString(GpsService.KEY_ACCESS_TOKEN, null) ?: TripReaderService.SUPABASE_ANON
                conn.setRequestProperty("Authorization", "Bearer $authToken")
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
