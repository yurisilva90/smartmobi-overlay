from pathlib import Path
import re

ROOT = Path('.')

# ───────────────── FlashCard: render visual do próprio MōB Flash ─────────────────
p = ROOT / 'app/src/main/java/io/github/yurisilva90/smartmobi/FlashCard.kt'
s = p.read_text()
if 'import android.graphics.Bitmap\n' not in s:
    s = s.replace('import android.graphics.Color\n', 'import android.graphics.Bitmap\nimport android.graphics.Canvas\nimport android.graphics.Color\n', 1)
if 'import java.util.concurrent.CountDownLatch\n' not in s:
    s = s.replace('import java.util.Locale\n', 'import java.util.Locale\nimport java.util.concurrent.CountDownLatch\nimport java.util.concurrent.TimeUnit\n', 1)

anchor = '    fun keepAlive(autoHideMs: Long = 20000L) {'
assert anchor in s
if 'fun renderNotificationBitmap(' not in s:
    method = r'''    /**
     * Renderiza uma cópia visual do próprio MōB Flash para a notificação.
     * Não usa o print bruto da oferta: a imagem mostra o veredito, os mesmos
     * indicadores do overlay e, abaixo, os dados úteis da rota.
     */
    fun renderNotificationBitmap(
        platform: String,
        overallGrade: String,
        metrics: List<Metric>,
        totalMin: Int,
        totalKm: Double,
        declineReason: String? = null,
        detailLines: List<String> = emptyList()
    ): Bitmap? {
        fun renderNow(): Bitmap? {
            return try {
                val cardWidthPx = widthFor(metrics.size)
                val imageWidthPx = maxOf(cardWidthPx, dp(344))
                val outer = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(dp(8), dp(8), dp(8), dp(10))
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dpf(14)
                        setColor(Color.parseColor("#101119"))
                    }
                }

                val verdict = when (overallGrade) {
                    "g" -> "ACEITAR"
                    "a" -> "ANALISAR"
                    else -> "RECUSAR"
                }
                outer.addView(TextView(context).apply {
                    text = "MōB Flash · $verdict"
                    textSize = 13f
                    setTypeface(Typeface.DEFAULT_BOLD)
                    setTextColor(colorOf(overallGrade))
                    gravity = Gravity.CENTER
                    setPadding(dp(4), dp(2), dp(4), dp(7))
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ))

                val card = buildCard(
                    platform, overallGrade, metrics, totalMin, totalKm,
                    cardWidthPx, declineReason
                )
                outer.addView(card, LinearLayout.LayoutParams(
                    cardWidthPx, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { gravity = Gravity.CENTER_HORIZONTAL })

                val cleanLines = detailLines.filter { it.isNotBlank() }.take(9)
                if (cleanLines.isNotEmpty()) {
                    outer.addView(android.view.View(context).apply {
                        setBackgroundColor(Color.parseColor("#22FFFFFF"))
                    }, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
                    ).apply { topMargin = dp(9); bottomMargin = dp(6) })
                    cleanLines.forEach { line ->
                        outer.addView(TextView(context).apply {
                            text = line
                            textSize = 11.5f
                            setTextColor(Color.parseColor("#F8FAFC"))
                            maxLines = 2
                            setPadding(dp(4), dp(2), dp(4), dp(2))
                        }, LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ))
                    }
                }

                val wSpec = android.view.View.MeasureSpec.makeMeasureSpec(
                    imageWidthPx, android.view.View.MeasureSpec.EXACTLY
                )
                val hSpec = android.view.View.MeasureSpec.makeMeasureSpec(
                    0, android.view.View.MeasureSpec.UNSPECIFIED
                )
                outer.measure(wSpec, hSpec)
                val height = outer.measuredHeight.coerceAtLeast(dp(1))
                outer.layout(0, 0, imageWidthPx, height)
                Bitmap.createBitmap(imageWidthPx, height, Bitmap.Config.ARGB_8888).also { bmp ->
                    outer.draw(Canvas(bmp))
                }
            } catch (_: Exception) { null }
        }

        if (Looper.myLooper() == Looper.getMainLooper()) return renderNow()
        var result: Bitmap? = null
        val latch = CountDownLatch(1)
        handler.post {
            try { result = renderNow() } finally { latch.countDown() }
        }
        return try {
            if (latch.await(1200, TimeUnit.MILLISECONDS)) result else null
        } catch (_: Exception) { null }
    }

'''
    s = s.replace(anchor, method + anchor, 1)
