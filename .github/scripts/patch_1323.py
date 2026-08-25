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
if old not in s:
    raise SystemExit('offer ownership block not found')
s=s.replace(old,new)

start=s.index('    private fun showRouteNotification(')
end=s.index('\n    private fun sendToCloud(', start)
newfunc=r'''    private fun showRouteNotification(
        plat: String,
        offer: Offer,
        overallGrade: String,
        metrics: List<FlashCard.Metric>,
        declineReason: String?
    ) {
        val origem = offer.origem
        val destino = offer.destino
        val totalMin = offer.min ?: listOfNotNull(offer.minPickup, offer.minTrip).sum().takeIf { it > 0 }
        val totalKm = offer.km ?: listOfNotNull(offer.kmPickup, offer.kmTrip).sum().takeIf { it > 0.0 }
        val key = listOf(plat, offer.valor?.toString() ?: "", origem ?: "", destino ?: "",
            overallGrade, totalMin?.toString() ?: "", totalKm?.toString() ?: "",
            metrics.joinToString("|") { "${it.label}:${it.value}:${it.grade}" }).joinToString("|")
        if (key == lastNotifKey) return
        lastNotifKey = key

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(NOTIF_CHANNEL_ROUTE) == null) {
            val ch = NotificationChannel(NOTIF_CHANNEL_ROUTE, "MōB Flash — rota da corrida", NotificationManager.IMPORTANCE_LOW)
            ch.setSound(null, null); ch.enableVibration(false); ch.enableLights(false)
            nm.createNotificationChannel(ch)
        }

        fun mapIntent(addr: String): PendingIntent {
            val geoUri = Uri.parse("geo:0,0?q=" + Uri.encode(addr))
            val maps = Intent(Intent.ACTION_VIEW, geoUri).apply { setPackage("com.google.android.apps.maps") }
            val generic = Intent(Intent.ACTION_VIEW, geoUri)
            val real = if (maps.resolveActivity(packageManager) != null) maps else generic
            return PendingIntent.getActivity(this, addr.hashCode(), real,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        val valorTxt = offer.valor?.let { "R$ ${fmtBr(it)}" }
        val platLabel = if (plat.equals("UBER", true)) "Uber" else "99"
        val titulo = listOfNotNull(platLabel, valorTxt).joinToString(" - ")
        val linha2 = listOfNotNull(totalKm?.let { "${fmtBr(it)} km" }, totalMin?.let { "${it}m" }).joinToString(" - ")
        val origemLinha = origem?.let { "Origem: $it" }
        val destinoLinha = destino?.let { "Destino: $it" }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, NOTIF_CHANNEL_ROUTE)
        else @Suppress("DEPRECATION") Notification.Builder(this)

        val flashPicture = flashCard.renderNotificationBitmap(
            plat, null, overallGrade, metrics, totalMin ?: 0, totalKm ?: 0.0, declineReason, emptyList()
        )
        builder.setContentTitle(titulo)
            .setContentText(linha2)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(Notification.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setOngoing(false)

        val expandedText = listOfNotNull(linha2.takeIf { it.isNotBlank() }, origemLinha, destinoLinha).joinToString("\n")
        if (flashPicture != null) {
            builder.setStyle(Notification.BigPictureStyle()
                .bigPicture(flashPicture)
                .setBigContentTitle(titulo)
                .setSummaryText(expandedText))
        } else {
            builder.setStyle(Notification.BigTextStyle().bigText(expandedText))
        }
        if (origem != null) builder.addAction(Notification.Action.Builder(null, "Origem", mapIntent(origem)).build())
        if (destino != null) builder.addAction(Notification.Action.Builder(null, "Destino", mapIntent(destino)).build())
        try { nm.notify(4103, builder.build()) } catch (_: Exception) {}
    }
'''
s=s[:start]+newfunc+s[end:]
p.write_text(s)

g=Path('app/build.gradle')
gs=g.read_text().replace('versionCode 235','versionCode 236').replace('versionName "1.3.22"','versionName "1.3.23"')
g.write_text(gs)
