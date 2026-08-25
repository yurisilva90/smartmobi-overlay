from pathlib import Path
import re


def rep(text, old, new, label, count=None):
    n = text.count(old)
    if n == 0:
        raise SystemExit(f"{label}: trecho nao encontrado")
    if count is not None and n != count:
        raise SystemExit(f"{label}: esperado {count}, encontrado {n}")
    return text.replace(old, new)


def replace_function(text, signature, replacement):
    start = text.find(signature)
    if start < 0:
        raise SystemExit(f"funcao nao encontrada: {signature}")
    brace = text.find("{", start)
    depth = 0
    i = brace
    in_str = False
    esc = False
    while i < len(text):
        c = text[i]
        if in_str:
            if esc:
                esc = False
            elif c == "\\":
                esc = True
            elif c == '"':
                in_str = False
        else:
            if c == '"':
                in_str = True
            elif c == "{":
                depth += 1
            elif c == "}":
                depth -= 1
                if depth == 0:
                    return text[:start] + replacement + text[i + 1:]
        i += 1
    raise SystemExit(f"fim da funcao nao encontrado: {signature}")


# TripReaderService
p = Path("app/src/main/java/io/github/yurisilva90/smartmobi/TripReaderService.kt")
s = p.read_text()
s = rep(s,
    "        val corridas: Int? = null, val paradas: Int = 0,\n        val multiplicador: Double? = null\n",
    "        val corridas: Int? = null, val paradas: Int = 0,\n        val multiplicador: Double? = null,\n        val stopAddresses: List<String> = emptyList()\n",
    "Offer stopAddresses", 1)
s = rep(s,
    "        val origem = addrCandidates.getOrNull(0)\n        val destino = addrCandidates.getOrNull(1)\n\n        return Offer(valor, km, min, rkmDirect, nota, origem, destino, legs, kmPickup, kmTrip, minPickup, minTrip, dinamico, corridas, paradas, multiplicador)\n",
    "        val origem = addrCandidates.firstOrNull()\n        val destino = if (addrCandidates.size >= 2) addrCandidates.lastOrNull() else null\n        val stopAddresses = if (paradas > 0 && addrCandidates.size > 2)\n            addrCandidates.subList(1, addrCandidates.size - 1).take(paradas)\n        else emptyList()\n\n        return Offer(valor, km, min, rkmDirect, nota, origem, destino, legs, kmPickup, kmTrip, minPickup, minTrip, dinamico, corridas, paradas, multiplicador, stopAddresses)\n",
    "parse enderecos/paradas", 1)
s = rep(s,
    "                origin = offer.origem, dest = offer.destino\n",
    "                origin = offer.origem, dest = offer.destino,\n                stopCount = offer.paradas, stopAddresses = offer.stopAddresses\n",
    "snapshot paradas")
s = rep(s,
    "        showRouteNotification(plat, offer)\n",
    "        showRouteNotification(plat, offer, overallGrade, metrics, declineReason)\n",
    "chamada notificacao", 1)

new_route_fun = '''private fun showRouteNotification(
        plat: String,
        offer: Offer,
        overallGrade: String,
        metrics: List<FlashCard.Metric>,
        declineReason: String?
    ) {
        val origem = offer.origem
        val destino = offer.destino
        val stops = offer.stopAddresses
        if (origem == null && destino == null && stops.isEmpty()) return

        val verdict = when (overallGrade) {
            "g" -> "ACEITAR"
            "a" -> "ANALISAR"
            else -> "RECUSAR"
        }
        val key = listOf(
            plat, offer.valor?.toString() ?: "", origem ?: "", destino ?: "",
            stops.joinToString("|"), overallGrade, declineReason ?: ""
        ).joinToString("|")
        if (key == lastNotifKey) return
        lastNotifKey = key

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val existing = nm.getNotificationChannel(NOTIF_CHANNEL_ROUTE)
            if (existing == null) {
                val ch = NotificationChannel(
                    NOTIF_CHANNEL_ROUTE, "MōB Flash — rota da corrida",
                    NotificationManager.IMPORTANCE_LOW
                )
                ch.description = "Resumo silencioso da oferta, rota e resultado do MōB Flash"
                ch.setSound(null, null)
                ch.enableVibration(false)
                ch.enableLights(false)
                nm.createNotificationChannel(ch)
            }
        }

        fun mapIntent(addr: String): PendingIntent {
            val navUri = Uri.parse("google.navigation:q=" + Uri.encode(addr))
            val navIntent = Intent(Intent.ACTION_VIEW, navUri).apply { setPackage("com.google.android.apps.maps") }
            val geoUri = Uri.parse("geo:0,0?q=" + Uri.encode(addr))
            val geoIntent = Intent(Intent.ACTION_VIEW, geoUri).apply { setPackage("com.google.android.apps.maps") }
            val genericIntent = Intent(Intent.ACTION_VIEW, geoUri)
            val real = when {
                navIntent.resolveActivity(packageManager) != null -> navIntent
                geoIntent.resolveActivity(packageManager) != null -> geoIntent
                else -> genericIntent
            }
            return PendingIntent.getActivity(
                this, addr.hashCode(), real,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val valorTxt = offer.valor?.let { "R$ ${fmtBr(it)}" }
        val titulo = "$plat · " + listOfNotNull(valorTxt, verdict).joinToString(" · ")
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
        val metricText = metrics.joinToString(" · ") { "${it.label} ${it.value}" }
        lines.add(if (metricText.isNotBlank()) "Flash: $verdict · $metricText" else "Flash: $verdict")
        declineReason?.let { lines.add("Motivo: $it") }
        val resumo = lines.joinToString("\\n")

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIF_CHANNEL_ROUTE)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        builder.setContentTitle(titulo)
            .setStyle(Notification.BigTextStyle().bigText(resumo))
            .setContentText(lines.firstOrNull() ?: titulo)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(Notification.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setOngoing(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) builder.setTimeoutAfter(20 * 60 * 1000L)
        if (origem != null) builder.addAction(Notification.Action.Builder(null, "Origem", mapIntent(origem)).build())
        if (destino != null) builder.addAction(Notification.Action.Builder(null, "Destino", mapIntent(destino)).build())
        try { nm.notify(4103, builder.build()) } catch (_: Exception) {}
    }'''
