from pathlib import Path

p = Path('app/src/main/java/io/github/yurisilva90/smartmobi/AutoTripCapture.kt')
s = p.read_text(encoding='utf-8')

old = '''    fun flushStaleOffers(ctx: Context) {
        val now = System.currentTimeMillis()
        val stale = lastOfferByPlat.filterValues { now - it.seenAt > OFFER_STALE_MS }
        stale.forEach { (plat, snap) ->
            logOfferSeen(ctx, plat, snap)
            lastOfferByPlat.remove(plat)
        }
    }
'''
new = '''    fun flushStaleOffers(ctx: Context) {
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
'''
if old not in s:
    raise SystemExit('flushStaleOffers block not found')
s = s.replace(old, new, 1)

b = Path('app/build.gradle')
g = b.read_text(encoding='utf-8')
g = g.replace("versionCode 238", "versionCode 239", 1)
g = g.replace('versionName "1.3.25"', 'versionName "1.3.27"', 1)
g = g.replace('// 1.3.25: melhora relevância e UX dos cards colaborativos proativos.', '// 1.3.27: preserva oferta sobreposta para vincular valor à próxima corrida.', 1)
g = g.replace('// build trigger v1.3.25', '// build trigger v1.3.27', 1)
b.write_text(g, encoding='utf-8')
p.write_text(s, encoding='utf-8')
print('overlap offer cache patch applied')
