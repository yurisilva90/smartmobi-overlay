from pathlib import Path


def replace_once(path_str, old, new):
    path = Path(path_str)
    text = path.read_text(encoding='utf-8')
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{path_str}: esperado 1 trecho, encontrado {count}')
    path.write_text(text.replace(old, new, 1), encoding='utf-8')

# 1) Expõe ao WebView o estado vivo que já existe no buffer nativo.
auto_path = 'app/src/main/java/io/github/yurisilva90/smartmobi/AutoTripCapture.kt'
auto_anchor = '    @Volatile private var buffer: Buffer? = null\n'
auto_insert = '''    @Volatile private var buffer: Buffer? = null\n\n    // Snapshot leve para a tela Jornada. Não grava nada e não altera a\n    // máquina de estados: apenas expõe os marcos que o AutoTripCapture já\n    // mantém em memória, junto com o km autoritativo do GpsService.\n    fun liveStateJson(): String {\n        val b = buffer\n        return JSONObject().apply {\n            put("active", b != null)\n            put("state", TripReaderService.confirmedTripSubState)\n            put("nowMs", System.currentTimeMillis())\n            put("gpsKm", GpsService.totalKm)\n            put("gpsRunning", GpsService.isRunning)\n            if (b != null) {\n                put("platform", b.platform.lowercase(Locale.getDefault()))\n                put("offerValue", b.offerValue ?: JSONObject.NULL)\n                put("acceptedAt", b.acceptedAt)\n                put("pickupStartedAt", b.pickupStartedAt)\n                put("pickupStartKm", b.pickupStartKm)\n                put("tripStartedAt", b.tripStartedAt)\n                put("tripStartKm", b.tripStartKm)\n            }\n        }.toString()\n    }\n'''
replace_once(auto_path, auto_anchor, auto_insert)

# 2) Bridge síncrona JS -> Android. A Jornada pode consultar isso a cada tick.
main_path = 'app/src/main/java/io/github/yurisilva90/smartmobi/MainActivity.kt'
main_anchor = '            @JavascriptInterface fun isGpsRunning(): Boolean = GpsService.isRunning\n'
main_insert = '''            @JavascriptInterface fun isGpsRunning(): Boolean = GpsService.isRunning\n            // Estado vivo Online/Buscar/Corrida + marcos de tempo/km da corrida atual.\n            @JavascriptInterface fun getLiveTripState(): String = AutoTripCapture.liveStateJson()\n'''
replace_once(main_path, main_anchor, main_insert)

# 3) Novo APK, pois o bridge nativo mudou.
gradle_path = 'app/build.gradle'
gradle = Path(gradle_path).read_text(encoding='utf-8')
if 'versionCode 217' not in gradle or 'versionName "1.3.4"' not in gradle:
    raise SystemExit('Versão base esperada 1.3.4/217 não encontrada')
gradle = gradle.replace('versionCode 217', 'versionCode 218', 1)
gradle = gradle.replace('versionName "1.3.4"', 'versionName "1.3.5"', 1)
Path(gradle_path).write_text(gradle, encoding='utf-8')

print('Patch Android realtime aplicado.')
