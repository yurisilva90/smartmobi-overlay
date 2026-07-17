package io.github.yurisilva90.smartmobi

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

// ══════════════════════════════════════════════════════════════════
// Motor de regras de detecção de estado (Online/Buscar/Corrida).
//
// CRIADO 14/07/2026 a pedido do Yuri: até a v1.0.50, qualquer ajuste
// nos padrões de texto (ex: "Chegue antes de", timeout de segurança)
// exigia build + download + instalação manual do APK toda vez — mesmo
// pra mudanças pequenas de 1 linha de regex.
//
// Agora as regras (padrão de texto, prioridade, resultado, se liga o
// flag de reachedPickup) ficam na tabela `state_detection_rules` do
// Supabase, e os números ajustáveis (timeout de segurança, debounce)
// em `state_detection_config`. O app:
//   1. Carrega o cache local (SharedPreferences) na hora — instantâneo,
//      não trava a primeira leitura esperando rede.
//   2. Busca a versão mais recente do Supabase em background, no máximo
//      1x a cada REFRESH_INTERVAL_MS, e atualiza o cache pra próxima vez.
//   3. Se nunca houve fetch bem-sucedido (1a instalação sem internet),
//      usa os padrões embutidos abaixo — espelham exatamente a v1.0.50.
//
// O que NÃO está aqui de propósito (continua no Kotlin, exige APK novo
// pra mudar): o heurístico de troca de endereço da 99 (é lógica com
// estado entre leituras, não um padrão de texto isolado), o filtro de
// vazamento do próprio widget, e o parsing de oferta/OCR em si.
// ══════════════════════════════════════════════════════════════════
object RuleEngine {

    data class Rule(
        val key: String,
        val priority: Int,
        val pattern: Regex,
        val result: String, // "online" | "buscar" | "corrida" | "branch_reached_pickup"
        val setReachedPickup: Boolean?, // null = não mexe no flag
        val onlyIfNotReachedPickup: Boolean,
        val resetKnownAddr: Boolean,
        // AJUSTE (15/07/2026): se true, a regra só é considerada na leitura
        // em que o endereço do cabeçalho mudou de verdade — e nesse caso
        // IGNORA onlyIfNotReachedPickup, porque a troca de endereço junto
        // com o padrão já é prova forte o bastante sozinha (ex: "Corrida
        // aceita" + endereço novo = pickup novo de verdade, mesmo que o
        // flag de chegada tivesse ficado preso true da corrida anterior).
        val requiresAddrChanged: Boolean = false,
        // AJUSTE (16/07/2026): se true, a regra só é considerada quando o
        // status CONFIRMADO atual (antes dessa leitura) já é Online — prova
        // de que passou pela avaliação de verdade. Distingue "corrida nova"
        // (só existe depois de Online) de "sobreposição com corrida em
        // andamento" (status atual seria Buscar/Corrida, não Online).
        val requiresCurrentlyOnline: Boolean = false
    )

    data class Evaluation(
        val state: String,
        val newReachedPickup: Boolean,
        val resetKnownAddr: Boolean,
        val matched: Boolean
    )

    private const val PREFS_NAME = "smartmobi_rules_cache_v1"
    private const val KEY_RULES_JSON = "rules_json"
    private const val KEY_CONFIG_JSON = "config_json"
    private const val KEY_LAST_FETCH_MS = "last_fetch_ms"
    private const val REFRESH_INTERVAL_MS = 10 * 60 * 1000L // 10 min

    @Volatile private var loaded = false
    @Volatile private var rulesByPlatform: Map<String, List<Rule>> = emptyMap()
    @Volatile private var configByKey: Map<String, Double> = emptyMap()

