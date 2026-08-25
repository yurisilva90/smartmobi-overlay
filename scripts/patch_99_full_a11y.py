from pathlib import Path
import re

p = Path('app/src/main/java/io/github/yurisilva90/smartmobi/TripReaderService.kt')
s = p.read_text()

if 'send99FullAccessibilityDiagnostics' not in s:
    marker = '    private fun collectTexts(node: AccessibilityNodeInfo?, out: ArrayList<String>) {'
    if marker not in s:
        raise SystemExit('collectTexts marker not found')
    helper = r'''
    private var last99TreeDiagMs = 0L
    private fun send99FullAccessibilityDiagnostics(texts: List<String>) {
        val now = System.currentTimeMillis()
        if (now - last99TreeDiagMs < 1800L) return
        last99TreeDiagMs = now
        val trees = JSONArray(); val windowsJson = JSONArray()
        try {
            for (w in windows) {
                val root = w.root ?: continue
                val pkg = root.packageName?.toString() ?: continue
                if (!NN_PKGS.contains(pkg)) continue
                val rect = Rect(); w.getBoundsInScreen(rect)
                windowsJson.put(JSONObject().apply {
                    put("type", w.type); put("layer", w.layer); put("active", w.isActive); put("focused", w.isFocused); put("pkg", pkg)
                    put("bounds", JSONObject().apply { put("left",rect.left);put("top",rect.top);put("right",rect.right);put("bottom",rect.bottom) })
                })
                collectNodeDiagnostics(root, trees)
            }
        } catch (_: Exception) {}
        thread(isDaemon = true) {
            try {
                val prefs = getSharedPreferences(GpsService.PREFS_NAME, Context.MODE_PRIVATE)
                val userId = prefs.getString(GpsService.KEY_USER_ID, null)
                val deviceId = try { Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) } catch (_: Exception) { "unknown" }
                val body = JSONObject().apply {
                    put("device_id", deviceId)
                    if (userId != null) put("user_id", userId)
                    put("platform", "99")
                    put("package", "accessibility-tree")
                    put("screen_class", "99_FULL_TREE")
                    put("texts", JSONObject().apply { put("state", "FULL_TREE"); put("raw", JSONArray(texts)); put("windows", windowsJson); put("nodes", trees) })
                }
                val url = URL("$SUPABASE_URL/rest/v1/trip_reader_log")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"; conn.connectTimeout = 8000; conn.readTimeout = 8000; conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json"); conn.setRequestProperty("apikey", SUPABASE_ANON)
                val authToken = prefs.getString(GpsService.KEY_ACCESS_TOKEN, null) ?: SUPABASE_ANON
                conn.setRequestProperty("Authorization", "Bearer $authToken"); conn.setRequestProperty("Prefer", "return=minimal")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }; conn.responseCode; conn.disconnect()
            } catch (_: Exception) {}
        }
    }

'''
    s = s.replace(marker, helper + marker, 1)

old = '''        if (realPlat != null && realTexts.isNotEmpty()) {
            if (realPlat == "UBER") {
'''
new = '''        if (realPlat != null && realTexts.isNotEmpty()) {
            if (realPlat == "99") send99FullAccessibilityDiagnostics(realTexts)
            if (realPlat == "UBER") {
'''
if old not in s:
    raise SystemExit('realPlat block not found')
s = s.replace(old, new, 1)
p.write_text(s)

g = Path('app/build.gradle')
b = g.read_text()
b = re.sub(r'versionCode\s+\d+', 'versionCode 231', b, count=1)
b = re.sub(r'versionName\s+"[^"]+"', 'versionName "1.3.18"', b, count=1)
b = b.replace('// 1.3.17: diagnóstico completo da árvore de acessibilidade da Uber em ofertas.', '// 1.3.18: diagnóstico completo da árvore de acessibilidade da Uber e 99.')
g.write_text(b)
