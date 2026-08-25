from pathlib import Path

# MainActivity ---------------------------------------------------------------
p = Path('app/src/main/java/io/github/yurisilva90/smartmobi/MainActivity.kt')
s = p.read_text(encoding='utf-8')

old = '''        instance = this\n        pendingScreen = intent.getStringExtra("open_screen")'''
new = '''        instance = this\n        JourneyStatusTracker.restore(this)\n        pendingScreen = intent.getStringExtra("open_screen")'''
assert old in s
s = s.replace(old, new, 1)

old = '''            // Estado vivo Online/Buscar/Corrida + marcos de tempo/km da corrida atual.\n            @JavascriptInterface fun getLiveTripState(): String = AutoTripCapture.liveStateJson()'''
new = '''            // Estado vivo Online/Buscar/Corrida + marcos de tempo/km da corrida atual.\n            @JavascriptInterface fun getLiveTripState(): String = AutoTripCapture.liveStateJson()\n            // Linha do tempo da Jornada, independente de auto_trips.\n            @JavascriptInterface fun getJourneyStatusTimeline(): String =\n                JourneyStatusTracker.timelineJson(this@MainActivity)\n            @JavascriptInterface fun setJourneySession(sessionId: String, date: String, startMs: Long, startKm: Double, isNew: Boolean) {\n                JourneyStatusTracker.setSession(this@MainActivity, sessionId, date, startMs, startKm, isNew)\n            }'''
assert old in s
s = s.replace(old, new, 1)

old = '''            @JavascriptInterface fun stopFloating() {\n                floatingWidget?.hide()\n                floatingWidget = null\n                stopGpsService()\n            }'''
new = '''            @JavascriptInterface fun stopFloating() {\n                JourneyStatusTracker.endSession(this@MainActivity)\n                floatingWidget?.hide()\n                floatingWidget = null\n                stopGpsService()\n            }'''
assert old in s
s = s.replace(old, new, 1)

old = '''            @JavascriptInterface fun pauseGpsService() {\n                val i = Intent(this@MainActivity, GpsService::class.java).apply { action = "PAUSE" }\n                startService(i)\n            }\n            @JavascriptInterface fun resumeGpsService() {\n                val i = Intent(this@MainActivity, GpsService::class.java).apply { action = "RESUME" }\n                startService(i)\n            }'''
new = '''            @JavascriptInterface fun pauseGpsService() {\n                JourneyStatusTracker.pause(this@MainActivity)\n                val i = Intent(this@MainActivity, GpsService::class.java).apply { action = "PAUSE" }\n                startService(i)\n            }\n            @JavascriptInterface fun resumeGpsService() {\n                JourneyStatusTracker.resume(this@MainActivity)\n                val i = Intent(this@MainActivity, GpsService::class.java).apply { action = "RESUME" }\n                startService(i)\n            }'''
assert old in s
s = s.replace(old, new, 1)
p.write_text(s, encoding='utf-8')

# TripReaderService ----------------------------------------------------------
p = Path('app/src/main/java/io/github/yurisilva90/smartmobi/TripReaderService.kt')
s = p.read_text(encoding='utf-8')
old = '''        instance = this\n        val info = AccessibilityServiceInfo().apply {'''
new = '''        instance = this\n        JourneyStatusTracker.restore(this)\n        val info = AccessibilityServiceInfo().apply {'''
assert old in s
s = s.replace(old, new, 1)

old = '''            AutoTripCapture.onStateTransition(this, plat, prev, best.key)'''
new = '''            AutoTripCapture.onStateTransition(this, plat, prev, best.key)\n            // Persistência independente da corrida financeira: cada trecho de\n            // Online/Buscar/Corrida sobrevive mesmo sem oferta completa.\n            JourneyStatusTracker.onStateTransition(this, plat, prev, best.key)'''
assert old in s
s = s.replace(old, new, 1)

old = '''    private val pollRunnable = object : Runnable {\n        override fun run() {\n            try { pollForeground() } catch (_: Exception) {}\n            main.postDelayed(this, 600)\n        }\n    }'''
new = '''    private val pollRunnable = object : Runnable {\n        override fun run() {\n            try { pollForeground() } catch (_: Exception) {}\n            try { JourneyStatusTracker.checkpoint(this@TripReaderService) } catch (_: Exception) {}\n            main.postDelayed(this, 600)\n        }\n    }'''
assert old in s
s = s.replace(old, new, 1)
p.write_text(s, encoding='utf-8')

# Versão --------------------------------------------------------------------
p = Path('app/build.gradle')
s = p.read_text(encoding='utf-8')
s = s.replace('// 1.3.5: bridge nativa para status e métricas em tempo real da jornada atual.',
              '// 1.3.6: persistência independente dos trechos Online/Buscar/Corrida da Jornada.')
s = s.replace('versionCode 218', 'versionCode 219')
s = s.replace('versionName "1.3.5"', 'versionName "1.3.6"')
p.write_text(s, encoding='utf-8')

print('Patch nativo da jornada aplicado.')
