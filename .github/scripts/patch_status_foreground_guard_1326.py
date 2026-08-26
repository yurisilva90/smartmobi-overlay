from pathlib import Path

p = Path('app/src/main/java/io/github/yurisilva90/smartmobi/TripReaderService.kt')
s = p.read_text(encoding='utf-8')

anchor = '''    private fun chooseForegroundPlatform(): String? {
        val hint = recentOfficialOfferHint()
'''
if anchor not in s:
    raise SystemExit('chooseForegroundPlatform anchor not found')

# Add a STRICT authority check used only for operational status.
# Offer capture intentionally remains more permissive because an offer may be
# visible as an overlay even when another app owns the active window.
insert_after = '''        return bestPlat
    }

'''
idx = s.find(insert_after, s.find(anchor))
if idx < 0:
    raise SystemExit('chooseForegroundPlatform end not found')
idx += len(insert_after)
helper = '''    // v1.3.26 — autoridade estrita para Online/Buscar/Corrida.
    // A captura de OFERTA pode olhar janelas visíveis/overlay mesmo com outro app
    // em primeiro plano. O STATUS operacional não: abrir o MōB, Waze, launcher ou
    // qualquer outro app jamais pode injetar votos "online" numa 99/Uber que ficou
    // ao fundo. Só uma janela TYPE_APPLICATION da própria plataforma, ativa ou
    // focada, tem autoridade para alterar o estado persistido daquela plataforma.
    private fun activeDriverPlatformForStatus(): String? {
        var activePlat: String? = null
        try {
            for (w in windows) {
                if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                if (!w.isActive && !w.isFocused) continue
                val wp = w.root?.packageName?.toString() ?: continue
                val plat = platformOfPackage(wp) ?: continue
                // Foco é a evidência mais forte. Se houver, pode retornar direto.
                if (w.isFocused) return plat
                activePlat = plat
            }
        } catch (_: Exception) {}
        return activePlat
    }

'''
s = s[:idx] + helper + s[idx:]

old_poll = '''        if (offerGuardActive()) return
        when (fgPlat) {
            "UBER" -> scanUberTripState()
            "99"   -> scanNN99TripState()
        }
'''
new_poll = '''        if (offerGuardActive()) return
        // IMPORTANTE: fgPlat é deliberadamente permissivo para capturar OFERTAS
        // sobrepostas. Para status usamos uma autoridade estrita. Se o MōB (ou
        // qualquer outro app) estiver em primeiro plano, statusPlat=null e o último
        // estado válido permanece congelado até a plataforma voltar a ser ativa.
        val statusPlat = activeDriverPlatformForStatus()
        when (statusPlat) {
            "UBER" -> scanUberTripState()
            "99"   -> scanNN99TripState()
        }
'''
if old_poll not in s:
    raise SystemExit('poll status block not found')
s = s.replace(old_poll, new_poll, 1)

old_event = '''        if (!offerGuardActive()) {
            if (realPlat == "UBER" && realTexts.isNotEmpty()) {
                detectAndApplyTripSubState(realTexts)
            } else if (realPlat == "99" && realTexts.isNotEmpty()) {
                detectAndApply99TripSubState(realTexts)
            }
        }
'''
new_event = '''        if (!offerGuardActive()) {
            // realPlat pode apontar para uma janela da plataforma que continua
            // visível, porém está ao fundo. Isso é útil para oferta, mas NÃO pode
            // mudar Online/Buscar/Corrida. Status só aceita texto da plataforma
            // que possui uma janela APPLICATION ativa/focada neste instante.
            val statusPlat = activeDriverPlatformForStatus()
            val statusTexts = when (statusPlat) {
                "UBER" -> textsByPkg.entries.firstOrNull { UBER_PKGS.contains(it.key) }?.value
                "99"   -> textsByPkg.entries.firstOrNull { NN_PKGS.contains(it.key) }?.value
                else   -> null
            }
            if (statusPlat == "UBER" && !statusTexts.isNullOrEmpty()) {
                detectAndApplyTripSubState(statusTexts)
            } else if (statusPlat == "99" && !statusTexts.isNullOrEmpty()) {
                detectAndApply99TripSubState(statusTexts)
            }
        }
'''
if old_event not in s:
    raise SystemExit('event status block not found')
s = s.replace(old_event, new_event, 1)

p.write_text(s, encoding='utf-8')

# Version bump
b = Path('app/build.gradle')
g = b.read_text(encoding='utf-8')
g = g.replace('// 1.3.25: melhora relevância e UX dos cards colaborativos proativos.\n// build trigger v1.3.25',
              '// 1.3.26: preserva status Uber/99 ao abrir o MōB ou outro app.\n// build trigger v1.3.26')
g = g.replace('versionCode 238', 'versionCode 239')
g = g.replace('versionName "1.3.25"', 'versionName "1.3.26"')
if 'versionCode 239' not in g or 'versionName "1.3.26"' not in g:
    raise SystemExit('version bump failed')
b.write_text(g, encoding='utf-8')

print('patched status foreground authority + bumped 1.3.26/239')