p.write_text(s)

# ───────────────── TripReader: parser + notificação ─────────────────
p = ROOT / 'app/src/main/java/io/github/yurisilva90/smartmobi/TripReaderService.kt'
s = p.read_text()

# Substitui só o bloco de extração de endereço dentro de parseOffer.
start_marker = '        // endereços: a linha logo depois de uma "perna" (tempo+distância)'
ret_marker = '        return Offer(valor, km, min, rkmDirect, nota, origem, destino'
start = s.index(start_marker)
end = s.index(ret_marker, start)
address_block = r'''        // Endereços da oferta. Na 99 o endereço pode ficar várias linhas
        // abaixo da perna ou ser quebrado em duas linhas. A leitura fica
        // limitada ao bloco da oferta para não capturar chat/navegação do fundo.
        fun cleanAddress(raw: String): String = raw
            .replace(Regex("""^[•·]\s*"""), "")
            .trim()

        fun looksLikeAddress(raw: String): Boolean {
            val sl = cleanAddress(raw).lowercase(Locale.getDefault())
            if (sl.length < 6) return false
            if (sl.contains("não afeta") || sl.contains("nao afeta")) return false
            if (Regex("""^\(?\d{1,3}([.,]\d+)?\s*(min|km|m)\b""").containsMatchIn(sl)) return false
            if (Regex("""^[x%\d.,\s]+$""").containsMatchIn(sl)) return false
            if (sl.contains("r$") || sl.contains("tarifa") || sl.contains("taxa de espera")) return false
            if (sl.contains("espera longa") || sl.contains("perfil essencial") || sl.contains("perfil premium") || sl.contains("perfil prata")) return false
            if (sl == "online" || sl == "buscando" || sl == "offline" || sl == "conectar") return false
            if (sl.contains("pgto. no app") || sl == "dinheiro" || sl == "negocia" || sl == "qr code") return false
            if (Regex("""[1-5][.,]\d{2}.{0,15}corrid""").containsMatchIn(sl)) return false
            if (sl.contains("verif.") || sl.contains("cpf e cart")) return false
            if (sl.contains("aceitar") || sl.contains("selecionar") || sl.contains("escolher")) return false
            val street = Regex("""\b(rua|r\.?|av\.?|avenida|estrada|travessa|alameda|rodovia|pra[çc]a|largo|ladeira|rod\.?|via\b)""", RegexOption.IGNORE_CASE).containsMatchIn(sl)
            val numbered = Regex("""[^\d],\s*\d{1,5}\b""").containsMatchIn(sl)
            return street || numbered
        }

        fun normAddress(raw: String): String = cleanAddress(raw)
            .lowercase(Locale.getDefault())
            .replace(Regex("""[\s,.;:–—-]+"""), " ")
            .trim()

        fun candidateAt(index: Int): String? {
            if (index !in texts.indices) return null
            val cur = cleanAddress(texts[index])
            if (looksLikeAddress(cur)) return cur
            // Alguns OCRs quebram "Rua X" e "120 - Bairro" em linhas distintas.
            val hasStreet = Regex("""\b(rua|r\.?|av\.?|avenida|estrada|travessa|alameda|rodovia|pra[çc]a|largo|ladeira|rod\.?|via\b)""", RegexOption.IGNORE_CASE).containsMatchIn(cur)
            if (hasStreet && index + 1 in texts.indices) {
                val next = cleanAddress(texts[index + 1])
                if (Regex("""^\d{1,5}\b""").containsMatchIn(next)) {
                    val joinedAddress = "$cur, $next"
                    if (looksLikeAddress(joinedAddress)) return joinedAddress
                }
            }
            return null
        }

        val legLineRe = Regex("""^\(?\s*\d{1,3}\s*min(?:utos)?""", RegexOption.IGNORE_CASE)
        val legIndexes = texts.indices.filter { legLineRe.containsMatchIn(texts[it]) }
        val blockStart = legIndexes.firstOrNull() ?: 0
        val actionIndex = ((blockStart + 1) until texts.size).firstOrNull { i ->
            val l = texts[i].lowercase(Locale.getDefault())
            l.contains("aceitar") || l.contains("selecionar") || l.contains("escolher") || l.contains("recusar")
        }
        val blockEnd = minOf(actionIndex ?: (blockStart + 24), texts.size - 1)

        val indexed = ArrayList<Pair<Int, String>>()
        fun addCandidate(index: Int, value: String?) {
            val v = value?.let(::cleanAddress)?.takeIf { looksLikeAddress(it) } ?: return
            val n = normAddress(v)
            if (indexed.none { normAddress(it.second) == n }) indexed.add(index to v)
        }

        // Procura depois de cada perna até a próxima perna/ação, com uma janela
        // maior que a antiga (3 linhas), suficiente para layouts reais da 99.
        legIndexes.forEachIndexed { pos, legIdx ->
            val nextLeg = legIndexes.getOrNull(pos + 1)
            val scanEnd = minOf(nextLeg?.minus(1) ?: blockEnd, legIdx + 8, blockEnd)
            if (legIdx + 1 <= scanEnd) {
                for (j in (legIdx + 1)..scanEnd) {
                    val c = candidateAt(j)
                    if (c != null) { addCandidate(j, c); break }
                }
            }
        }

        fun labeledTail(line: String, labels: List<String>): String? {
            val lowLine = line.lowercase(Locale.getDefault())
            for (label in labels) {
                val idx = lowLine.indexOf(label)
                if (idx >= 0) {
                    val tail = cleanAddress(line.substring(idx + label.length).trimStart(':', '-', '–', '—', ' '))
                    if (looksLikeAddress(tail)) return tail
                }
            }
            return null
        }

        var labeledOrigin: String? = null
        var labeledDest: String? = null
        val labeledStops = ArrayList<String>()
        if (blockStart <= blockEnd) {
            for (i in blockStart..blockEnd) {
                val line = texts[i]
                if (labeledOrigin == null) labeledOrigin = labeledTail(line, listOf("origem", "embarque", "buscar em", "pickup"))
                if (labeledDest == null) labeledDest = labeledTail(line, listOf("destino", "desembarque", "dropoff", "para"))
                Regex("""(?i)(?:parada\s*\d+|\d+[ªa]?\s*parada)\s*[:\-–—]\s*(.+)$""")
                    .find(line)?.groupValues?.getOrNull(1)?.let {
                        if (looksLikeAddress(it)) labeledStops.add(cleanAddress(it))
                    }
                addCandidate(i, candidateAt(i))
            }
        }

        val ordered = indexed.sortedBy { it.first }.map { it.second }.fold(ArrayList<String>()) { acc, v ->
            val n = normAddress(v)
            if (acc.none { normAddress(it) == n }) acc.add(v)
            acc
        }
        val origem = labeledOrigin ?: ordered.firstOrNull()
        val destino = labeledDest ?: ordered.lastOrNull()?.takeIf {
            origem == null || normAddress(it) != normAddress(origem)
        }
        val stopAddresses = if (labeledStops.isNotEmpty()) {
            labeledStops.distinctBy(::normAddress).take(maxOf(paradas, labeledStops.size))
        } else if (paradas > 0) {
            ordered.filter { a ->
                (origem == null || normAddress(a) != normAddress(origem)) &&
                (destino == null || normAddress(a) != normAddress(destino))
            }.take(paradas)
        } else emptyList()

'''
s = s[:start] + address_block + s[end:]

