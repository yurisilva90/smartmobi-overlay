from pathlib import Path
p=Path('app/src/main/java/io/github/yurisilva90/smartmobi/TripReaderService.kt')
s=p.read_text()
s=s.replace('        val notificationImage = try { bmp?.copy(Bitmap.Config.ARGB_8888, false) } catch (_: Exception) { null }\n','',1)
s=s.replace('        showRouteNotification(plat, offer, overallGrade, metrics, declineReason, notificationImage)','        showRouteNotification(plat, offer, overallGrade, metrics, declineReason)',1)
p.write_text(s)