s = replace_function(s, "private fun showRouteNotification(plat: String, offer: Offer) {", new_route_fun)
p.write_text(s)

# AutoTripCapture
p = Path("app/src/main/java/io/github/yurisilva90/smartmobi/AutoTripCapture.kt")
s = p.read_text()
s = rep(s,
    "        val origin: String?,\n        val dest: String?,\n",
    "        val origin: String?,\n        val dest: String?,\n        val stopCount: Int = 0,\n        val stopAddresses: List<String> = emptyList(),\n",
    "OfferSnapshot paradas", 1)
s = rep(s,
    "        var originAddress: String?,\n        var destAddress: String?,\n",
    "        var originAddress: String?,\n        var destAddress: String?,\n        var stopCount: Int = 0,\n        var stopAddresses: List<String> = emptyList(),\n",
    "Buffer paradas", 1)
s = rep(s,
    "                    put(\"origin_address\", snap.origin ?: JSONObject.NULL)\n                    put(\"dest_address\", snap.dest ?: JSONObject.NULL)\n",
    "                    put(\"origin_address\", snap.origin ?: JSONObject.NULL)\n                    put(\"dest_address\", snap.dest ?: JSONObject.NULL)\n                    put(\"stop_count\", snap.stopCount)\n                    put(\"stop_addresses\", org.json.JSONArray(snap.stopAddresses))\n",
    "declined paradas", 1)
s = rep(s,
    "                origin = betterAddress(existing.origin, snap.origin),\n                dest = betterAddress(existing.dest, snap.dest),\n",
    "                origin = betterAddress(existing.origin, snap.origin),\n                dest = betterAddress(existing.dest, snap.dest),\n                stopCount = maxOf(existing.stopCount, snap.stopCount),\n                stopAddresses = if (snap.stopAddresses.size >= existing.stopAddresses.size) snap.stopAddresses else existing.stopAddresses,\n",
    "merge paradas", 1)
s = rep(s,
    "                originAddress = offer?.origin,\n                destAddress = offer?.dest,\n",
    "                originAddress = offer?.origin,\n                destAddress = offer?.dest,\n                stopCount = offer?.stopCount ?: 0,\n                stopAddresses = offer?.stopAddresses ?: emptyList(),\n",
    "buffer recebe paradas", 1)
s = rep(s,
    "                    put(\"origin_address\", finalOriginAddress ?: JSONObject.NULL)\n                    put(\"dest_address\", finalDestAddress ?: JSONObject.NULL)\n",
    "                    put(\"origin_address\", finalOriginAddress ?: JSONObject.NULL)\n                    put(\"dest_address\", finalDestAddress ?: JSONObject.NULL)\n                    put(\"stop_count\", b.stopCount)\n                    put(\"stop_addresses\", org.json.JSONArray(b.stopAddresses))\n",
    "auto_trips paradas", 1)
p.write_text(s)

# DriverNotificationListenerService
p = Path("app/src/main/java/io/github/yurisilva90/smartmobi/DriverNotificationListenerService.kt")
s = p.read_text()
s = s.replace(
    " * Captura somente metadados estruturados das notificações oficiais da Uber/99.\n * Não persiste nome, endereço ou texto bruto. O texto completo existe apenas em\n * memória pelo tempo necessário para extrair sinais operacionais e, quando há\n * evidência forte de oferta, antecipar a leitura do Flash.\n",
    " * Captura dados estruturados das notificações oficiais da Uber/99.\n * Não persiste o texto bruto nem nome do passageiro; preserva os endereços de\n * origem/destino/paradas quando a própria notificação os expõe, além dos sinais\n * operacionais necessários para antecipar a leitura do Flash.\n")
