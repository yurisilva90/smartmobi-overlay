from pathlib import Path
p=Path('app/src/main/java/io/github/yurisilva90/smartmobi/TripReaderService.kt')
s=p.read_text()
# Este script agora serve só para corrigir a mutabilidade que impedia smart-cast.
old='''        var offer = parseOffer(texts)\n        offer = enrichOfferWithOfficialRoute(plat, offer)\n        val valor = offer.valor'''
new='''        val parsedOffer = parseOffer(texts)\n        val offer = enrichOfferWithOfficialRoute(plat, parsedOffer)\n        val valor = offer.valor'''
if old in s:
    s=s.replace(old,new,1)
else:
    # Na primeira execução completa, aplica as mudanças estruturais antes.
    d=Path('app/src/main/java/io/github/yurisilva90/smartmobi/DriverNotificationListenerService.kt')
    ds=d.read_text()
    a='''        if (eventType == "posted") {\n            TripReaderService.onOfficialOperationalNotification(platform, keywords.distinct(), actionLabels.distinct())\n        }'''
    b='''        if (eventType == "posted") {\n            TripReaderService.onOfficialOperationalNotification(platform, keywords.distinct(), actionLabels.distinct())\n            if (routeInfo.origin != null || routeInfo.dest != null || routeInfo.stops.isNotEmpty()) {\n                TripReaderService.onOfficialRouteInfo(platform, routeInfo.origin, routeInfo.dest, routeInfo.stops)\n            }\n        }'''
    assert a in ds; d.write_text(ds.replace(a,b,1))
    a='''        fun onOfficialOperationalNotification(platform: String, keywords: List<String>, actions: List<String>) {\n            instance?.handleOfficialOperationalNotification(platform, keywords, actions)\n        }'''
    b=a+'''\n\n        @JvmStatic\n        fun onOfficialRouteInfo(platform: String, origin: String?, dest: String?, stops: List<String>) {\n            instance?.handleOfficialRouteInfo(platform, origin, dest, stops)\n        }'''
    assert a in s; s=s.replace(a,b,1)
    a='''    private val OFFICIAL_OFFER_HINT_MS = 12_000L\n'''
    b=a+'''    private var officialRoutePlatform: String? = null\n    private var officialRouteOrigin: String? = null\n    private var officialRouteDest: String? = null\n    private var officialRouteStops: List<String> = emptyList()\n    private var officialRouteUntilMs = 0L\n    private val OFFICIAL_ROUTE_HINT_MS = 20_000L\n'''
    assert a in s; s=s.replace(a,b,1)
    a='''    private fun handleOfficialOperationalNotification(plat: String, keywords: List<String>, actions: List<String>) {\n        if (plat != "UBER" && plat != "99") return\n        val strongArrival = keywords.any { it == "cheguei" || it == "iniciar" } ||\n            actions.any { it == "cheguei" || it == "iniciar" }\n        if (strongArrival) AutoTripCapture.markPickupArrived(plat)\n    }'''
    b=a+'''\n\n    private fun handleOfficialRouteInfo(plat: String, origin: String?, dest: String?, stops: List<String>) {\n        if (plat != "UBER" && plat != "99") return\n        officialRoutePlatform = plat\n        if (!origin.isNullOrBlank()) officialRouteOrigin = origin\n        if (!dest.isNullOrBlank()) officialRouteDest = dest\n        if (stops.isNotEmpty()) officialRouteStops = stops.filter { it.isNotBlank() }.distinct()\n        officialRouteUntilMs = System.currentTimeMillis() + OFFICIAL_ROUTE_HINT_MS\n    }\n\n    private fun enrichOfferWithOfficialRoute(plat: String, offer: Offer): Offer {\n        if (officialRoutePlatform != plat || System.currentTimeMillis() > officialRouteUntilMs) return offer\n        return offer.copy(origem=offer.origem ?: officialRouteOrigin, destino=offer.destino ?: officialRouteDest, stopAddresses=if (offer.stopAddresses.isNotEmpty()) offer.stopAddresses else officialRouteStops, paradas=maxOf(offer.paradas, if (offer.stopAddresses.isNotEmpty()) offer.stopAddresses.size else officialRouteStops.size))\n    }'''
    assert a in s; s=s.replace(a,b,1)
    a='''        val offer = parseOffer(texts)\n        val valor = offer.valor'''; b=new
    assert a in s; s=s.replace(a,b,1)
    a='''        saveSnapshot(plat, bmp, overallGrade, metrics, min ?: 0, km, offer, texts)\n        showRouteNotification(plat, offer, overallGrade, metrics, declineReason)'''
    b='''        val notificationImage = try { bmp?.copy(Bitmap.Config.ARGB_8888, false) } catch (_: Exception) { null }\n        saveSnapshot(plat, bmp, overallGrade, metrics, min ?: 0, km, offer, texts)\n        showRouteNotification(plat, offer, overallGrade, metrics, declineReason, notificationImage)'''
    assert a in s; s=s.replace(a,b,1)
    a='''        metrics: List<FlashCard.Metric>,\n        declineReason: String?\n    ) {'''; b='''        metrics: List<FlashCard.Metric>,\n        declineReason: String?,\n        offerImage: Bitmap? = null\n    ) {'''
    assert a in s; s=s.replace(a,b,1)
    a='''.setStyle(Notification.BigTextStyle().bigText(resumo))'''; b='''.setStyle(if (offerImage != null) Notification.BigPictureStyle().bigPicture(offerImage).setSummaryText(resumo) else Notification.BigTextStyle().bigText(resumo))'''
    assert a in s; s=s.replace(a,b,1)
    a='''        try { nm.notify(4103, builder.build()) } catch (_: Exception) {}'''; b='''        try { nm.notify(4103, builder.build()) } catch (_: Exception) {} finally { try { offerImage?.recycle() } catch (_: Exception) {} }'''
    assert a in s; s=s.replace(a,b,1)
p.write_text(s)
g=Path('app/build.gradle'); gs=g.read_text().replace('versionCode 225','versionCode 226').replace('versionName "1.3.12"','versionName "1.3.13"'); g.write_text(gs)
