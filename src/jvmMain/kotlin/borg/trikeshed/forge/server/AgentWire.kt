package borg.trikeshed.forge.server

import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.lcnc.AgentNodes
import borg.trikeshed.lcnc.AgentRuns
import borg.trikeshed.litebike.JvmKanbanServer
import borg.trikeshed.parse.json.JsonSupport

/**
 * The coding-agent lane on the wire (Forge genesis, Cut A): the roster this host resolved at
 * boot, and the `agent/run/<runId>` receipts newest first. Read-only; a run is started by a
 * card's `AGENT:` line or the `agent.run` lego, never by a route.
 */
class AgentWire(
    private val runs: AgentRuns,
    private val blackboard: ConfixBlackboard,
    private val probedAtMs: Long,
    private val enabledBy: String,
) {
    companion object {
        val ROUTES: List<Pair<String, String>> = listOf("GET" to "/api/agents", "GET" to "/api/agents/runs")
        const val DEFAULT_LIMIT = 50
        const val MAX_LIMIT = 500
    }

    suspend fun route(method: String, path: String, text: String, respond: (suspend (ByteArray) -> Unit)?): JvmKanbanServer.HttpResponse? {
        val p = path.substringBefore('?')
        return when {
            method == "GET" && p == "/api/agents" -> json(
                mapOf("probedAtMs" to probedAtMs, "enabledBy" to enabledBy, "agents" to runs.roster().map { it.toMap() }),
            )
            method == "GET" && p == "/api/agents/runs" -> {
                val query = path.substringAfter('?', "").split('&').filter { it.contains('=') }
                    .associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }
                val limit = query["limit"]?.toIntOrNull()?.coerceIn(1, MAX_LIMIT) ?: DEFAULT_LIMIT
                val jobId = query["jobId"]?.takeIf { it.isNotBlank() }
                val rows = blackboard.keys().filter { it.startsWith(AgentNodes.RECEIPT_PREFIX) }
                    .mapNotNull { k -> (blackboard.get(k) as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value } }
                    .filter { jobId == null || it["jobId"] == jobId }
                    .sortedByDescending { (it["startedAtMs"] as? Number)?.toLong() ?: 0L }
                    .take(limit)
                json(mapOf("runs" to rows, "count" to rows.size))
            }
            else -> null
        }
    }

    private fun json(value: Any?, status: Int = 200) = JvmKanbanServer.HttpResponse(status, JsonSupport.stringify(value))
}
