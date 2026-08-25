from pathlib import Path
import re

p = Path('app/src/main/java/io/github/yurisilva90/smartmobi/TripReaderService.kt')
s = p.read_text()

if 'sendUberFullAccessibilityDiagnostics' not in s:
    marker = '    private fun collectTexts(node: AccessibilityNodeInfo?, out: ArrayList<String>) {'
    if marker not in s:
        raise SystemExit('collectTexts marker not found')
    helper = r'''
    private fun collectNodeDiagnostics(node: AccessibilityNodeInfo?, out: JSONArray, path: String = "0", depth: Int = 0) {
        node ?: return
        if (depth > 40 || out.length() >= 1200) return
        try {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val o = JSONObject().apply {
                put("path", path)
                put("depth", depth)
                put("class", node.className?.toString() ?: JSONObject.NULL)
                put("package", node.packageName?.toString() ?: JSONObject.NULL)
                put("view_id", node.viewIdResourceName ?: JSONObject.NULL)
                put("text", node.text?.toString() ?: JSONObject.NULL)
                put("content_desc", node.contentDescription?.toString() ?: JSONObject.NULL)
                put("hint_text", if (Build.VERSION.SDK_INT >= 26) node.hintText?.toString() ?: JSONObject.NULL else JSONObject.NULL)
                put("pane_title", if (Build.VERSION.SDK_INT >= 28) node.paneTitle?.toString() ?: JSONObject.NULL else JSONObject.NULL)
                put("tooltip_text", if (Build.VERSION.SDK_INT >= 28) node.tooltipText?.toString() ?: JSONObject.NULL else JSONObject.NULL)
                put("state_description", if (Build.VERSION.SDK_INT >= 30) node.stateDescription?.toString() ?: JSONObject.NULL else JSONObject.NULL)
                put("error", node.error?.toString() ?: JSONObject.NULL)
                put("bounds", JSONObject().apply {
                    put("left", rect.left); put("top", rect.top); put("right", rect.right); put("bottom", rect.bottom)
                    put("center_x", rect.centerX()); put("center_y", rect.centerY()); put("width", rect.width()); put("height", rect.height())
                })
                put("child_count", node.childCount)
                put("clickable", node.isClickable)
                put("long_clickable", node.isLongClickable)
                put("focusable", node.isFocusable)
                put("focused", node.isFocused)
                put("accessibility_focused", node.isAccessibilityFocused)
                put("selected", node.isSelected)
                put("checkable", node.isCheckable)
                put("checked", node.isChecked)
                put("enabled", node.isEnabled)
                put("password", node.isPassword)
                put("scrollable", node.isScrollable)
                put("visible_to_user", node.isVisibleToUser)
                put("editable", node.isEditable)
                put("dismissable", node.isDismissable)
                put("important_for_accessibility", if (Build.VERSION.SDK_INT >= 24) node.isImportantForAccessibility else true)
                put("actions", JSONArray(node.actionList.map { a -> JSONObject().apply {
                    put("id", a.id)
                    put("label", a.label?.toString() ?: JSONObject.NULL)
                }}))
                val extrasObj = JSONObject()
                try {
                    for (k in node.extras.keySet()) {
                        val v = node.extras.get(k)
                        extrasObj.put(k, when (v) {
                            null -> JSONObject.NULL
                            is CharSequence, is Number, is Boolean, is String -> v
                            else -> v.toString()
                        })
                    }
                } catch (_: Exception) {}
                put("extras", extrasObj)
            }
            out.put(o)
        } catch (_: Exception) {}
        for (i in 0 until node.childCount) collectNodeDiagnostics(node.getChild(i), out, "$path.$i", depth + 1)
    }

    private var lastUberTreeDiagMs = 0L
    private fun sendUberFullAccessibilityDiagnostics(texts: List<String>) {
        val now = System.currentTimeMillis()
        if (now - lastUberTreeDiagMs < 1800L) return
        lastUberTreeDiagMs = now
        val trees = JSONArray()
        val windowsJson = JSONArray()
        try {
            for (w in windows) {
                val root = w.root ?: continue
                val pkg = root.packageName?.toString() ?: continue
                if (!UBER_PKGS.contains(pkg)) continue
                val rect = Rect(); w.getBoundsInScreen(rect)
                windowsJson.put(JSONObject().apply {
                    put("type", w.type); put("layer", w.layer); put("active", w.isActive); put("focused", w.isFocused)
                    put("pkg", pkg)
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
                    put("platform", "UBER")
                    put("package", "accessibility-tree")
                    put("screen_class", "UBER_OFERTA_TREE")
                    put("texts", JSONObject().apply {
                        put("state", "OFERTA_TREE")
                        put("raw", JSONArray(texts))
                        put("windows", windowsJson)
                        put("nodes", trees)
                    })
                }
                val url = URL("$SUPABASE_URL/rest/v1/trip_reader_log")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"; conn.connectTimeout = 8000; conn.readTimeout = 8000; conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("apikey", SUPABASE_ANON)
                val authToken = prefs.getString(GpsService.KEY_ACCESS_TOKEN, null) ?: SUPABASE_ANON
                conn.setRequestProperty("Authorization", "Bearer $authToken")
                conn.setRequestProperty("Prefer", "return=minimal")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                conn.responseCode
                conn.disconnect()
            } catch (_: Exception) {}
        }
    }

'''
    s = s.replace(marker, helper + marker, 1)

    old = '''                    sendToCloud(
                        "UBER", "accessibility-raw", "UBER_OFERTA_RAW", "OFERTA_RAW",
                        extractMoney(uberRawJoined), extractKm(uberRawLow), extractMin(uberRawLow), realTexts
                    )'''
    if old not in s:
        raise SystemExit('Uber raw diagnostic block not found')
    s = s.replace(old, old + '\n                    sendUberFullAccessibilityDiagnostics(realTexts)', 1)
    p.write_text(s)

g = Path('app/build.gradle')
b = g.read_text()
b = re.sub(r'versionCode\s+\d+', 'versionCode 230', b, count=1)
b = re.sub(r'versionName\s+"[^"]+"', 'versionName "1.3.17"', b, count=1)
g.write_text(b)
