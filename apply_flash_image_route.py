from pathlib import Path
import re

trip = Path('app/src/main/java/io/github/yurisilva90/smartmobi/TripReaderService.kt')
s = trip.read_text()

s = s.replace('import android.graphics.Bitmap\nimport android.graphics.Rect\n',
    'import android.graphics.Bitmap\nimport android.graphics.Canvas\nimport android.graphics.Color\nimport android.graphics.Paint\nimport android.graphics.Rect\nimport android.graphics.RectF\nimport android.graphics.Typeface\n')

a = s.index('        // endereços: a linha logo depois de uma "perna"')
b = s.index('\n\n        return Offer(', a)
address_block = r'''        // Endereços de rota: a 99/Uber variam bastante a ordem do OCR.
        // Prioridade: rótulo explícito -> endereço próximo às pernas -> todos os
        // endereços válidos da oferta, preservando a ordem visual.
        fun isAddressNoise(s: String): Boolean {
            val sl = s.lowercase(Locale.getDefault()).trim()
            if (sl.length < 5) return true
            if (sl.contains("r$")) return true
            if (sl.contains("não afeta") || sl.contains("nao afeta")) return true
            if (sl.contains("tarifa") || sl.contains("taxa de espera") || sl.contains("espera longa")) return true
            if (sl == "online" || sl == "buscando" || sl == "offline" || sl == "conectar") return true
            if (sl.contains("perfil essencial") || sl.contains("perfil premium") || sl.contains("perfil prata")) return true
            if (sl.contains("pgto. no app") || sl == "dinheiro" || sl == "negocia" || sl == "qr code") return true
            if (Regex("""[1-5][.,]\d{2}.{0,15}corrid""").containsMatchIn(sl)) return true
            if (sl.contains("verif.") || sl.contains("cpf e cart")) return true
            if (Regex("""^\(?\s*\d{1,3}(?:[.,]\d+)?\s*(?:min(?:utos)?|km|m)\b""", RegexOption.IGNORE_CASE).containsMatchIn(sl)) return true
            if (Regex("""^[x%\d.,\s]+$""").containsMatchIn(sl)) return true
            return false
        }
        fun looksLikeAddress(s: String): Boolean {
            if (isAddressNoise(s)) return false
            val sl = s.lowercase(Locale.getDefault()).trim()
            if (sl.contains("parada") && !Regex("""\b(parada|stop)\b.*[,\-–—:]""", RegexOption.IGNORE_CASE).containsMatchIn(sl)) return false
            val hasStreetWord = Regex("""\b(rua|r\.?|av\.?|avenida|estrada|travessa|alameda|rodovia|pra[çc]a|largo|ladeira|rod\.?|via\b)""", RegexOption.IGNORE_CASE).containsMatchIn(sl)
            val hasNumberPattern = Regex("""[^\d],\s*\d{1,5}\b""").containsMatchIn(sl)
            return hasStreetWord || hasNumberPattern
        }
        fun looksLikeLabeledPlace(s: String): Boolean = !isAddressNoise(s) && s.trim().length >= 5

        fun afterLabel(labels: List<String>): String? {
            for (i in texts.indices) {
                val raw = texts[i].trim()
                val lowRaw = raw.lowercase(Locale.getDefault())
                for (label in labels) {
                    val pos = lowRaw.indexOf(label)
                    if (pos < 0) continue
                    val tail = raw.substring(pos + label.length).trim().trimStart(':', '-', '–', '—', ' ').trim()
                    if (tail.isNotBlank() && (looksLikeAddress(tail) || looksLikeLabeledPlace(tail))) return tail
                    for (j in (i + 1)..minOf(i + 3, texts.size - 1)) {
                        val cand = texts[j].trim()
                        if (looksLikeAddress(cand) || looksLikeLabeledPlace(cand)) return cand
                    }
                }
            }
            return null
        }

        val labeledOrigin = afterLabel(listOf("origem", "embarque", "buscar em", "pickup", "de:"))
        val labeledDest = afterLabel(listOf("destino", "desembarque", "dropoff", "para:"))

        val labeledStops = ArrayList<String>()
        for (i in texts.indices) {
            val raw = texts[i].trim()
            val m = Regex("""(?i)(?:parada|stop)\s*\d*\s*[:\-–—]\s*(.+)$""").find(raw)
            val inline = m?.groupValues?.getOrNull(1)?.trim()
            if (!inline.isNullOrBlank() && looksLikeLabeledPlace(inline)) labeledStops.add(inline)
            else if (Regex("""(?i)^\s*(?:parada|stop)\s*\d*\s*[:\-–—]?\s*$""").matches(raw)) {
                for (j in (i + 1)..minOf(i + 2, texts.size - 1)) {
                    val cand = texts[j].trim()
                    if (looksLikeAddress(cand) || looksLikeLabeledPlace(cand)) { labeledStops.add(cand); break }
                }
            }
        }

        val legLineRe = Regex("""(?:\b\d{1,3}\s*min(?:utos)?\b|\b\d{1,3}(?:[.,]\d+)?\s*km\b)""", RegexOption.IGNORE_CASE)
        val nearLegCandidates = ArrayList<String>()
        for (i in texts.indices) {
            if (!legLineRe.containsMatchIn(texts[i])) continue
            for (j in (i + 1)..minOf(i + 5, texts.size - 1)) {
                val cand = texts[j].trim()
                val nl = cand.lowercase(Locale.getDefault())
                if (nl.contains("aceitar") || nl.contains("selecionar") || nl.contains("escolher")) break
                if (looksLikeAddress(cand)) { nearLegCandidates.add(cand); break }
            }
        }

        val globalCandidates = texts.map { it.trim() }.filter { looksLikeAddress(it) }
        val ordered = (nearLegCandidates + globalCandidates).distinct()
        var origem = labeledOrigin ?: ordered.firstOrNull()
        var destino = labeledDest ?: ordered.lastOrNull()?.takeIf { it != origem }
        val middle = ordered.filter { it != origem && it != destino }
        val stopAddresses = (labeledStops + middle)
            .filter { it != origem && it != destino }
            .distinct()
            .let { if (paradas > 0) it.take(paradas) else emptyList() }
        if (destino == null && ordered.size >= 2) destino = ordered.last()
        if (origem == null && ordered.isNotEmpty()) origem = ordered.first()
'''
s = s[:a] + address_block + s[b:]

