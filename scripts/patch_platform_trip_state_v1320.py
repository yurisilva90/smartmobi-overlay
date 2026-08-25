from pathlib import Path

trip_path = Path('app/src/main/java/io/github/yurisilva90/smartmobi/TripReaderService.kt')
build_path = Path('app/build.gradle')
s = trip_path.read_text(encoding='utf-8')

old_decl = '''        private const val TRIP_STATE_DEBOUNCE = 3
        private const val TRIP_STATE_DEBOUNCE_WINDOW = 5
        private val tripSubStateHistory = ArrayDeque<String>()
        // Não é mais "private" (16/08/2026) — ProactiveAlert.kt precisa
        // saber o estado atual (online/buscar/corrida) pra decidir se pode
        // mostrar um alerta proativo e com qual tempo de espera (10s
        // corrida / 5s online). Nada na lógica de detecção mudou, só ficou
        // legível de fora.
        var confirmedTripSubState = "online"
'''
new_decl = '''        private const val TRIP_STATE_DEBOUNCE = 3
        private const val TRIP_STATE_DEBOUNCE_WINDOW = 5

        // v1.3.20 — estado 100% separado por plataforma.
        // Antes Uber e 99 escreviam no MESMO histórico de debounce e no mesmo
        // confirmedTripSubState. Assim, abrir a Uber durante uma corrida da 99
        // injetava votos "online" no buffer da 99 e derrubava o botão para Online.
        // Cada plataforma agora mantém seu histórico + estado próprios. O campo
        // público abaixo vira apenas a visão consolidada usada pelo widget/alertas.
        private val tripSubStateHistoryByPlat = HashMap<String, ArrayDeque<String>>()
        private val confirmedTripSubStateByPlat = hashMapOf(
            "UBER" to "online",
            "99" to "online"
        )
        private fun tripStateFor(plat: String): String =
            confirmedTripSubStateByPlat[plat] ?: "online"

        // ProactiveAlert/FloatingWidget continuam lendo um único estado visual.
        // Prioridade: qualquer corrida ativa > qualquer busca ativa > online.
        var confirmedTripSubState = "online"
'''
if old_decl not in s:
    raise SystemExit('declaracao compartilhada de estado nao encontrada')
s = s.replace(old_decl, new_decl, 1)

# Toda lógica interna da 99 deve consultar exclusivamente o estado da 99.
repls = {
    'if (confirmedTripSubState != "online" && nn99BuscandoOcrRe.containsMatchIn(joinedOcrText))':
        'if (tripStateFor("99") != "online" && nn99BuscandoOcrRe.containsMatchIn(joinedOcrText))',
    'if (confirmedTripSubState != "corrida" && !temChegueAntes && !temEspera && nn99CobrarPagamentoRe.containsMatchIn(joinedOcrText))':
        'if (tripStateFor("99") != "corrida" && !temChegueAntes && !temEspera && nn99CobrarPagamentoRe.containsMatchIn(joinedOcrText))',
    'if (confirmedTripSubState != "corrida" && !temChegueAntes && !temEspera && nn99FinalizarCorridaRe.containsMatchIn(joinedOcrText))':
        'if (tripStateFor("99") != "corrida" && !temChegueAntes && !temEspera && nn99FinalizarCorridaRe.containsMatchIn(joinedOcrText))',
    'if (confirmedTripSubState == "online") {\n                nn99WaitingBuscandoViaOcr = false':
        'if (tripStateFor("99") == "online") {\n                nn99WaitingBuscandoViaOcr = false',
    'currentlyOnline = confirmedTripSubState == "online"':
        'currentlyOnline = tripStateFor("99") == "online"',
    'nn99DebugCurrentlyOnline = confirmedTripSubState == "online"':
        'nn99DebugCurrentlyOnline = tripStateFor("99") == "online"',
    'raw = confirmedTripSubState\n            nn99DebugRPBefore':
        'raw = tripStateFor("99")\n            nn99DebugRPBefore',
}
for old, new in repls.items():
    if old not in s:
        raise SystemExit(f'padrao 99 nao encontrado: {old[:70]}')
    s = s.replace(old, new)

start = s.index('    // Debounce compartilhado por Uber e 99')
end = s.index('    // ── Captura automática: endereço mais completo visto em tela (Uber)', start)
old_func = s[start:end]
new_func = '''    // v1.3.20 — debounce independente por plataforma. Uber nunca mais vota
    // no buffer da 99 e vice-versa. O widget recebe somente o agregado visual.
    private fun applyTripSubStateDebounced(raw: String, plat: String) {
        val windowSize = RuleEngine.config("trip_state_debounce_window", TRIP_STATE_DEBOUNCE_WINDOW.toDouble()).toInt().coerceAtLeast(1)
        val required = RuleEngine.config("trip_state_debounce", TRIP_STATE_DEBOUNCE.toDouble()).toInt().coerceAtLeast(1)

        val history = tripSubStateHistoryByPlat.getOrPut(plat) { ArrayDeque<String>() }
        history.addLast(raw)
        while (history.size > windowSize) history.removeFirst()

        val counts = history.groupingBy { it }.eachCount()
        val best = counts.maxByOrNull { it.value } ?: return
        val currentPlatState = tripStateFor(plat)
        if (best.value >= required && currentPlatState != best.key) {
            val prev = currentPlatState
            confirmedTripSubStateByPlat[plat] = best.key

            // Visão única do botão: uma plataforma online não pode apagar a
            // atividade da outra. Corrida sempre vence Buscar, que vence Online.
            confirmedTripSubState = when {
                confirmedTripSubStateByPlat.values.any { it == "corrida" } -> "corrida"
                confirmedTripSubStateByPlat.values.any { it == "buscar" } -> "buscar"
                else -> "online"
            }
            MainActivity.floatingWidget?.updateTripState(confirmedTripSubState)

            // Reset exclusivo da 99 — nunca acionado por transição da Uber.
            if (plat == "99" && best.key == "online") {
                nn99KnownDestAddr = null
                nn99ReachedPickup = false
                nn99NavSemChegueCount = 0
                nn99ReachedPickupReason = "online_confirmado"
            }

            // Captura e persistência recebem a transição da plataforma correta,
            // preservando início/meio/fim independentes para Uber e 99.
            AutoTripCapture.onStateTransition(this, plat, prev, best.key)
            JourneyStatusTracker.onStateTransition(this, plat, prev, best.key)
        }
    }

'''
s = s[:start] + new_func + s[end:]
trip_path.write_text(s, encoding='utf-8')

b = build_path.read_text(encoding='utf-8')
if 'versionCode 232' not in b or 'versionName "1.3.19"' not in b:
    raise SystemExit('versao 1.3.19/232 nao encontrada')
b = b.replace('// 1.3.19: Uber via acessibilidade; OCR da 99 por gatilho; notificação estruturada.',
              '// 1.3.20: estados Uber/99 totalmente independentes; status agregado por prioridade.')
b = b.replace('versionCode 232', 'versionCode 233', 1)
b = b.replace('versionName "1.3.19"', 'versionName "1.3.20"', 1)
build_path.write_text(b, encoding='utf-8')

print('v1.3.20 patch aplicado')
