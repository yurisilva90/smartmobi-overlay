from pathlib import Path

trip_path = Path('app/src/main/java/io/github/yurisilva90/smartmobi/TripReaderService.kt')
flash_path = Path('app/src/main/java/io/github/yurisilva90/smartmobi/FlashCard.kt')
gradle_path = Path('app/build.gradle')

trip = trip_path.read_text()
flash = flash_path.read_text()
gradle = gradle_path.read_text()

def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f'{label}: expected 1 occurrence, found {count}')
    return text.replace(old, new, 1)

# ── 99: Accessibility is the lightweight offer-state trigger for OCR. ──
gate_anchor = '''    private fun requestPriorityOcr(plat: String) {
        lastOcrMs = 0L
        requestOcrPass(plat)
    }
'''
gate_code = '''    // v1.3.19 — OCR policy:
    // • Uber: offer data comes from AccessibilityService; never run OCR.
    // • 99: AccessibilityService only arms OCR while an offer sheet is present.
    //   Keep a short grace window because Flutter can replace/remove accessible
    //   nodes slightly before the visual offer disappears.
    private var nn99OfferOcrUntilMs = 0L
    private val NN99_OCR_GATE_HOLD_MS = 5_000L

    private fun nodeHas99OfferMarker(node: AccessibilityNodeInfo?, depth: Int = 0): Boolean {
        node ?: return false
        if (depth > 40) return false
        try {
            val id = node.viewIdResourceName?.lowercase(Locale.getDefault()) ?: ""
            if (id.contains("flu_v2_btm_sht_top_container_landscape")) return true
            for (i in 0 until node.childCount) {
                if (nodeHas99OfferMarker(node.getChild(i), depth + 1)) return true
            }
        } catch (_: Exception) {}
        return false
    }

    private fun refresh99OfferOcrGate() {
        var signal = false
        val texts = ArrayList<String>()
        try {
            for (w in windows) {
                val root = w.root ?: continue
                val pkg = root.packageName?.toString() ?: continue
                if (!NN_PKGS.contains(pkg)) continue
                collectTexts(root, texts)
                if (nodeHas99OfferMarker(root)) signal = true
            }
        } catch (_: Exception) {}
        val low = texts.joinToString(" ").lowercase(Locale.getDefault())
        if (low.contains("toque para selecionar") ||
            low.contains("opções de corridas") || low.contains("opcoes de corridas") ||
            Regex("""\\d+\\s*corrida\\(s\\).*op[cç][oõ]es\\s+de\\s+corridas""", RegexOption.IGNORE_CASE).containsMatchIn(low)) {
            signal = true
        }
        if (signal) nn99OfferOcrUntilMs = System.currentTimeMillis() + NN99_OCR_GATE_HOLD_MS
    }

    private fun nn99OfferOcrGateActive(): Boolean =
        System.currentTimeMillis() <= nn99OfferOcrUntilMs

    private fun requestPriorityOcr(plat: String) {
        lastOcrMs = 0L
        requestOcrPass(plat)
    }
'''
trip = replace_once(trip, gate_anchor, gate_code, 'insert 99 OCR gate')

ocr_anchor = '''    private fun requestOcrPass(plat: String) {
        if (!ScreenOcrService.isActive) {
'''
ocr_new = '''    private fun requestOcrPass(plat: String) {
        // Uber is 100% accessibility for offers from v1.3.19 onward.
        if (plat == "UBER") return
        // 99 only spends OCR while its accessible offer-state signal is alive.
        if (plat == "99") {
            refresh99OfferOcrGate()
            if (!nn99OfferOcrGateActive()) return
        }
        if (!ScreenOcrService.isActive) {
'''
trip = replace_once(trip, ocr_anchor, ocr_new, 'gate requestOcrPass')