# Não duplica o bitmap bruto da tela para a notificação; preserva o original só no snapshot.
s = s.replace('''        val notificationImage = try { bmp?.copy(Bitmap.Config.ARGB_8888, false) } catch (_: Exception) { null }\n        saveSnapshot(plat, bmp, overallGrade, metrics, min ?: 0, km, offer, texts)\n        showRouteNotification(plat, offer, overallGrade, metrics, declineReason, notificationImage)''', '''        saveSnapshot(plat, bmp, overallGrade, metrics, min ?: 0, km, offer, texts)\n        showRouteNotification(plat, offer, overallGrade, metrics, declineReason)''', 1)

# Assinatura antiga com offerImage -> assinatura limpa.
s = s.replace('''        metrics: List<FlashCard.Metric>,\n        declineReason: String?,\n        offerImage: Bitmap? = null\n    ) {''', '''        metrics: List<FlashCard.Metric>,\n        declineReason: String?\n    ) {''', 1)

# A notificação deve existir mesmo quando o endereço ainda estiver refinando.
s = s.replace('''        if (origem == null && destino == null && stops.isEmpty()) return\n\n''', '', 1)

old_key = '''        val key = listOf(\n            plat, offer.valor?.toString() ?: "", origem ?: "", destino ?: "",\n            stops.joinToString("|"), overallGrade, declineReason ?: ""\n        ).joinToString("|")'''
new_key = '''        val key = listOf(\n            plat, offer.valor?.toString() ?: "", origem ?: "", destino ?: "",\n            stops.joinToString("|"), overallGrade, declineReason ?: "",\n            offer.minPickup?.toString() ?: "", offer.kmPickup?.toString() ?: "",\n            offer.minTrip?.toString() ?: "", offer.kmTrip?.toString() ?: "",\n            metrics.joinToString("|") { "${it.label}:${it.value}:${it.grade}" }\n        ).joinToString("|")'''
assert old_key in s
s = s.replace(old_key, new_key, 1)