helper = r'''
    private fun buildFlashNotificationBitmap(
        plat: String,
        verdict: String,
        overallGrade: String,
        metrics: List<FlashCard.Metric>,
        declineReason: String?
    ): Bitmap? = try {
        val width = 900
        val height = if (declineReason.isNullOrBlank()) 300 else 350
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        fun gradeColor(g: String) = when (g) {
            "g" -> Color.rgb(16, 185, 129)
            "a" -> Color.rgb(245, 158, 11)
            else -> Color.rgb(239, 68, 68)
        }
        paint.color = Color.rgb(17, 19, 24)
        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), 34f, 34f, paint)
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textSize = 34f
        paint.color = Color.WHITE
        canvas.drawText("MōB Flash · $plat", 38f, 52f, paint)
        paint.textSize = 27f
        paint.color = gradeColor(overallGrade)
        canvas.drawText(verdict, 38f, 90f, paint)
        val shown = metrics.take(4)
        if (shown.isNotEmpty()) {
            val gap = 14f
            val left = 38f
            val usable = width - 76f - gap * (shown.size - 1)
            val tileW = usable / shown.size
            val top = 116f
            val bottom = 255f
            shown.forEachIndexed { idx, m ->
                val x = left + idx * (tileW + gap)
                paint.color = Color.rgb(31, 35, 43)
                canvas.drawRoundRect(RectF(x, top, x + tileW, bottom), 20f, 20f, paint)
                paint.color = gradeColor(m.grade)
                canvas.drawRoundRect(RectF(x, top, x + tileW, top + 10f), 10f, 10f, paint)
                paint.textSize = 24f
                paint.color = Color.rgb(148, 163, 184)
                canvas.drawText(m.label.take(12), x + 16f, top + 48f, paint)
                paint.textSize = if (m.value.length > 9) 31f else 38f
                paint.color = Color.WHITE
                canvas.drawText(m.value, x + 16f, top + 101f, paint)
            }
        }
        if (!declineReason.isNullOrBlank()) {
            paint.textSize = 24f
            paint.color = Color.rgb(248, 113, 113)
            canvas.drawText("Motivo: ${declineReason.take(60)}", 38f, height - 35f, paint)
        }
        bmp
    } catch (_: Exception) { null }

'''
fn = '    private fun showRouteNotification(\n'
pos = s.index(fn)
s = s[:pos] + helper + s[pos:]
start = s.index('    private fun showRouteNotification(', pos + len(helper))
brace = s.index('{', start)
depth = 0
end = None
for i in range(brace, len(s)):
    if s[i] == '{': depth += 1
    elif s[i] == '}':
        depth -= 1
        if depth == 0:
            end = i + 1
            break
