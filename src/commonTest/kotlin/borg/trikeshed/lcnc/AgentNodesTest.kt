package borg.trikeshed.lcnc

import borg.trikeshed.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The pure half of the coding-agent lane: contracts, request building, the legos over the seam, the receipt. */
class AgentNodesTest {

    private val roster = listOf(
        AgentCliInfo("stub", "/usr/bin/stub", "0.1", enabled = true),
        AgentCliInfo("offlane", "/x/offlane", "2.1", enabled = false, why = "not in the default set"),
    )

    private fun runs(exit: Int = 0, killed: Boolean = false) = InMemoryAgentRuns(roster) { req ->
        AgentRunResult(runId = req.runId, jobId = req.jobId, agent = req.agent, cli = "/usr/bin/stub", version = "0.1", model = req.model,
            repo = req.repo, base = "abc123", exit = exit, killed = killed, bytes = 40, kept = 40,
            transcriptCid = "sha256:t", patchCid = "sha256:p", patchBytes = 12, filesChanged = 1, summary = "did it: " + req.brief.take(10))
    }

    @Test
    fun servedTypesMatchTheContracts() {
        val declared = LcncContracts.all().filter { it.type.startsWith("agent.") }.map { it.type }.toSet()
        assertEquals(AgentNodes.servedTypes(), declared)
        val run = LcncContracts.all().first { it.type == AgentNodes.RUN }
        assertTrue(run.isEffect, "a run edits files; it is an effect")
        assertEquals("agent.list#agents[].id", run.params.getValue("agent").optsFrom)
    }

    @Test
    fun theRequestPrefersWiredInputsAndClampsTheBudget() {
        val node = LcncNode("a1", AgentNodes.RUN, params = mapOf("agent" to "Stub", "brief" to "from params", "budgetSeconds" to "9000", "retain" to "true"))
        val fromParams = AgentNodes.requestOf(node, emptyMap(), runId = "r1", jobId = "j1")
        assertEquals("stub", fromParams.agent, "lowercased")
        assertEquals("from params", fromParams.brief)
        assertEquals(AgentNodes.MAX_BUDGET_SECONDS, fromParams.budgetSeconds, "9000 clamps to the reaper's ceiling")
        assertTrue(fromParams.retain); assertEquals("r1", fromParams.runId); assertEquals("j1", fromParams.jobId)
        val wired = AgentNodes.requestOf(node, mapOf("brief?" to "from the cable", "budgetSeconds" to 30), runId = "r2")
        assertEquals("from the cable", wired.brief); assertEquals(30, wired.budgetSeconds)
        assertEquals(AgentNodes.DEFAULT_BUDGET_SECONDS, AgentNodes.clampBudget(null))
    }

    @Test
    fun theLegosRunOverTheSeamAndAreLoudWithoutAnAgent(): Unit = runBlocking {
        val seam = runs()
        val registry = AgentNodes.registry(seam) { "run-7" }
        val listed = registry.getValue(AgentNodes.LIST).run(LcncNode("l", AgentNodes.LIST), emptyMap())
        assertEquals(2, listed["count"])
        assertEquals(listOf("stub", "offlane"), (listed["agents"] as List<*>).map { (it as Map<*, *>)["id"] })
        val out = registry.getValue(AgentNodes.RUN).run(LcncNode("a", AgentNodes.RUN, params = mapOf("agent" to "stub")), mapOf("brief?" to "add hello.txt"))
        assertEquals("run-7", out["runId"]); assertEquals(true, out["ok"]); assertEquals("sha256:p", out["patchCid"]); assertEquals(0, out["exit"])
        assertEquals("add hello.txt", seam.requests.single().brief)
        assertFailsWith<IllegalArgumentException> { registry.getValue(AgentNodes.RUN).run(LcncNode("b", AgentNodes.RUN), mapOf("brief?" to "x")) }
        assertFailsWith<IllegalArgumentException> { registry.getValue(AgentNodes.RUN).run(LcncNode("c", AgentNodes.RUN, params = mapOf("agent" to "stub")), emptyMap()) }
    }

    @Test
    fun theReceiptNamesEverythingAndOkIsStrict() {
        val good = runs().let { AgentRunResult(runId = "r", agent = "stub", exit = 0, summary = "s".repeat(5000)) }
        assertTrue(good.ok)
        assertEquals(AgentNodes.SUMMARY_CHARS, (good.receipt()["summary"] as String).length)
        assertEquals(
            listOf("runId", "jobId", "agent", "cli", "version", "model", "repo", "base", "scratch", "retained", "startedAtMs", "finishedAtMs",
                "budgetSeconds", "exit", "ok", "killed", "bytes", "kept", "truncated", "transcriptCid", "patchCid", "patchBytes", "filesChanged", "summary", "error"),
            good.receipt().keys.toList(),
        )
        assertTrue(!AgentRunResult(runId = "r", agent = "stub", exit = 0, killed = true).ok, "a budget kill is not ok")
        assertTrue(!AgentRunResult(runId = "r", agent = "stub", exit = 1).ok)
        assertTrue(!AgentRunResult(runId = "r", agent = "stub", exit = 0, error = "clone failed").ok)
        assertEquals("blackboard/agent/run/r", AgentNodes.evidenceId("r"))
    }
}