old_builder = '''        builder.setContentTitle(titulo)\n            .setStyle(if (offerImage != null) Notification.BigPictureStyle().bigPicture(offerImage).setSummaryText(resumo) else Notification.BigTextStyle().bigText(resumo))\n            .setContentText(lines.firstOrNull() ?: titulo)'''
new_builder = '''        val visualLines = lines.filterNot { it.startsWith("Flash:") || it.startsWith("Motivo:") }\n        val flashPicture = flashCard.renderNotificationBitmap(\n            plat, overallGrade, metrics, routeMin ?: 0, routeKm ?: 0.0, declineReason, visualLines\n        )\n        builder.setContentTitle(titulo)\n            .setContentText(lines.firstOrNull() ?: titulo)'''
assert old_builder in s
s = s.replace(old_builder, new_builder, 1)

old_tail = '''            .setAutoCancel(false)\n            .setOngoing(false)\n        if (origem != null) builder.addAction'''
new_tail = '''            .setAutoCancel(false)\n            .setOngoing(false)\n            .setSilent(true)\n        if (flashPicture != null) {\n            builder.setStyle(Notification.BigPictureStyle()\n                .bigPicture(flashPicture)\n                .setBigContentTitle(titulo)\n                .setSummaryText(lines.take(2).joinToString(" · ")))\n        } else {\n            builder.setStyle(Notification.BigTextStyle().bigText(resumo))\n        }\n        if (origem != null) builder.addAction'''
assert old_tail in s
s = s.replace(old_tail, new_tail, 1)

s = s.replace('''        try { nm.notify(4103, builder.build()) } catch (_: Exception) {} finally { try { offerImage?.recycle() } catch (_: Exception) {} }''', '''        try { nm.notify(4103, builder.build()) } catch (_: Exception) {}''', 1)
p.write_text(s)

