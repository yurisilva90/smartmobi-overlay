from pathlib import Path
p=Path('app/src/main/java/io/github/yurisilva90/smartmobi/TripReaderService.kt')
s=p.read_text()

# 1) Card não deve sumir por duas leituras ruins transitórias do OCR.
s=s.replace('private val OFFER_HIDE_GRACE = 2', 'private val OFFER_HIDE_GRACE = 5')

# 2) Remove linhas inequívocas do próprio MōB antes de interpretar a oferta.
old='''        val joined = texts.joinToString("  ")
        val low = joined.lowercase(Locale.getDefault())

        if (!isOfferScreen(low)) {'''
new='''        // O screenshot inclui o próprio overlay do MōB. Remove apenas as
        // linhas inequívocas do nosso card antes do parser; números soltos não
        // bastam para formar oferta e ficam inofensivos. Isso evita feedback
        // MōB -> OCR -> MōB sem apagar a oferta real que está por baixo.
        val offerTexts = texts.filterNot { raw ->
            val l = raw.trim().lowercase(Locale.getDefault())
            l == "r$/km" || l == "r$/hora" || l == "r$/min" ||
                l == "% lucro" || l == "%lucro" || l == "lucro" ||
                l == "origem" || l == "destino"
        }
        val joined = offerTexts.joinToString("  ")
        val low = joined.lowercase(Locale.getDefault())

        if (!isOfferScreen(low)) {'''
if old not in s: raise SystemExit('processRealOffer header block not found')
s=s.replace(old,new,1)

s=s.replace('val parsedOffer = parseOffer(texts)', 'val parsedOffer = parseOffer(offerTexts)',1)

# 3) Rejeita uma distorção observada nos logs reais: releitura de menos de
# 3 km aparecendo como 45+ minutos (ex. 0,893 km / 64 min). Mantém a oferta
# anterior viva e pede nova leitura em vez de substituir o card por lixo.
old='''        val valor = offer.valor
        val km = offer.km
        val min = offer.min

        if (valor == null || valor < 5.0 || valor > 2000.0) {'''
new='''        val valor = offer.valor
        val km = offer.km
        val min = offer.min

        if (km != null && min != null && km < 3.0 && min >= 45) {
            bmp?.recycle()
            main.post { flashCard.keepAlive(15000L) }
            val nowBad = System.currentTimeMillis()
            if (nowBad - (lastPartialRetryMsByPlat[plat] ?: 0L) > 650L) {
                lastPartialRetryMsByPlat[plat] = nowBad
                main.postDelayed({ requestPriorityOcr(plat) }, 260L)
            }
            return
        }

        if (valor == null || valor < 5.0 || valor > 2000.0) {'''
if old not in s: raise SystemExit('offer validation block not found')
s=s.replace(old,new,1)

# 4) Uma releitura melhor da MESMA oferta continua enriquecendo AutoTripCapture
# (isso já ocorre antes deste bloco), mas não redesenha o Flash. O redraw era
# a principal origem de piscadas e de 5-8 snapshots idênticos por oferta.
old='''                if (nowVisual - activeVisual.firstSeenMs <= OFFER_REFINE_WINDOW_MS &&
                    isClearlyBetterOffer(offer, activeVisual.offer)) {
                    activeVisual.offer = offer
                    visualChanged = true
                }'''
new='''                if (nowVisual - activeVisual.firstSeenMs <= OFFER_REFINE_WINDOW_MS &&
                    isClearlyBetterOffer(offer, activeVisual.offer)) {
                    // Refina apenas o estado interno. AutoTripCapture já recebeu
                    // essa leitura acima; o card visível não precisa piscar nem
                    // gerar outro snapshot/notificação para a mesma oferta.
                    activeVisual.offer = offer
                }'''
if old not in s: raise SystemExit('same-offer refinement block not found')
s=s.replace(old,new,1)

# Usa as linhas já limpas também na auditoria/snapshot do card.
s=s.replace('"OFERTA", extractMoney(joined), km.toString(), min?.toString(), texts)',
            '"OFERTA", extractMoney(joined), km.toString(), min?.toString(), offerTexts)',1)
s=s.replace('saveSnapshot(plat, bmp, overallGrade, metrics, min ?: 0, km, offer, texts)',
            'saveSnapshot(plat, bmp, overallGrade, metrics, min ?: 0, km, offer, offerTexts)',1)

p.write_text(s)

g=Path('app/build.gradle')
gs=g.read_text().replace('versionCode 236','versionCode 237').replace('versionName "1.3.23"','versionName "1.3.24"')
g.write_text(gs)
