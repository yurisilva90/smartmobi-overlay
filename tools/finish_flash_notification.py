from pathlib import Path

root=Path('.')
tr=root/'app/src/main/java/io/github/yurisilva90/smartmobi/TripReaderService.kt'
drv=root/'app/src/main/java/io/github/yurisilva90/smartmobi/DriverNotificationListenerService.kt'
grad=root/'app/build.gradle'

s=drv.read_text()
old='''        if (eventType == "posted") {\n            TripReaderService.onOfficialOperationalNotification(platform, keywords.distinct(), actionLabels.distinct())\n        }'''
new='''        if (eventType == "posted") {\n            TripReaderService.onOfficialOperationalNotification(platform, keywords.distinct(), actionLabels.distinct())\n            if (routeInfo.origin != null || routeInfo.dest != null || routeInfo.stops.isNotEmpty()) {\n                TripReaderService.onOfficialRouteInfo(platform, routeInfo.origin, routeInfo.dest, routeInfo.stops)\n            }\n        }'''
assert old in s
s=s.replace(old,new,1)
drv.write_text(s)

s=tr.read_text()
old='''        fun onOfficialOperationalNotification(platform: String, keywords: List<String>, actions: List<String>) {\n            instance?.handleOfficialOperationalNotification(platform, keywords, actions)\n        }'''
new='''        fun onOfficialOperationalNotification(platform: String, keywords: List<String>, actions: List<String>) {\n            instance?.handleOfficialOperationalNotification(platform, keywords, actions)\n        }\n\n        @JvmStatic\n        fun onOfficialRouteInfo(platform: String, origin: String?, dest: String?, stops: List<String>) {\n            instance?.handleOfficialRouteInfo(platform, origin, dest, stops)\n        }'''
assert old in s
s=s.replace(old,new,1)

anchor='''    private val OFFICIAL_OFFER_HINT_MS = 12_000L\n'''
insert='''    private val OFFICIAL_OFFER_HINT_MS = 12_000L\n    private var officialRoutePlatform: String? = null\n    private var officialRouteOrigin: String? = null\n    private var officialRouteDest: String? = null\n    private var officialRouteStops: List<String> = emptyList()\n    private var officialRouteUntilMs = 0L\n    private val OFFICIAL_ROUTE_HINT_MS = 20_000L\n'''
assert anchor in s
s=s.replace(anchor,insert,1)

anchor='''    private fun handleOfficialOperationalNotification(plat: String, keywords: List<String>, actions: List<String>) {\n        if (plat != "UBER" && plat != "99") return\n        val strongArrival = keywords.any { it == "cheguei" || it == "iniciar" } ||\n            actions.any { it == "cheguei" || it == "iniciar" }\n        if (strongArrival) AutoTripCapture.markPickupArrived(plat)\n    }'''
insert=anchor+'''\n\n    private fun handleOfficialRouteInfo(plat: String, origin: String?, dest: String?, stops: List<String>) {\n        if (plat != "UBER" && plat != "99") return\n        officialRoutePlatform = plat\n        if (!origin.isNullOrBlank()) officialRouteOrigin = origin\n        if (!dest.isNullOrBlank()) officialRouteDest = dest\n        if (stops.isNotEmpty()) officialRouteStops = stops.filter { it.isNotBlank() }.distinct()\n        officialRouteUntilMs = System.currentTimeMillis() + OFFICIAL_ROUTE_HINT_MS\n    }\n\n    private fun enrichOfferWithOfficialRoute(plat: String, offer: Offer): Offer {\n        if (officialRoutePlatform != plat || System.currentTimeMillis() > officialRouteUntilMs) return offer\n        return offer.copy(\n            origem = offer.origem ?: officialRouteOrigin,\n            destino = offer.destino ?: officialRouteDest,\n            stopAddresses = if (offer.stopAddresses.isNotEmpty()) offer.stopAddresses else officialRouteStops,\n            paradas = maxOf(offer.paradas, if (offer.stopAddresses.isNotEmpty()) offer.stopAddresses.size else officialRouteStops.size)\n        )\n    }'''
assert anchor in s
s=s.replace(anchor,insert,1)

old='''        val offer = parseOffer(texts)\n        val valor = offer.valor'''
new='''        var offer = parseOffer(texts)\n        offer = enrichOfferWithOfficialRoute(plat, offer)\n        val valor = offer.valor'''
assert old in s
s=s.replace(old,new,1)

old='''        saveSnapshot(plat, bmp, overallGrade, metrics, min ?: 0, km, offer, texts)\n        showRouteNotification(plat'''
new='''        val notificationImage = try { bmp?.copy(Bitmap.Config.ARGB_8888, false) } catch (_: Exception) { null }\n        saveSnapshot(plat, bmp, overallGrade, metrics, min ?: 0, km, offer, texts)\n        showRouteNotification(plat'''
assert old in s
s=s.replace(old,new,1)

old='''            declineReason = declineReason\n        )'''
# replace first occurrence after showRouteNotification only
idx=s.index('showRouteNotification(plat')
pos=s.index(old,idx)
s=s[:pos]+'''            declineReason = declineReason,\n            offerImage = notificationImage\n        )'''+s[pos+len(old):]

old='''        metrics: List<FlashCard.Metric>,\n        declineReason: String?\n    ) {'''
new='''        metrics: List<FlashCard.Metric>,\n        declineReason: String?,\n        offerImage: Bitmap? = null\n    ) {'''
assert old in s
s=s.replace(old,new,1)

old='''        builder.setContentTitle(titulo)\n            .setStyle(Notification.BigTextStyle().bigText(resumo))\n            .setContentText(lines.firstOrNull() ?: titulo)'''
new='''        builder.setContentTitle(titulo)\n            .setStyle(if (offerImage != null) Notification.BigPictureStyle().bigPicture(offerImage).setSummaryText(resumo) else Notification.BigTextStyle().bigText(resumo))\n            .setContentText(lines.firstOrNull() ?: titulo)'''
assert old in s
s=s.replace(old,new,1)
old='''        try { nm.notify(4103, builder.build()) } catch (_: Exception) {}'''
new='''        try { nm.notify(4103, builder.build()) } catch (_: Exception) {} finally { try { offerImage?.recycle() } catch (_: Exception) {} }'''
assert old in s
s=s.replace(old,new,1)
tr.write_text(s)

g=grad.read_text()
g=g.replace('versionCode 225','versionCode 226').replace('versionName "1.3.12"','versionName "1.3.13"')
grad.write_text(g)