# ───────────────── Notificações oficiais Uber/99: rota estruturada ─────────────────
p = ROOT / 'app/src/main/java/io/github/yurisilva90/smartmobi/DriverNotificationListenerService.kt'
s = p.read_text()
start = s.index('    private fun extractRouteInfo(parts: List<String>, mins: List<Int>): RouteInfo {')
end = s.index('\n    private fun hash(', start)
route_fn = r'''    private fun extractRouteInfo(parts: List<String>, mins: List<Int>): RouteInfo {
        val cleaned = parts.flatMap { it.split('\n') }
            .map { it.replace(Regex("""\s+"""), " ").trim() }
            .filter { it.isNotBlank() }

        fun looksLikeAddress(raw: String): Boolean {
            val line = raw.trim()
            val low = line.lowercase(Locale("pt", "BR"))
            if (line.length < 6 || low.contains("r$")) return false
            if (Regex("""^\d{1,3}(?:[.,]\d+)?\s*(km|m|min)\b""", RegexOption.IGNORE_CASE).containsMatchIn(low)) return false
            if (low.contains("aceitar") || low.contains("recusar") || low.contains("corrida disponível")) return false
            val street = Regex("""\b(rua|r\.?|av\.?|avenida|estrada|travessa|alameda|rodovia|pra[çc]a|largo|ladeira|via)\b""", RegexOption.IGNORE_CASE).containsMatchIn(low)
            val numbered = Regex("""[^\d],\s*\d{1,5}\b""").containsMatchIn(low)
            return street || numbered
        }
        fun norm(raw: String) = raw.lowercase(Locale("pt", "BR"))
            .replace(Regex("""[\s,.;:–—-]+"""), " ").trim()
        fun valueAfterLabel(line: String, labels: List<String>): String? {
            val low = line.lowercase(Locale("pt", "BR"))
            for (label in labels) {
                val i = low.indexOf(label)
                if (i >= 0) {
                    val tail = line.substring(i + label.length).trim().trimStart(':', '-', '–', '—').trim()
                    if (looksLikeAddress(tail)) return tail
                }
            }
            return null
        }

        val originLabels = listOf("origem", "embarque", "buscar em", "pickup")
        val destLabels = listOf("destino", "desembarque", "dropoff", "para")
        var origin: String? = null
        var dest: String? = null
        val stops = ArrayList<String>()
        var declaredStops = 0
        cleaned.forEach { line ->
            Regex("""\b(\d{1,2})\s*paradas?\b""", RegexOption.IGNORE_CASE).find(line)?.let {
                declaredStops = maxOf(declaredStops, it.groupValues[1].toIntOrNull() ?: 0)
            }
            if (origin == null) origin = valueAfterLabel(line, originLabels)
            if (dest == null) dest = valueAfterLabel(line, destLabels)
            Regex("""(?i)(?:parada\s*\d+|\d+[ªa]?\s*parada)\s*[:\-–—]\s*(.+)$""")
                .find(line)?.groupValues?.getOrNull(1)?.trim()?.let {
                    if (looksLikeAddress(it)) stops.add(it)
                }
        }
        val routeCandidates = cleaned.filter(::looksLikeAddress).distinctBy(::norm)
        if (origin == null) origin = routeCandidates.firstOrNull()
        if (dest == null && routeCandidates.size >= 2) dest = routeCandidates.lastOrNull()
        if (stops.isEmpty() && declaredStops > 0 && routeCandidates.size > 2) {
            stops.addAll(routeCandidates.subList(1, routeCandidates.size - 1).take(declaredStops))
        }
        return RouteInfo(origin, dest, maxOf(declaredStops, stops.size), stops.distinctBy(::norm), mins.lastOrNull()?.times(60))
    }
'''
s = s[:start] + route_fn + s[end:]
p.write_text(s)

# ───────────────── Versão ─────────────────
p = ROOT / 'app/build.gradle'
s = p.read_text()
s = s.replace('versionCode 226', 'versionCode 227')
s = s.replace('versionName "1.3.13"', 'versionName "1.3.14"')
# Atualiza só a linha de comentário de versão, independentemente do texto atual.
s = re.sub(r'^// 1\.3\.[0-9]+:.*$', '// 1.3.14: visual do Flash na notificação + origem/destino/paradas robustos.', s, count=1, flags=re.M)
p.write_text(s)

# Invariantes finais
flash = (ROOT / 'app/src/main/java/io/github/yurisilva90/smartmobi/FlashCard.kt').read_text()
trip = (ROOT / 'app/src/main/java/io/github/yurisilva90/smartmobi/TripReaderService.kt').read_text()
drv = (ROOT / 'app/src/main/java/io/github/yurisilva90/smartmobi/DriverNotificationListenerService.kt').read_text()
assert 'fun renderNotificationBitmap(' in flash
assert 'MōB Flash · $verdict' in flash
assert 'val flashPicture = flashCard.renderNotificationBitmap(' in trip
assert 'legIdx + 8' in trip
assert 'id="' not in trip  # sanity: não inseriu HTML acidental
assert "parts.flatMap { it.split('\\n') }" in drv
assert 'versionCode 227' in (ROOT / 'app/build.gradle').read_text()