    // Espelha EXATAMENTE as regras da v1.0.50 — usado só se o cache local
    // estiver vazio e o primeiro fetch ainda não tiver acontecido/falhado.
    private fun defaultRulesJson(): String = """
        [
          {"key":"uber_encerrar","platform":"uber","priority":10,"pattern":"\\bencerrar\\s+\\S","result":"corrida"},
          {"key":"uber_destino_de","platform":"uber","priority":20,"pattern":"\\bDestino de\\b","result":"corrida"},
          {"key":"uber_tail","platform":"uber","priority":30,"pattern":"\\bColetar pagamento\\b|\\bPagamento realizado\\b|\\bComo foi a viagem\\b","result":"corrida"},
          {"key":"uber_iniciar","platform":"uber","priority":40,"pattern":"\\biniciar\\s+\\S","result":"buscar"},
          {"key":"uber_reforco","platform":"uber","priority":50,"pattern":"\\bEncontro com\\b|\\bAguardando usuário\\b|\\bUsuário notificado\\b","result":"buscar"},
          {"key":"nn99_avaliacao","platform":"99","priority":10,"pattern":"Como foi sua corrida|Avaliar como anônimo|Valor da corrida","result":"online","set_reached_pickup":false,"reset_known_addr":true},
          {"key":"nn99_final","platform":"99","priority":20,"pattern":"Finalizar corrida","result":"corrida"},
          {"key":"nn99_chegue_antes","platform":"99","priority":30,"pattern":"Chegue antes de \\d{1,2}:\\d{2}","result":"buscar","set_reached_pickup":false},
          {"key":"nn99_chegou_espera","platform":"99","priority":40,"pattern":"Passaremos a cobrar uma taxa de espera|Iniciar corrida|Você está perto do local de embarque|Calculando taxa de espera|Você receberá a taxa de espera total","result":"buscar","set_reached_pickup":true},
          {"key":"nn99_botao_cheguei","platform":"99","priority":50,"pattern":"Cheguei no embarque","result":"buscar"},
          {"key":"nn99_aceite_pickup_novo","platform":"99","priority":55,"pattern":"Corrida encontrada|Corrida aceita|Vamos nessa","result":"buscar","set_reached_pickup":false,"requires_addr_changed":true,"requires_currently_online":true},
          {"key":"nn99_aceite","platform":"99","priority":60,"pattern":"Corrida encontrada|Corrida aceita|Vamos nessa","result":"buscar","only_if_not_reached_pickup":true},
          {"key":"nn99_navegando","platform":"99","priority":70,"pattern":"km/h","result":"branch_reached_pickup"},
          {"key":"nn99_aguardando","platform":"99","priority":80,"pattern":"Buscando|Procurando viagens","result":"online","set_reached_pickup":false,"reset_known_addr":true}
        ]
    """.trimIndent()

    private fun defaultConfigJson(): String = """
        [
          {"key":"99_safety_timeout_ms","value":60000},
          {"key":"trip_state_debounce","value":3},
          {"key":"trip_state_debounce_window","value":5}
        ]
    """.trimIndent()