assert end is not None
new_fn = r'''    private fun showRouteNotification(
        plat: String,
        offer: Offer,
        overallGrade: String,
        metrics: List<FlashCard.Metric>,
        declineReason: String?
    ) {
        val origem = offer.origem
        val destino = offer.destino
        val stops = offer.stopAddresses
        val verdict = when (overallGrade) { "g" -> "ACEITAR"; "a" -> "ANALISAR"; else -> "RECUSAR" }
        val key = listOf(plat, offer.valor?.toString() ?: "", origem ?: "", destino ?: "", stops.joinToString("|"), overallGrade, metrics.joinToString("|") { "${it.label}:${it.value}:${it.grade}" }, declineReason ?: "").joinToString("|")
        if (key == lastNotifKey) return
        lastNotifKey = key

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(NOTIF_CHANNEL_ROUTE, "MōB Flash — última oferta", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Resumo silencioso da última oferta analisada pelo MōB Flash"
                setSound(null, null); enableVibration(false); enableLights(false)
            }
            nm.createNotificationChannel(ch)
        }
        fun mapIntent(addr: String): PendingIntent {
            val navUri = Uri.parse("google.navigation:q=" + Uri.encode(addr))
            val navIntent = Intent(Intent.ACTION_VIEW, navUri).apply { setPackage("com.google.android.apps.maps") }
            val geoUri = Uri.parse("geo:0,0?q=" + Uri.encode(addr))
            val geoIntent = Intent(Intent.ACTION_VIEW, geoUri).apply { setPackage("com.google.android.apps.maps") }
            val genericIntent = Intent(Intent.ACTION_VIEW, geoUri)
            val chosen = when { navIntent.resolveActivity(packageManager) != null -> navIntent; geoIntent.resolveActivity(packageManager) != null -> geoIntent; else -> genericIntent }
            return PendingIntent.getActivity(this, addr.hashCode(), chosen, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val valorTxt = offer.valor?.let { "R$ ${fmtBr(it)}" }
        val titulo = "$plat · " + listOfNotNull(valorTxt, verdict).joinToString(" · ")
        val lines = ArrayList<String>()
        if (offer.minPickup != null || offer.kmPickup != null) {
            val pickup = listOfNotNull(offer.minPickup?.let { "$it min" }, offer.kmPickup?.let { "${fmtBr(it)} km" }).joinToString(" · ")
            if (pickup.isNotEmpty()) lines.add("Até o passageiro: $pickup")
        }
        val routeMin = offer.minTrip ?: offer.min
        val routeKm = offer.kmTrip ?: offer.km
        if (routeMin != null || routeKm != null) {
            val route = listOfNotNull(routeMin?.let { "$it min" }, routeKm?.let { "${fmtBr(it)} km" }).joinToString(" · ")
            if (route.isNotEmpty()) lines.add("Rota da corrida: $route")
        }
        origem?.let { lines.add("Origem: $it") }
        stops.forEachIndexed { i, stop -> lines.add("Parada ${i + 1}: $stop") }
        if (offer.paradas > stops.size) {
            val faltantes = offer.paradas - stops.size
            lines.add(if (faltantes == 1) "1 parada adicional sem endereço legível" else "$faltantes paradas adicionais sem endereço legível")
        }
        destino?.let { lines.add("Destino: $it") }
        if (origem == null && destino == null && stops.isEmpty()) lines.add("Endereços ainda não reconhecidos nesta oferta")
        val metricText = metrics.joinToString(" · ") { "${it.label} ${it.value}" }
        lines.add(if (metricText.isNotBlank()) "Flash: $verdict · $metricText" else "Flash: $verdict")
        declineReason?.let { lines.add("Motivo: $it") }
        val resumo = lines.joinToString("\n")
        val flashImage = buildFlashNotificationBitmap(plat, verdict, overallGrade, metrics, declineReason)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, NOTIF_CHANNEL_ROUTE) else { @Suppress("DEPRECATION") Notification.Builder(this) }
        builder.setContentTitle(titulo).setContentText(lines.firstOrNull() ?: titulo).setSmallIcon(R.mipmap.ic_launcher).setPriority(Notification.PRIORITY_LOW).setOnlyAlertOnce(true).setAutoCancel(false).setOngoing(false)
        if (flashImage != null) {
            builder.setStyle(Notification.BigPictureStyle().bigPicture(flashImage).setBigContentTitle(titulo).setSummaryText(resumo))
            val thumbW = 160
            val thumbH = (flashImage.height.toDouble() / flashImage.width * thumbW).toInt().coerceAtLeast(1)
            builder.setLargeIcon(Bitmap.createScaledBitmap(flashImage, thumbW, thumbH, true))
        } else builder.setStyle(Notification.BigTextStyle().bigText(resumo))
        if (origem != null) builder.addAction(Notification.Action.Builder(null, "Origem", mapIntent(origem)).build())
        if (destino != null) builder.addAction(Notification.Action.Builder(null, "Destino", mapIntent(destino)).build())
        try { nm.notify(4103, builder.build()) } catch (_: Exception) {}
    }'''
