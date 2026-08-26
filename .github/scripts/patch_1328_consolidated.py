from pathlib import Path

svc = Path('app/src/main/java/io/github/yurisilva90/smartmobi/TripReaderService.kt')
s = svc.read_text(encoding='utf-8')

# Status foreground guard — idempotent.
if 'private fun activeDriverPlatformForStatus()' not in s:
    anchor = '''    private fun chooseForegroundPlatform(): String? {\n        val hint = recentOfficialOfferHint()\n'''
    if anchor not in s:
        raise SystemExit('chooseForegroundPlatform anchor not found')
    insert_after = '''        return bestPlat\n    }\n\n'''
    idx = s.find(insert_after, s.find(anchor))
    if idx < 0:
        raise SystemExit('chooseForegroundPlatform end not found')
    idx += len(insert_after)
    helper = '''    // v1.3.28 — autoridade estrita para Online/Buscar/Corrida.\n    // Oferta continua podendo ser lida em janela visível/sobreposta. Status\n    // operacional só muda quando Uber/99 possuem janela APPLICATION ativa\n    // ou focada. Abrir MōB, Waze, launcher ou outro app preserva o último\n    // estado válido da plataforma até ela voltar a ser realmente ativa.\n    private fun activeDriverPlatformForStatus(): String? {\n        var activePlat: String? = null\n        try {\n            for (w in windows) {\n                if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue\n                if (!w.isActive && !w.isFocused) continue\n                val wp = w.root?.packageName?.toString() ?: continue\n                val plat = platformOfPackage(wp) ?: continue\n                if (w.isFocused) return plat\n                activePlat = plat\n            }\n        } catch (_: Exception) {}\n        return activePlat\n    }\n\n'''
    s = s[:idx] + helper + s[idx:]

old_poll = '''        if (offerGuardActive()) return\n        when (fgPlat) {\n            "UBER" -> scanUberTripState()\n            "99"   -> scanNN99TripState()\n        }\n'''
new_poll = '''        if (offerGuardActive()) return\n        // fgPlat é permissivo para OFERTA; status precisa de janela ativa/focada.\n        val statusPlat = activeDriverPlatformForStatus()\n        when (statusPlat) {\n            "UBER" -> scanUberTripState()\n            "99"   -> scanNN99TripState()\n        }\n'''
if old_poll in s:
    s = s.replace(old_poll, new_poll, 1)
elif 'val statusPlat = activeDriverPlatformForStatus()' not in s:
    raise SystemExit('poll status block not found')

old_event = '''        if (!offerGuardActive()) {\n            if (realPlat == "UBER" && realTexts.isNotEmpty()) {\n                detectAndApplyTripSubState(realTexts)\n            } else if (realPlat == "99" && realTexts.isNotEmpty()) {\n                detectAndApply99TripSubState(realTexts)\n            }\n        }\n'''
new_event = '''        if (!offerGuardActive()) {\n            val statusPlat = activeDriverPlatformForStatus()\n            val statusTexts = when (statusPlat) {\n                "UBER" -> textsByPkg.entries.firstOrNull { UBER_PKGS.contains(it.key) }?.value\n                "99"   -> textsByPkg.entries.firstOrNull { NN_PKGS.contains(it.key) }?.value\n                else   -> null\n            }\n            if (statusPlat == "UBER" && !statusTexts.isNullOrEmpty()) {\n                detectAndApplyTripSubState(statusTexts)\n            } else if (statusPlat == "99" && !statusTexts.isNullOrEmpty()) {\n                detectAndApply99TripSubState(statusTexts)\n            }\n        }\n'''
if old_event in s:
    s = s.replace(old_event, new_event, 1)
elif 'val statusTexts = when (statusPlat)' not in s:
    raise SystemExit('event status block not found')

svc.write_text(s, encoding='utf-8')

# Confirm overlap cache from 1.3.27 is present; never regress it.
auto = Path('app/src/main/java/io/github/yurisilva90/smartmobi/AutoTripCapture.kt')
a = auto.read_text(encoding='utf-8')
required = [
    'OFFER_MAX_AGE_OVERLAP_MS',
    'val overlapCandidate = current != null && current.tripStartedAt > 0L && current.tripEndedAt == 0L',
    'val maxAge = if (overlapCandidate) OFFER_MAX_AGE_OVERLAP_MS else OFFER_STALE_MS'
]
for marker in required:
    if marker not in a:
        raise SystemExit('1.3.27 overlap cache regression: ' + marker)

# Bump version.
b = Path('app/build.gradle')
g = b.read_text(encoding='utf-8')
g = g.replace('// 1.3.27: preserva oferta sobreposta para vincular valor à próxima corrida.',
              '// 1.3.28: consolida cache de oferta + proteção de status em segundo plano.')
g = g.replace('// build trigger v1.3.27', '// build trigger v1.3.28')
g = g.replace('versionCode 239', 'versionCode 240')
g = g.replace('versionName "1.3.27"', 'versionName "1.3.28"')
if 'versionCode 240' not in g or 'versionName "1.3.28"' not in g:
    raise SystemExit('version bump failed')
b.write_text(g, encoding='utf-8')

print('1.3.28 consolidated native patch applied')
