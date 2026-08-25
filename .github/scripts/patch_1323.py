from pathlib import Path
p=Path('app/src/main/java/io/github/yurisilva90/smartmobi/TripReaderService.kt')
s=p.read_text()
old='''        if (realPlat != null && realTexts.isNotEmpty()) {
            if (realPlat == "99") send99FullAccessibilityDiagnostics(realTexts)
            if (realPlat == "UBER") {
                val uberRawJoined = realTexts.joinToString("  ")
                val uberRawLow = uberRawJoined.lowercase(Locale.getDefault())
                if (isOfferScreen(uberRawLow)) {
                    sendToCloud(
                        "UBER", "accessibility-raw", "UBER_OFERTA_RAW", "OFERTA_RAW",
                        extractMoney(uberRawJoined), extractKm(uberRawLow), extractMin(uberRawLow), realTexts
                    )
                    sendUberFullAccessibilityDiagnostics(realTexts)
                }
            }
            processRealOffer(realPlat, realTexts)
        } else if (realPlat == null) {
            hideFlashIfActive()
        }
'''
new='''        val uberOfferTexts = textsByPkg.entries.firstOrNull { UBER_PKGS.contains(it.key) &&
            isOfferScreen(it.value.joinToString("  ").lowercase(Locale.getDefault())) }?.value
        val nnOfferTexts = textsByPkg.entries.firstOrNull { NN_PKGS.contains(it.key) &&
            isOfferScreen(it.value.joinToString("  ").lowercase(Locale.getDefault())) }?.value
        val eventOfferPlat = when {
            UBER_PKGS.contains(evPkg) && uberOfferTexts != null -> "UBER"
            NN_PKGS.contains(evPkg) && nnOfferTexts != null -> "99"
            uberOfferTexts != null && nnOfferTexts == null -> "UBER"
            nnOfferTexts != null && uberOfferTexts == null -> "99"
            else -> realPlat
        }
        val eventOfferTexts = when (eventOfferPlat) {
            "UBER" -> uberOfferTexts ?: realTexts
            "99" -> nnOfferTexts ?: realTexts
            else -> realTexts
        }
        if (eventOfferPlat != null && eventOfferTexts.isNotEmpty()) {
            if (eventOfferPlat == "99") send99FullAccessibilityDiagnostics(eventOfferTexts)
            if (eventOfferPlat == "UBER") {
                val uberRawJoined = eventOfferTexts.joinToString("  ")
                val uberRawLow = uberRawJoined.lowercase(Locale.getDefault())
                if (isOfferScreen(uberRawLow)) {
                    sendToCloud("UBER", "accessibility-raw", "UBER_OFERTA_RAW", "OFERTA_RAW",
                        extractMoney(uberRawJoined), extractKm(uberRawLow), extractMin(uberRawLow), eventOfferTexts)
                    sendUberFullAccessibilityDiagnostics(eventOfferTexts)
                }
            }
            processRealOffer(eventOfferPlat, eventOfferTexts)
        } else if (realPlat == null) {
            hideFlashIfActive()
        }
'''
if old not in s: raise SystemExit('offer ownership block not found')
s=s.replace(old,new)
old2='''        val valorTxt = offer.valor?.let { "R$ ${fmtBr(it)}" }
        val titulo = listOfNotNull(plat, valorTxt).joinToString(" · ")
        val lines = ArrayList<String>()
        if (offer.minPickup != null || offer.kmPickup != null) {
            val pickup = listOfNotNull(
                offer.minPickup?.let { "$it min" },
                offer.kmPickup?.let { "${fmtBr(it)} km" }
            ).joinToString(" · ")
            if (pickup.isNotEmpty()) lines.add("Até o passageiro: $pickup")
        }
        val routeMin = offer.minTrip ?: offer.min
        val routeKm = offer.kmTrip ?: offer.km
        if (routeMin != null || routeKm != null) {
            val route = listOfNotNull(
                routeMin?.let { "$it min" },
                routeKm?.let { "${fmtBr(it)} km" }
            ).joinToString(" · ")
            if (route.isNotEmpty()) lines.add("Rota da corrida: $route")
        }
        origem?.let { lines.add("Origem: $it") }
        stops.forEachIndexed { i, addr -> lines.add("Parada ${i + 1}: $addr") }
        if (offer.paradas > stops.size) {
            val faltantes = offer.paradas - stops.size
            lines.add(if (faltantes == 1) "1 parada adicional sem endereço legível" else "$faltantes paradas adicionais sem endereço legível")
        }
        destino?.let { lines.add("Destino: $it") }
        val resumo = lines.joinToString("\\n")
'''
new2='''        val valorTxt = offer.valor?.let { "R$ ${fmtBr(it)}" }
        val titulo = listOfNotNull(plat, valorTxt).joinToString(" - ")
        val totalKmNotif = offer.km ?: listOfNotNull(offer.kmPickup, offer.kmTrip).sum().takeIf { it > 0 }
        val totalMinNotif = offer.min ?: listOfNotNull(offer.minPickup, offer.minTrip).sum().takeIf { it > 0 }
        val linha2 = listOfNotNull(totalKmNotif?.let { "${fmtBr(it)} km" }, totalMinNotif?.let { "${it}m" }).joinToString(" - ")
        val lines = ArrayList<String>()
        origem?.let { lines.add("Origem: $it") }
        destino?.let { lines.add("Destino: $it") }
        val resumo = listOf(titulo, linha2).plus(lines).filter { it.isNotBlank() }.joinToString("\\n")
        val routeMin = offer.minTrip ?: offer.min
        val routeKm = offer.kmTrip ?: offer.km
'''
if old2 not in s: raise SystemExit('notification text block not found')
s=s.replace(old2,new2)
old3='''        val visualLines = lines
        val flashPicture = flashCard.renderNotificationBitmap(
            plat, valorTxt, overallGrade, metrics, offer.min ?: routeMin ?: 0,
            offer.km ?: routeKm ?: 0.0, declineReason, visualLines
        )
        builder.setContentTitle(titulo)
            .setContentText(lines.firstOrNull() ?: titulo)
'''
new3='''        val flashPicture = flashCard.renderNotificationBitmap(
            plat, null, overallGrade, metrics, offer.min ?: routeMin ?: 0,
            offer.km ?: routeKm ?: 0.0, declineReason, emptyList()
        )
        builder.setContentTitle(titulo)
            .setContentText(linha2)
'''
if old3 not in s: raise SystemExit('notification bitmap block not found')
s=s.replace(old3,new3)
old4='''        if (flashPicture != null) {
            // Samsung/Android 10 pode esconder BigPicture enquanto a notificação
            // está recolhida. LargeIcon mantém uma miniatura do Flash visível já
            // no estado normal; ao expandir continua mostrando a imagem completa.
            builder.setLargeIcon(flashPicture)
            builder.setStyle(Notification.BigPictureStyle()
                .bigPicture(flashPicture)
                .setBigContentTitle(titulo)
                .setSummaryText(lines.take(2).joinToString(" · ")))
        } else {
            builder.setStyle(Notification.BigTextStyle().bigText(resumo))
        }
'''
new4='''        if (flashPicture != null) {
            val expandedHeader = listOfNotNull(titulo, linha2.takeIf { it.isNotBlank() },
                origem?.let { "Origem: $it" }, destino?.let { "Destino: $it" }).joinToString("\\n")
            builder.setStyle(Notification.BigPictureStyle().bigPicture(flashPicture).setBigContentTitle(expandedHeader))
        } else {
            builder.setStyle(Notification.BigTextStyle().bigText(resumo))
        }
'''
if old4 not in s: raise SystemExit('notification style block not found')
s=s.replace(old4,new4)
p.write_text(s)
g=Path('app/build.gradle')
gs=g.read_text().replace('versionCode 235','versionCode 236').replace('versionName "1.3.22"','versionName "1.3.23"')
g.write_text(gs)
