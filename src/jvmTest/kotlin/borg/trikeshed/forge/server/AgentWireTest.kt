package borg.trikeshed.forge.server

import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.lcnc.AgentCliInfo
import borg.trikeshed.lcnc.AgentNodes
import borg.trikeshed.lcnc.AgentRunResult
import borg.trikeshed.lcnc.InMemoryAgentRuns
import borg.trikeshed.parse.json.JsonSupport
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** `/api/agents` and `/api/agents/runs`: the roster and the receipts, read-only. */
class AgentWireTest {

    @Suppress("UNCHECKED_CAST")
    private fun json(body: String) = JsonSupport.parse(body) as Map<String, Any?>

    @Test
    fun rosterAndRunsAreServedNewestFirstAndFiltered(): Unit = runBlocking {
        val board = ConfixBlackboard.empty()
        val runs = InMemoryAgentRuns(listOf(AgentCliInfo("codex", "/opt/homebrew/bin/codex", "codex-cli 0.145.0", true), AgentCliInfo("claude", "/x/claude", "2.1", false, why = "installed; enable with --agents"))) { req ->
            AgentRunResult(runId = req.runId, agent = req.agent)
        }
        for ((i, job) in listOf("j1", "j2", "j1").withIndex()) {
            val r = AgentRunResult(runId = "r$i", jobId = job, agent = "codex", startedAtMs = 1000L + i, finishedAtMs = 2000L + i, exit = 0)
            board.put(AgentNodes.RECEIPT_PREFIX + r.runId, r.receipt(), AgentNodes.LANGUAGE)
        }
        val wire = AgentWire(runs, board, probedAtMs = 42L, enabledBy = "default")
        val roster = json(wire.route("GET", "/api/agents", "", null)!!.body)
        assertEquals(42L, (roster["probedAtMs"] as Number).toLong()); assertEquals("default", roster["enabledBy"])
        val agents = roster["agents"] as List<Map<*, *>>
        assertEquals(listOf("codex", "claude"), agents.map { it["id"] })
        assertEquals(false, agents[1]["enabled"]); assertEquals("installed; enable with --agents", agents[1]["why"])

        val all = json(wire.route("GET", "/api/agents/runs", "", null)!!.body)
        assertEquals(3, (all["count"] as Number).toInt())
        assertEquals(listOf("r2", "r1", "r0"), (all["runs"] as List<Map<*, *>>).map { it["runId"] }, "newest first")
        val j1 = json(wire.route("GET", "/api/agents/runs?jobId=j1&limit=1", "", null)!!.body)
        assertEquals(1, (j1["count"] as Number).toInt()); assertEquals("r2", (j1["runs"] as List<Map<*, *>>).single()["runId"])
        assertNull(wire.route("GET", "/api/agents/nope", "", null))
        assertNull(wire.route("POST", "/api/agents", "", null))
    }
}
