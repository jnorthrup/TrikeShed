package borg.trikeshed.lcnc

import borg.trikeshed.lib.*
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
        assertEquals("id", run.inputKinds["jobId"])
        assertEquals("json", run.outputKinds["receipt"])
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
        val task = node.copy(params = node.params + ("jobId" to "task-param"))
        assertEquals("task-param", AgentNodes.requestOf(task, emptyMap(), "r3").jobId)
        assertEquals("task-wired", AgentNodes.requestOf(task, mapOf("jobId?" to "task-wired"), "r3").jobId)
        assertEquals("task-explicit", AgentNodes.requestOf(task, mapOf("jobId" to "task-wired"), "r3", "task-explicit").jobId)
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
        assertEquals("", out["jobId"])
        val receipt = out["receipt"] as Map<*, *>
        assertEquals("run-7", receipt["runId"])
        assertEquals("abc123", receipt["base"])
        assertEquals(12L, receipt["patchBytes"])
        assertEquals("add hello.txt", seam.requests.single().brief)
        assertFailsWith<IllegalArgumentException> { registry.getValue(AgentNodes.RUN).run(LcncNode("b", AgentNodes.RUN), mapOf("brief?" to "x")) }
        assertFailsWith<IllegalArgumentException> { registry.getValue(AgentNodes.RUN).run(LcncNode("c", AgentNodes.RUN, params = mapOf("agent" to "stub")), emptyMap()) }
    }

    @Test
    fun mismatchedAgentResultsCannotBecomeTaskEvidence(): Unit = runBlocking {
        val node = LcncNode("agent", AgentNodes.RUN, params = mapOf("agent" to "stub", "brief" to "review", "jobId" to "task"))
        for (field in s_["runId", "jobId", "agent"].view) {
            val seam = InMemoryAgentRuns(roster) { request ->
                AgentRunResult(runId = if (field == "runId") "other" else request.runId,
                    jobId = if (field == "jobId") "other" else request.jobId,
                    agent = if (field == "agent") "other" else request.agent, exit = 0)
            }
            assertFailsWith<IllegalStateException> { AgentNodes.registry(seam) { "run" }.getValue(AgentNodes.RUN).run(node, emptyMap()) }
            assertEquals(1, seam.requests.size)
        }
        val seam = runs()
        assertFailsWith<IllegalArgumentException> { AgentNodes.registry(seam) { " " }.getValue(AgentNodes.RUN).run(node, emptyMap()) }
        assertTrue(seam.requests.isEmpty())
    }

    @Test
    fun nestedTaskRunsReturnCorrelatedReceiptsAndSummaries(): Unit = runBlocking {
        val program = LcncProgram("tasks", s_[
            LcncNode("tasks", "scope.in", params = mapOf("name" to "taskIds")),
            LcncNode("workers", "scope", params = mapOf("item" to "taskId"), children = s_[
                LcncNode("task", "scope.in", params = mapOf("name" to "taskId", "kind" to "id")),
                LcncNode("worker", "scope", children = s_[
                    LcncNode("agent", AgentNodes.RUN, params = mapOf("agent" to "stub", "brief" to "review")),
                    LcncNode("receipt", "scope.out", params = mapOf("name" to "receipt")),
                    LcncNode("summary", "scope.out", params = mapOf("name" to "summary")),
                ]),
                LcncNode("worker-receipt", "scope.out", params = mapOf("name" to "receipt")),
                LcncNode("worker-summary", "scope.out", params = mapOf("name" to "summary")),
            ]),
            LcncNode("digest", "list.format", params = mapOf("template" to "{summary}")),
            LcncNode("results", "scope.out", params = mapOf("name" to "results")),
            LcncNode("summaries", "scope.out", params = mapOf("name" to "summaries")),
        ], s_[
            LcncWire("tasks", "value", "workers", "each?"),
            LcncWire("task", "value", "agent", "jobId?"),
            LcncWire("agent", "receipt", "receipt", "value"),
            LcncWire("agent", "summary", "summary", "value"),
            LcncWire("worker", "receipt", "worker-receipt", "value"),
            LcncWire("worker", "summary", "worker-summary", "value"),
            LcncWire("workers", "returns", "digest", "x"),
            LcncWire("workers", "returns", "results", "value"),
            LcncWire("digest", "lines", "summaries", "value"),
        ])
        assertEquals(emptyList(), LcncTypeCheck.check(program))
        val seam = runs()
        var sequence = 0
        val result = LcncRunner(PureNodes.registry { 0L } + AgentNodes.registry(seam) { "run-${++sequence}" })
            .runProcedure(program, mapOf("taskIds" to listOf("task/a", "task/b")))
        assertEquals(listOf("task/a", "task/b"), seam.requests.map { it.jobId })
        val results = result.returns["results"] as List<*>
        assertEquals(2, results.size)
        for ((index, row) in results.withIndex()) {
            val receipt = (row as Map<*, *>)["receipt"] as Map<*, *>
            assertEquals("task/${if (index == 0) "a" else "b"}", receipt["jobId"])
            assertEquals("run-${index + 1}", receipt["runId"])
            assertEquals("sha256:p", receipt["patchCid"])
            assertEquals("abc123", receipt["base"])
        }
        assertEquals(listOf("did it: review", "did it: review"), result.returns["summaries"])
    }

    @Test
    fun theReceiptNamesEverythingAndOkIsStrict() {
        val good = runs().let { AgentRunResult(runId = "r", agent = "stub", exit = 0, summary = "s".repeat(5000)) }
        assertTrue(good.ok)
        assertEquals(AgentNodes.SUMMARY_CHARS, (good.receipt()["summary"] as String).length)
        assertEquals(
            listOf("runId", "jobId", "agent", "cli", "version", "model", "repo", "base", "scratch", "retained", "startedAtMs", "finishedAtMs",
                "budgetSeconds", "exit", "ok", "killed", "bytes", "kept", "truncated", "transcriptCid", "patchCid", "patchBytes", "filesChanged", "files", "summary", "error"),
            good.receipt().keys.toList(),
        )
        assertTrue(!AgentRunResult(runId = "r", agent = "stub", exit = 0, killed = true).ok, "a budget kill is not ok")
        assertTrue(!AgentRunResult(runId = "r", agent = "stub", exit = 1).ok)
        assertTrue(!AgentRunResult(runId = "r", agent = "stub", exit = 0, error = "clone failed").ok)
        assertEquals("blackboard/agent/run/r", AgentNodes.evidenceId("r"))
    }
}