# ── Notification: place inspection instead of navigation. ──
map_old = '''        fun mapIntent(addr: String): PendingIntent {
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
'''
map_new = '''        fun mapIntent(addr: String): PendingIntent {
            // Open the place/address for inspection. Do NOT start navigation:
            // the driver wants to inspect the location, photos and Street View
            // when Google Maps has coverage before deciding on the offer.
            val geoUri = Uri.parse("geo:0,0?q=" + Uri.encode(addr))
            val geoIntent = Intent(Intent.ACTION_VIEW, geoUri).apply { setPackage("com.google.android.apps.maps") }
            val genericIntent = Intent(Intent.ACTION_VIEW, geoUri)
            val real = if (geoIntent.resolveActivity(packageManager) != null) geoIntent else genericIntent
'''
trip = replace_once(trip, map_old, map_new, 'map intent')

verdict_old = '''        val verdict = when (overallGrade) {
            "g" -> "ACEITAR"
            "a" -> "ANALISAR"
            else -> "RECUSAR"
        }
'''
trip = replace_once(trip, verdict_old, '', 'remove notification verdict label')

trip = replace_once(
    trip,
    '''        val valorTxt = offer.valor?.let { "R$ ${fmtBr(it)}" }
        val titulo = "$plat · " + listOfNotNull(valorTxt, verdict).joinToString(" · ")
''',
    '''        val valorTxt = offer.valor?.let { "R$ ${fmtBr(it)}" }
        val titulo = listOfNotNull(plat, valorTxt).joinToString(" · ")
''',
    'notification title'
)

trip = replace_once(
    trip,
    '''        val metricText = metrics.joinToString(" · ") { "${it.label} ${it.value}" }
        lines.add(if (metricText.isNotBlank()) "Flash: $verdict · $metricText" else "Flash: $verdict")
        declineReason?.let { lines.add("Motivo: $it") }
        val resumo = lines.joinToString("\\n")
''',
    '''        val resumo = lines.joinToString("\\n")
''',
    'remove explicit verdict from notification body'
)

trip = replace_once(
    trip,
    '''        val visualLines = lines.filterNot { it.startsWith("Flash:") || it.startsWith("Motivo:") }
        val flashPicture = flashCard.renderNotificationBitmap(
            plat, overallGrade, metrics, offer.min ?: routeMin ?: 0, offer.km ?: routeKm ?: 0.0, declineReason, visualLines
        )
''',
    '''        val visualLines = lines
        val flashPicture = flashCard.renderNotificationBitmap(
            plat, valorTxt, overallGrade, metrics, offer.min ?: routeMin ?: 0,
            offer.km ?: routeKm ?: 0.0, declineReason, visualLines
        )
''',
    'pass fare into notification artwork'
)

# ── Flash notification artwork: colored fare, no ACCEPT/REJECT word. ──
flash = replace_once(
    flash,
    '''    fun renderNotificationBitmap(
        platform: String,
        overallGrade: String,
''',
    '''    fun renderNotificationBitmap(
        platform: String,
        offerValueText: String?,
        overallGrade: String,
''',
    'notification bitmap signature'
)

header_old = '''                val verdict = when (overallGrade) {
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
'''
header_new = '''                val header = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    setPadding(dp(4), dp(2), dp(4), dp(7))
                }
                header.addView(TextView(context).apply {
                    text = platform
                    textSize = 13f
                    setTypeface(Typeface.DEFAULT_BOLD)
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                })
                if (!offerValueText.isNullOrBlank()) {
                    header.addView(TextView(context).apply {
                        text = "  ·  $offerValueText"
                        textSize = 16f
                        setTypeface(Typeface.DEFAULT_BOLD)
                        setTextColor(colorOf(overallGrade))
                        gravity = Gravity.CENTER
                    })
                }
                outer.addView(header, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ))
'''
flash = replace_once(flash, header_old, header_new, 'colored fare notification header')

# ── Version ──
gradle = gradle.replace('// 1.3.18: diagnóstico completo da árvore de acessibilidade da Uber e 99.\n// build trigger',
                        '// 1.3.19: Uber via acessibilidade; OCR da 99 por gatilho; notificação estruturada.\n// build trigger')
gradle = replace_once(gradle, 'versionCode 231', 'versionCode 232', 'versionCode')
gradle = replace_once(gradle, 'versionName "1.3.18"', 'versionName "1.3.19"', 'versionName')

trip_path.write_text(trip)
flash_path.write_text(flash)
gradle_path.write_text(gradle)
print('v1.3.19 patch applied')