s = rep(s,
    "        val money = extractMoney(joined)\n        val kms = extractKm(low)\n        val mins = extractMinutes(low)\n        val keywords = extractKeywords(low)\n",
    "        val money = extractMoney(joined)\n        val kms = extractKm(low)\n        val mins = extractMinutes(low)\n        val routeInfo = extractRouteInfo(parts, mins)\n        val keywords = extractKeywords(low)\n",
    "notification route info", 1)
s = rep(s,
    "            money, kms, mins, keywords.distinct(), actionLabels.distinct(), offerHint\n",
    "            money, kms, mins, keywords.distinct(), actionLabels.distinct(), offerHint, routeInfo\n",
    "persist route info call", 1)
marker = "    private fun hash(s: String?): String? {"
helper = '''    private data class RouteInfo(
        val origin: String?, val dest: String?, val stopCount: Int,
        val stops: List<String>, val routeDurationSec: Int?
    )

    private fun extractRouteInfo(parts: List<String>, mins: List<Int>): RouteInfo {
        val cleaned = parts.map { it.replace(Regex("\\\\s+"), " ").trim() }.filter { it.isNotBlank() }
        fun valueAfterLabel(line: String, labels: List<String>): String? {
            val low = line.lowercase(Locale("pt", "BR"))
            for (label in labels) {
                val i = low.indexOf(label)
                if (i >= 0) {
                    val tail = line.substring(i + label.length).trim().trimStart(':', '-', '–', '—').trim()
                    if (tail.length >= 5 && !tail.contains("R$", true)) return tail
                }
            }
            return null
        }
        val originLabels = listOf("origem", "embarque", "buscar em", "pickup")
        val destLabels = listOf("destino", "desembarque", "dropoff")
        var origin: String? = null
        var dest: String? = null
        val stops = ArrayList<String>()
        var declaredStops = 0
        cleaned.forEach { line ->
            Regex("""\\b(\\d{1,2})\\s*paradas?\\b""", RegexOption.IGNORE_CASE).find(line)?.let {
                declaredStops = maxOf(declaredStops, it.groupValues[1].toIntOrNull() ?: 0)
            }
            if (origin == null) origin = valueAfterLabel(line, originLabels)
            if (dest == null) dest = valueAfterLabel(line, destLabels)
            Regex("""(?i)(?:parada\\s*\\d+|\\d+[ªa]?\\s*parada)\\s*[:\\-–—]\\s*(.+)$""").find(line)?.groupValues?.getOrNull(1)?.trim()?.let {
                if (it.length >= 5 && !it.contains("R$", true)) stops.add(it)
            }
        }
        val streetLike = cleaned.filter { line ->
            val low = line.lowercase(Locale("pt", "BR"))
            !low.contains("r$") && Regex("""\\b(rua|r\\.?|av\\.?|avenida|estrada|travessa|alameda|rodovia|pra[çc]a|largo|ladeira|via)\\b""", RegexOption.IGNORE_CASE).containsMatchIn(line)
        }.distinct()
        if (origin == null) origin = streetLike.firstOrNull()
        if (dest == null && streetLike.size >= 2) dest = streetLike.lastOrNull()
        if (stops.isEmpty() && declaredStops > 0 && streetLike.size > 2) {
            stops.addAll(streetLike.subList(1, streetLike.size - 1).take(declaredStops))
        }
        return RouteInfo(origin, dest, maxOf(declaredStops, stops.size), stops.distinct(), mins.lastOrNull()?.times(60))
    }

'''
if marker not in s:
    raise SystemExit("marker hash nao encontrado")
s = s.replace(marker, helper + marker, 1)
s = rep(s,
    "        actionLabels: List<String>,\n        offerHint: Boolean\n",
    "        actionLabels: List<String>,\n        offerHint: Boolean,\n        routeInfo: RouteInfo\n",
    "persist args", 1)
s = rep(s,
    "                    put(\"offer_hint\", offerHint)\n",
    "                    put(\"offer_hint\", offerHint)\n                    put(\"origin_address\", routeInfo.origin ?: JSONObject.NULL)\n                    put(\"dest_address\", routeInfo.dest ?: JSONObject.NULL)\n                    put(\"stop_count\", routeInfo.stopCount)\n                    put(\"stop_addresses\", JSONArray(routeInfo.stops))\n                    put(\"route_duration_sec\", routeInfo.routeDurationSec ?: JSONObject.NULL)\n",
    "persist route columns", 1)
p.write_text(s)

# Version
p = Path("app/build.gradle")
s = p.read_text()
if "versionCode 222" not in s or 'versionName "1.3.9"' not in s:
    raise SystemExit("versao base inesperada")
s = s.replace("versionCode 222", "versionCode 223", 1)
s = s.replace('versionName "1.3.9"', 'versionName "1.3.10"', 1)
p.write_text(s)
