from pathlib import Path
p=Path('app/src/main/java/io/github/yurisilva90/smartmobi/TripReaderService.kt')
s=p.read_text()

# 1) Extrai Origem/Destino também quando vêm explicitamente rotulados na notificação/OCR.
old='''        val origem = addrCandidates.firstOrNull()\n        val destino = if (addrCandidates.size >= 2) addrCandidates.lastOrNull() else null\n        val stopAddresses = if (paradas > 0 && addrCandidates.size > 2)\n            addrCandidates.subList(1, addrCandidates.size - 1).take(paradas)\n        else emptyList()\n\n        return Offer(valor, km, min, rkmDirect, nota, origem, destino, legs, kmPickup, kmTrip, minPickup, minTrip, dinamico, corridas, paradas, multiplicador, stopAddresses)'''
new='''        fun explicitAddress(labels: List<String>): String? {\n            for (line in texts) {\n                val clean = line.replace(Regex("^\\\\((?:title|text|big)\\\\)\\\\s*", RegexOption.IGNORE_CASE), "").trim()\n                val lowLine = clean.lowercase(Locale.getDefault())\n                for (label in labels) {\n                    val idx = lowLine.indexOf(label)\n                    if (idx >= 0) {\n                        val tail = clean.substring(idx + label.length).trim().trimStart(':', '-', '–', '—').trim()\n                        if (looksLikeAddress(tail)) return tail\n                    }\n                }\n            }\n            return null\n        }\n        // Notificações oficiais frequentemente trazem o endereço como\n        // "Origem: ..." / "Destino: ..." sem uma linha de perna imediatamente\n        // antes. Esse caminho tem prioridade porque o rótulo elimina a\n        // ambiguidade do heurístico posicional.\n        val origem = explicitAddress(listOf("origem", "embarque", "buscar em", "pickup")) ?: addrCandidates.firstOrNull()\n        val destino = explicitAddress(listOf("destino", "desembarque", "dropoff")) ?: if (addrCandidates.size >= 2) addrCandidates.lastOrNull() else null\n        val stopAddresses = if (paradas > 0 && addrCandidates.size > 2)\n            addrCandidates.subList(1, addrCandidates.size - 1).take(paradas)\n        else emptyList()\n\n        return Offer(valor, km, min, rkmDirect, nota, origem, destino, legs, kmPickup, kmTrip, minPickup, minTrip, dinamico, corridas, paradas, multiplicador, stopAddresses)'''
assert old in s, 'parseOffer anchor missing'
s=s.replace(old,new,1)

# 2) Não perde paradas no snapshot principal depois que a oferta passa na validação completa.
old2='''            origin = offer.origem, dest = offer.destino\n        ))'''
new2='''            origin = offer.origem, dest = offer.destino,\n            stopCount = offer.paradas, stopAddresses = offer.stopAddresses\n        ))'''
assert old2 in s, 'snapshot anchor missing'
s=s.replace(old2,new2,1)

# 3) A notificação recebe uma cópia do screenshot antes de saveSnapshot reciclar o bitmap.
old3='''        saveSnapshot(plat, bmp, overallGrade, metrics, min ?: 0, km, offer, texts)\n        showRouteNotification(plat, offer, overallGrade, metrics, declineReason)'''
new3='''        val notifBmp = try { bmp?.copy(Bitmap.Config.ARGB_8888, false) } catch (_: Exception) { null }\n        saveSnapshot(plat, bmp, overallGrade, metrics, min ?: 0, km, offer, texts)\n        showRouteNotification(plat, offer, overallGrade, metrics, declineReason, notifBmp)'''
assert old3 in s, 'notification call anchor missing'
s=s.replace(old3,new3,1)

old4='''        metrics: List<FlashCard.Metric>,\n        declineReason: String?\n    ) {'''
new4='''        metrics: List<FlashCard.Metric>,\n        declineReason: String?,\n        screenshot: Bitmap? = null\n    ) {'''
idx=s.find('    private fun showRouteNotification(')
assert idx>=0, 'showRouteNotification missing'
sub=s[idx:]
assert old4 in sub, 'signature anchor missing'
sub=sub.replace(old4,new4,1)
s=s[:idx]+sub
s=s.replace('''        if (origem == null && destino == null && stops.isEmpty()) return\n\n        val verdict''','''        val verdict''',1)

old5='''        builder.setContentTitle(titulo)\n            .setStyle(Notification.BigTextStyle().bigText(resumo))\n            .setContentText(lines.firstOrNull() ?: titulo)'''
new5='''        builder.setContentTitle(titulo)\n            .setContentText(lines.firstOrNull() ?: titulo)\n        if (screenshot != null) {\n            builder.setStyle(Notification.BigPictureStyle().bigPicture(screenshot).setSummaryText(resumo))\n        } else {\n            builder.setStyle(Notification.BigTextStyle().bigText(resumo))\n        }\n        builder'''
assert old5 in s, 'style anchor missing'
s=s.replace(old5,new5,1)
s=s.replace('''        try { nm.notify(4103, builder.build()) } catch (_: Exception) {}\n    }''','''        try { nm.notify(4103, builder.build()) } catch (_: Exception) {}\n        try { screenshot?.recycle() } catch (_: Exception) {}\n    }''',1)
p.write_text(s)

b=Path('app/build.gradle')
g=b.read_text()
g=g.replace('versionCode 225','versionCode 226').replace('versionName "1.3.12"','versionName "1.3.13"')
g=g.replace('// 1.3.12: checklist completo de permissões + alerta preventivo antes das ofertas.','// 1.3.13: notificação com imagem do Flash + captura reforçada de origem/destino.')
b.write_text(g)
# trigger apply-route-notification-v1313
