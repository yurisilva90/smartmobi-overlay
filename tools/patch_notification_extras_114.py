from pathlib import Path

p = Path('app/src/main/java/io/github/yurisilva90/smartmobi/DriverNotificationListenerService.kt')
s = p.read_text()

old = '''        val parts = ArrayList<String>()
        listOf(title, text, big, sub, info, summary).filterTo(parts) { it.isNotBlank() }
        lines.filterTo(parts) { it.isNotBlank() }
        val joined = parts.joinToString(" ").replace(Regex("\\\\s+"), " ").trim()
'''
new = '''        // Lê também campos customizados internos dos extras. Uber/99 podem
        // expor rota/endereço fora de EXTRA_TEXT/BIG_TEXT. O conteúdo bruto
        // permanece apenas em memória; no banco persistimos só os campos
        // estruturados extraídos abaixo.
        val partSet = LinkedHashSet<String>()
        fun addPart(v: String?) { v?.trim()?.takeIf { it.isNotBlank() }?.let { partSet.add(it) } }
        listOf(title, text, big, sub, info, summary).forEach(::addPart)
        lines.forEach(::addPart)
        fun collectExtra(value: Any?, depth: Int = 0) {
            if (value == null || depth > 2) return
            when (value) {
                is CharSequence -> addPart(value.toString())
                is android.os.Bundle -> try {
                    value.keySet().forEach { key -> collectExtra(value.get(key), depth + 1) }
                } catch (_: Exception) {}
                is Array<*> -> value.forEach { collectExtra(it, depth + 1) }
                is Iterable<*> -> value.forEach { collectExtra(it, depth + 1) }
            }
        }
        try { extras?.keySet()?.forEach { key -> collectExtra(extras.get(key)) } } catch (_: Exception) {}
        val parts = ArrayList(partSet)
        val joined = parts.joinToString(" ").replace(Regex("\\\\s+"), " ").trim()
'''
assert old in s, 'parts block not found'
s = s.replace(old, new, 1)

old = '''        fun valueAfterLabel(line: String, labels: List<String>): String? {
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
'''
new = '''        fun looksLikeLabeledPlace(raw: String): Boolean {
            val line = raw.trim()
            val low = line.lowercase(Locale("pt", "BR"))
            if (line.length < 4 || low.contains("r$")) return false
            if (Regex("""^\\d{1,3}(?:[.,]\\d+)?\\s*(km|m|min)\\b""", RegexOption.IGNORE_CASE).containsMatchIn(low)) return false
            if (low.contains("aceitar") || low.contains("recusar") || low.contains("corrida disponível")) return false
            if (low in setOf("origem", "destino", "embarque", "desembarque", "pickup", "dropoff", "para")) return false
            return line.any { it.isLetter() }
        }
        fun valueAfterLabel(line: String, labels: List<String>): String? {
            val low = line.lowercase(Locale("pt", "BR"))
            for (label in labels) {
                val i = low.indexOf(label)
                if (i >= 0) {
                    val tail = line.substring(i + label.length).trim().trimStart(':', '-', '–', '—').trim()
                    if (looksLikeLabeledPlace(tail)) return tail
                }
            }
            return null
        }
'''
assert old in s, 'valueAfterLabel block not found'
s = s.replace(old, new, 1)

old = '''        cleaned.forEach { line ->
            Regex("""\\b(\\d{1,2})\\s*paradas?\\b""", RegexOption.IGNORE_CASE).find(line)?.let {
                declaredStops = maxOf(declaredStops, it.groupValues[1].toIntOrNull() ?: 0)
            }
            if (origin == null) origin = valueAfterLabel(line, originLabels)
            if (dest == null) dest = valueAfterLabel(line, destLabels)
            Regex("""(?i)(?:parada\\s*\\d+|\\d+[ªa]?\\s*parada)\\s*[:\\-–—]\\s*(.+)$""")
                .find(line)?.groupValues?.getOrNull(1)?.trim()?.let {
                    if (looksLikeAddress(it)) stops.add(it)
                }
        }
'''
new = '''        cleaned.forEachIndexed { idx, line ->
            Regex("""\\b(\\d{1,2})\\s*paradas?\\b""", RegexOption.IGNORE_CASE).find(line)?.let {
                declaredStops = maxOf(declaredStops, it.groupValues[1].toIntOrNull() ?: 0)
            }
            if (origin == null) origin = valueAfterLabel(line, originLabels)
            if (dest == null) dest = valueAfterLabel(line, destLabels)
            val bare = line.lowercase(Locale("pt", "BR")).trim().trimEnd(':')
            val next = cleaned.getOrNull(idx + 1)
            if (origin == null && originLabels.any { bare == it } && next != null && looksLikeLabeledPlace(next)) origin = next
            if (dest == null && destLabels.any { bare == it } && next != null && looksLikeLabeledPlace(next)) dest = next
            Regex("""(?i)(?:parada\\s*\\d+|\\d+[ªa]?\\s*parada)\\s*[:\\-–—]\\s*(.+)$""")
                .find(line)?.groupValues?.getOrNull(1)?.trim()?.let {
                    if (looksLikeLabeledPlace(it)) stops.add(it)
                }
            if (Regex("""(?i)^(?:parada\\s*\\d+|\\d+[ªa]?\\s*parada):?$""").matches(line.trim()) && next != null && looksLikeLabeledPlace(next)) {
                stops.add(next)
            }
        }
'''
assert old in s, 'route loop block not found'
s = s.replace(old, new, 1)
p.write_text(s)

# TripReader: a versão atual já tem BigPicture. LargeIcon garante uma
# referência visual também no estado recolhido em Samsung/One UI.
t = Path('app/src/main/java/io/github/yurisilva90/smartmobi/TripReaderService.kt')
q = t.read_text()
needle = '''        if (flashPicture != null) {
            builder.setStyle(Notification.BigPictureStyle()
'''
if needle in q:
    q = q.replace(needle, '''        if (flashPicture != null) {
            builder.setLargeIcon(flashPicture)
            builder.setStyle(Notification.BigPictureStyle()
''', 1)
# Se outro patch já colocou setLargeIcon, não duplica.
assert 'if (flashPicture != null)' in q and 'setLargeIcon(' in q

t.write_text(q)
