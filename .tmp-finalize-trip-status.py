from pathlib import Path

p = Path('app/src/main/java/io/github/yurisilva90/smartmobi/AutoTripCapture.kt')
s = p.read_text(encoding='utf-8')
old = '''                val status = if (b.offerValue != null && b.tripStartedAt > 0 && b.tripEndedAt > 0)
                    "estimada" else "capturada"
'''
new = '''                // Status principal agora representa somente confirmação:
                // toda captura automática nasce aguardando confirmação e só o
                // vídeo do histórico pode promovê-la a "confirmada" no PWA.
                // A completude da captura continua guardada separadamente para
                // diagnóstico/aperfeiçoamento do OCR, sem criar outro status.
                val captureComplete = b.offerValue != null && b.tripStartedAt > 0 && b.tripEndedAt > 0
                val status = "estimada"
'''
if s.count(old) != 1:
    raise SystemExit(f'bloco de status esperado 1x, encontrado {s.count(old)}')
s = s.replace(old, new, 1)

old2 = '                    put("status", status)\n'
new2 = '''                    put("status", status)
                    put("capture_source", "automatica")
                    put("value_needs_review", true)
                    put("data_quality_flag", if (captureComplete) JSONObject.NULL else "captura_incompleta")
'''
if s.count(old2) != 1:
    raise SystemExit(f'put status esperado 1x, encontrado {s.count(old2)}')
s = s.replace(old2, new2, 1)
p.write_text(s, encoding='utf-8')

g = Path('app/build.gradle')
b = g.read_text(encoding='utf-8')
b = b.replace('// 1.3.7 final: Flash estável por oferta + piso absoluto de R$5 para tarifa principal.',
              '// 1.3.8: status simples Aguardando confirmação/Confirmada + origem separada por tag.')
if 'versionCode 220' not in b or 'versionName "1.3.7"' not in b:
    raise SystemExit('versão base inesperada')
b = b.replace('versionCode 220', 'versionCode 221', 1)
b = b.replace('versionName "1.3.7"', 'versionName "1.3.8"', 1)
g.write_text(b, encoding='utf-8')

Path('.tmp-finalize-trip-status.py').unlink(missing_ok=True)
Path('.github/workflows/finalize-trip-status.yml').unlink(missing_ok=True)
