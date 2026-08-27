from pathlib import Path

p=Path('app/src/main/java/io/github/yurisilva90/smartmobi/AutoTripCapture.kt')
s=p.read_text(encoding='utf-8')

old='''    private val lastOfferByPlat = HashMap<String, OfferSnapshot>()
'''
new='''    private val lastOfferByPlat = HashMap<String, OfferSnapshot>()

    // Marca ofertas que foram vistas enquanto outra corrida da mesma plataforma
    // ainda estava em andamento. Essa marca precisa sobreviver ao fim da corrida
    // anterior: a 99 pode exibir a próxima oferta antes do desembarque e o aceite
    // efetivo só acontecer mais de 45s depois.
    private val overlapOfferByPlat = HashSet<String>()
'''
if old not in s: raise SystemExit('lastOfferByPlat marker not found')
s=s.replace(old,new,1)

old='''            val current = buffersByPlat[plat]
            val overlapCandidate = current != null && current.tripStartedAt > 0L && current.tripEndedAt == 0L
            val maxAge = if (overlapCandidate) OFFER_MAX_AGE_OVERLAP_MS else OFFER_STALE_MS
'''
new='''            val current = buffersByPlat[plat]
            val activeRide = current != null && current.tripStartedAt > 0L && current.tripEndedAt == 0L
            val overlapCandidate = activeRide || overlapOfferByPlat.contains(plat)
            val maxAge = if (overlapCandidate) OFFER_MAX_AGE_OVERLAP_MS else OFFER_STALE_MS
'''
if old not in s: raise SystemExit('flush overlap block not found')
s=s.replace(old,new,1)

old='''            logOfferSeen(ctx, plat, snap)
            lastOfferByPlat.remove(plat)
'''
new='''            logOfferSeen(ctx, plat, snap)
            lastOfferByPlat.remove(plat)
            overlapOfferByPlat.remove(plat)
'''
# only first occurrence is stale loop
if old not in s: raise SystemExit('stale removal block not found')
s=s.replace(old,new,1)

old='''    fun onOfferSeen(ctx: Context, plat: String, snap: OfferSnapshot) {
        val existing = lastOfferByPlat[plat]
'''
new='''    fun onOfferSeen(ctx: Context, plat: String, snap: OfferSnapshot) {
        val existing = lastOfferByPlat[plat]
        val current = buffersByPlat[plat]
        val seenDuringActiveRide = current != null && current.tripStartedAt > 0L && current.tripEndedAt == 0L
        if (seenDuringActiveRide) overlapOfferByPlat.add(plat)
'''
if old not in s: raise SystemExit('onOfferSeen header not found')
s=s.replace(old,new,1)

old='''        if (isReallyDifferentOffer) {
            // Oferta diferente chegou por cima de uma que nunca foi
            // aceita (aceite já teria removido do cache antes disso) —
            // a anterior era mesmo recusada/expirada.
            logOfferSeen(ctx, plat, existing!!)
        }
'''
new='''        if (isReallyDifferentOffer) {
            // Oferta diferente chegou por cima de uma que nunca foi
            // aceita (aceite já teria removido do cache antes disso) —
            // a anterior era mesmo recusada/expirada.
            logOfferSeen(ctx, plat, existing!!)
            // Se a substituta apareceu fora de uma corrida ativa, ela volta a
            // ser uma oferta normal e não herda a janela longa da anterior.
            if (!seenDuringActiveRide) overlapOfferByPlat.remove(plat)
        }
'''
if old not in s: raise SystemExit('different offer block not found')
s=s.replace(old,new,1)

old='''            val cached = lastOfferByPlat[plat]
            val offer = cached?.takeIf { System.currentTimeMillis() - it.seenAt <= maxAgeMs }
            // Consome (limpa) o cache nesse ponto, usada ou não — uma vez
            // que um "buscar" nasceu, essa oferta já cumpriu seu papel (ou
            // expirou); nunca deve poder grudar numa corrida futura.
            lastOfferByPlat.remove(plat)
'''
new='''            val cached = lastOfferByPlat[plat]
            // Uma oferta vista durante a corrida anterior continua sendo uma
            // candidata de overlap mesmo depois que a anterior terminou.
            val effectiveMaxAgeMs = if (overlapOfferByPlat.contains(plat)) OFFER_MAX_AGE_OVERLAP_MS else maxAgeMs
            val offer = cached?.takeIf { System.currentTimeMillis() - it.seenAt <= effectiveMaxAgeMs }
            // Consome (limpa) o cache e a marca de overlap juntos.
            lastOfferByPlat.remove(plat)
            overlapOfferByPlat.remove(plat)
'''
if old not in s: raise SystemExit('startBuffer cache block not found')
s=s.replace(old,new,1)

p.write_text(s,encoding='utf-8')

b=Path('app/build.gradle')
g=b.read_text(encoding='utf-8')
g=g.replace('// 1.3.28: consolida cache de oferta + proteção de status em segundo plano.', '// 1.3.29: preserva oferta da próxima corrida após o fim da corrida anterior.', 1)
g=g.replace('// build trigger v1.3.28', '// build trigger v1.3.29', 1)
g=g.replace('versionCode 240', 'versionCode 241', 1)
g=g.replace('versionName "1.3.28"', 'versionName "1.3.29"', 1)
b.write_text(g,encoding='utf-8')
print('overlap offer survival patch applied')
