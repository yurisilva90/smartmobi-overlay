from pathlib import Path

p=Path('app/src/main/java/io/github/yurisilva90/smartmobi/MainActivity.kt')
s=p.read_text()
anchor='''            // Notificações oficiais Uber/99 — permissão separada da Acessibilidade.\n            @JavascriptInterface fun isNotificationAccessEnabled(): Boolean = this@MainActivity.isNotificationAccessEnabled()\n'''
assert anchor in s
if '@JavascriptInterface fun isLocationReady()' not in s:
    block='''            // Estado real das permissões essenciais exibidas no checklist do MōB Flash.\n            @JavascriptInterface fun isLocationReady(): Boolean {\n                val fg = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||\n                    ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED\n                val bg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)\n                    ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED\n                else true\n                return fg && bg\n            }\n            @JavascriptInterface fun openLocationSettings() {\n                runOnUiThread {\n                    try {\n                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))\n                    } catch (_: Exception) {}\n                }\n            }\n            @JavascriptInterface fun isBatteryExempt(): Boolean {\n                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true\n                return try {\n                    val pm = getSystemService(POWER_SERVICE) as PowerManager\n                    pm.isIgnoringBatteryOptimizations(packageName)\n                } catch (_: Exception) { false }\n            }\n            @JavascriptInterface fun openBatterySettings() {\n                runOnUiThread { requestBatteryOptimizationExemption() }\n            }\n\n'''
    s=s.replace(anchor,block+anchor,1)
p.write_text(s)

g=Path('app/build.gradle')
t=g.read_text()
t=t.replace('// 1.3.11: notificações oficiais + rota/Flash + paradas + espera do passageiro + vídeo nativo.','// 1.3.12: checklist completo de permissões + alerta preventivo antes das ofertas.')
t=t.replace('versionCode 224','versionCode 225')
t=t.replace('versionName "1.3.11"','versionName "1.3.12"')
assert 'versionCode 225' in t and 'versionName "1.3.12"' in t
g.write_text(t)