    // Chamado no começo de cada leitura — barato, só faz trabalho real na
    // primeira vez (carrega do cache/default pra memória).
    fun ensureLoaded(ctx: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val rulesJson = prefs.getString(KEY_RULES_JSON, null) ?: defaultRulesJson()
            val configJson = prefs.getString(KEY_CONFIG_JSON, null) ?: defaultConfigJson()
            applyJson(rulesJson, configJson)
            loaded = true
        }
    }

    // Busca a versão mais nova do Supabase em background (thread própria,
    // nunca bloqueia a leitura de tela). No máximo 1x a cada 10min, ou na
    // hora se force=true (ex: usuário pediu pra sincronizar manualmente).
    fun refreshIfDue(ctx: Context, force: Boolean = false) {
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastFetch = prefs.getLong(KEY_LAST_FETCH_MS, 0L)
        if (!force && System.currentTimeMillis() - lastFetch < REFRESH_INTERVAL_MS) return
        thread {
            try {
                val rulesBody = httpGet(
                    "${TripReaderService.SUPABASE_URL}/rest/v1/state_detection_rules" +
                        "?active=eq.true&select=key,platform,priority,pattern,result,set_reached_pickup," +
                        "only_if_not_reached_pickup,reset_known_addr&order=platform.asc,priority.asc"
                ) ?: return@thread // sem rede/erro — mantém o que já está carregado, tenta de novo depois

                val configBody = httpGet(
                    "${TripReaderService.SUPABASE_URL}/rest/v1/state_detection_config?select=key,value"
                ) ?: prefs.getString(KEY_CONFIG_JSON, null) ?: defaultConfigJson()

                // valida antes de aplicar/salvar — regras vazias ou JSON
                // quebrado do banco nunca devem apagar um cache bom que já
                // funcionava.
                val parsedRules = parseRules(rulesBody)
                if (parsedRules.isEmpty()) return@thread

                applyJson(rulesBody, configBody)
                prefs.edit()
                    .putString(KEY_RULES_JSON, rulesBody)
                    .putString(KEY_CONFIG_JSON, configBody)
                    .putLong(KEY_LAST_FETCH_MS, System.currentTimeMillis())
                    .apply()
            } catch (_: Exception) {
                // sem internet, timeout, JSON malformado etc — nunca derruba
                // o app, só mantém o cache/default local até a próxima tentativa.
            }
        }
    }

    private fun httpGet(urlStr: String): String? {
        return try {
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("apikey", TripReaderService.SUPABASE_ANON)
            conn.setRequestProperty("Authorization", "Bearer ${TripReaderService.SUPABASE_ANON}")
            val code = conn.responseCode
            val body = if (code in 200..299) conn.inputStream.bufferedReader().readText() else null
            conn.disconnect()
            body
        } catch (_: Exception) { null }
    }

    private fun parseRules(rulesJson: String): Map<String, List<Rule>> {
        val byPlat = HashMap<String, MutableList<Rule>>()
        try {
            val arr = JSONArray(rulesJson)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val platform = o.optString("platform", "")
                val patternStr = o.optString("pattern", "")
                if (platform.isEmpty() || patternStr.isEmpty()) continue
                val rule = try {
                    Rule(
                        key = o.optString("key", "rule_$i"),
                        priority = o.optInt("priority", 999),
                        pattern = Regex(patternStr, RegexOption.IGNORE_CASE),
                        result = o.optString("result", "online"),
                        setReachedPickup = if (o.has("set_reached_pickup") && !o.isNull("set_reached_pickup"))
                            o.getBoolean("set_reached_pickup") else null,
                        onlyIfNotReachedPickup = o.optBoolean("only_if_not_reached_pickup", false),
                        resetKnownAddr = o.optBoolean("reset_known_addr", false),
                        requiresAddrChanged = o.optBoolean("requires_addr_changed", false),
                        requiresCurrentlyOnline = o.optBoolean("requires_currently_online", false)
                    )
                } catch (_: Exception) { null } // 1 regex inválida vinda do banco não derruba as outras
                if (rule != null) byPlat.getOrPut(platform) { mutableListOf() }.add(rule)
            }
        } catch (_: Exception) { return emptyMap() }
        return byPlat.mapValues { (_, list) -> list.sortedBy { it.priority } }
    }

    private fun parseConfig(configJson: String): Map<String, Double> {
        val map = HashMap<String, Double>()
        try {
            val arr = JSONArray(configJson)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                map[o.optString("key")] = o.optDouble("value", 0.0)
            }
        } catch (_: Exception) { }
        return map
    }

    private fun applyJson(rulesJson: String, configJson: String) {
        val parsedRules = parseRules(rulesJson)
        if (parsedRules.isNotEmpty()) rulesByPlatform = parsedRules
        val parsedConfig = parseConfig(configJson)
        if (parsedConfig.isNotEmpty()) configByKey = parsedConfig
    }

    // Avalia as regras da plataforma em ordem de prioridade, primeira que
    // bater vence. `matched=false` quando nenhuma regra bateu — quem chama
    // decide o fallback (rede de segurança / mantém último estado).
    fun evaluate(platform: String, texts: List<String>, reachedPickup: Boolean, addrChanged: Boolean = false, currentlyOnline: Boolean = false): Evaluation {
        val joined = texts.joinToString(" ")
        val rules = rulesByPlatform[platform] ?: emptyList()
        for (rule in rules) {
            if (rule.requiresAddrChanged && !addrChanged) continue
            if (rule.requiresCurrentlyOnline && !currentlyOnline) continue
            // requiresAddrChanged=true ignora de propósito onlyIfNotReachedPickup
            // (ver comentário no data class Rule) — a troca de endereço já é
            // prova forte o bastante sozinha.
            if (rule.onlyIfNotReachedPickup && reachedPickup && !rule.requiresAddrChanged) continue
            if (!rule.pattern.containsMatchIn(joined)) continue
            val state = if (rule.result == "branch_reached_pickup") {
                if (reachedPickup) "corrida" else "buscar"
            } else rule.result
            val newFlag = rule.setReachedPickup ?: reachedPickup
            return Evaluation(state, newFlag, rule.resetKnownAddr, true)
        }
        return Evaluation("", reachedPickup, false, false)
    }

    fun config(key: String, default: Double): Double = configByKey[key] ?: default
}