s = s[:start] + new_fn + s[end:]
trip.write_text(s)

nf = Path('app/src/main/java/io/github/yurisilva90/smartmobi/DriverNotificationListenerService.kt')
n = nf.read_text()
start = n.index('    private fun extractRouteInfo(')
brace = n.index('{', start)
depth = 0
end = None
for i in range(brace, len(n)):
    if n[i] == '{': depth += 1
    elif n[i] == '}':
        depth -= 1
        if depth == 0:
            end = i + 1
            break
assert end is not None
route_fn = r'''    private fun extractRouteInfo(parts: List<String>, mins: List<Int>): RouteInfo {
        val cleaned = parts.flatMap { it.split('\n') }.map { it.replace(Regex("\\s+"), " ").trim() }.filter { it.isNotBlank() }
        fun noise(line: String): Boolean {
            val low = line.lowercase(Locale("pt", "BR")).trim()
            if (low.length < 5 || low.contains("r$")) return true
            if (Regex("""^\(?\s*\d{1,3}(?:[.,]\d+)?\s*(?:min(?:utos)?|km|m)\b""", RegexOption.IGNORE_CASE).containsMatchIn(low)) return true
            if (low in setOf("online", "offline", "buscando", "aceitar", "recusar")) return true
            return false
        }
        fun addressLike(line: String): Boolean {
            if (noise(line)) return false
            val street = Regex("""\b(rua|r\.?|av\.?|avenida|estrada|travessa|alameda|rodovia|pra[çc]a|largo|ladeira|via)\b""", RegexOption.IGNORE_CASE).containsMatchIn(line)
            val numbered = Regex("""[^\d],\s*\d{1,5}\b""").containsMatchIn(line)
            return street || numbered
        }
        fun labeled(labels: List<String>): String? {
            for (i in cleaned.indices) {
                val line = cleaned[i]; val low = line.lowercase(Locale("pt", "BR"))
                for (label in labels) {
                    val pos = low.indexOf(label); if (pos < 0) continue
                    val tail = line.substring(pos + label.length).trim().trimStart(':','-','–','—',' ').trim()
                    if (!noise(tail)) return tail
                    for (j in (i + 1)..minOf(i + 2, cleaned.size - 1)) if (!noise(cleaned[j]) && (addressLike(cleaned[j]) || cleaned[j].length >= 5)) return cleaned[j]
                }
            }
            return null
        }
        var origin = labeled(listOf("origem", "embarque", "buscar em", "pickup", "de:"))
        var dest = labeled(listOf("destino", "desembarque", "dropoff", "para:"))
        val stops = ArrayList<String>(); var declaredStops = 0
        cleaned.forEachIndexed { i, line ->
            Regex("""\b(\d{1,2})\s*paradas?\b""", RegexOption.IGNORE_CASE).find(line)?.let { declaredStops = maxOf(declaredStops, it.groupValues[1].toIntOrNull() ?: 0) }
            Regex("""(?i)(?:parada|stop)\s*\d*\s*[:\-–—]\s*(.+)$""").find(line)?.groupValues?.getOrNull(1)?.trim()?.let { if (!noise(it)) stops.add(it) }
            if (Regex("""(?i)^\s*(?:parada|stop)\s*\d*\s*[:\-–—]?\s*$""").matches(line)) cleaned.getOrNull(i + 1)?.let { if (!noise(it)) stops.add(it) }
        }
        val candidates = cleaned.filter { addressLike(it) }.distinct()
        if (origin == null) origin = candidates.firstOrNull()
        if (dest == null) dest = candidates.lastOrNull()?.takeIf { it != origin }
        if (stops.isEmpty() && declaredStops > 0) stops.addAll(candidates.filter { it != origin && it != dest }.take(declaredStops))
        return RouteInfo(origin, dest, maxOf(declaredStops, stops.size), stops.distinct(), mins.lastOrNull()?.times(60))
    }'''
n = n[:start] + route_fn + n[end:]
nf.write_text(n)

gradle = Path('app/build.gradle')
g = gradle.read_text()
g = re.sub(r'// 1\.3\.12:.*?\n', '// 1.3.13: imagem do Flash na notificação + rota/endereço robustos.\n', g, count=1)
g = re.sub(r'versionCode\s+225\b', 'versionCode 226', g, count=1)
g = re.sub(r'versionName\s+"1\.3\.12"', 'versionName "1.3.13"', g, count=1)
gradle.write_text(g)
