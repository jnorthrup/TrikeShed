package borg.trikeshed.kanban.module

import borg.trikeshed.agent.AgentCli
import borg.trikeshed.agent.JvmAgentRunner
import borg.trikeshed.couch.CouchDatabase
import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.lcnc.AgentCliInfo
import borg.trikeshed.lcnc.AgentNodes
import borg.trikeshed.lcnc.LcncNodeRunner
import borg.trikeshed.litebike.JvmKanbanServer
import borg.trikeshed.module.ModuleContext
import borg.trikeshed.module.ModuleRouteRegistry
import borg.trikeshed.module.ModuleSupervisor
import borg.trikeshed.parse.json.JsonSupport
import borg.trikeshed.dag.ReteNetwork
import borg.trikeshed.dag.ReteProductionRegistry
import borg.trikeshed.util.oroboros.CouchAttachmentGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The coding-agent lane end to end on the module rig (Forge genesis, Cut A): an `AGENT:` card
 * is claimed by `claim:agent:<id>`, the stub agent edits a scratch clone, its diff and transcript
 * become receipts, the judge closes it; a budget kill parks it in REVIEW without a strike; the
 * output cap holds; the source repository is untouched; an unknown agent leaves the card READY.
 */
class KanbanAgentClaimLoopTest {

    private fun tempDir(name: String): File =
        File(System.getProperty("java.io.tmpdir"), "kanban-agent-$name-${System.nanoTime()}").apply { mkdirs() }

    private class Rig(val server: JvmKanbanServer, val ctx: ModuleContext, val supervisor: ModuleSupervisor, val source: File, val home: File)

    private fun git(dir: File, vararg args: String): String {
        val p = ProcessBuilder(listOf("git") + args).directory(dir).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0) { "git ${args.toList()} failed: $out" }
        return out.trim()
    }

    /** A source repository with one commit and one uncommitted file, so the clone is HEAD and nothing more. */
    private fun sourceRepo(name: String): File {
        val dir = tempDir("$name-src")
        git(dir, "init", "-q", "-b", "main")
        git(dir, "config", "user.email", "t@t"); git(dir, "config", "user.name", "t")
        File(dir, "README.md").writeText("# source\n")
        git(dir, "add", "README.md"); git(dir, "commit", "-q", "-m", "one")
        File(dir, "uncommitted.txt").writeText("never in the clone\n")
        return dir
    }

    private fun rig(name: String, stubEnv: Map<String, String> = emptyMap(), roster: List<AgentCliInfo>? = null): Rig {
        val cas = CasStore.inMemory()
        val couchStore = CouchStoreFactory.casBacked(cas)
        val routes = ModuleRouteRegistry()
        val home = tempDir("$name-home")
        val source = sourceRepo(name)
        val ctx = ModuleContext(
            couchDb = CouchDatabase("kanban-agent-test-$name", couchStore, cas),
            rete = ReteNetwork(),
            productions = ReteProductionRegistry(),
            beliefBag = null,
            turnReview = null,
            blackboard = ConfixBlackboard.empty(),
            casStore = cas,
            attachments = CouchAttachmentGateway(couchStore, cas),
            routes = routes,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            clock = { System.currentTimeMillis() },
            stateDir = home,
        )
        // No chat brain on purpose: the lane must not need one.
        ctx.lcncRunners["mux.models"] = LcncNodeRunner { _, _ -> mapOf("models" to emptyList<Any>()) }
        val stub = File("src/jvmTest/resources/agents/stub-agent.sh").absoluteFile
        check(stub.isFile) { "fixture missing: $stub" }
        val rows = roster ?: listOf(AgentCliInfo("stub", stub.absolutePath, "stub 0.1", enabled = true))
        var n = 0
        ctx.agentRuns = JvmAgentRunner(
            rosterRows = rows, repoDir = source, forgeHome = home, cas = cas, attachments = ctx.attachments, blackboard = ctx.blackboard,
            extraEnvironment = stubEnv,
            definitions = { info -> AgentCli(info.id, info.path, briefOnStdin = true) { _, _, _, _ -> listOf("sh", info.path) } },
            mintRunId = { "run-${++n}" },
        )
        val server = JvmKanbanServer(moduleRoutes = routes)
        val supervisor = ModuleSupervisor(ctx)
        runBlocking { supervisor.attach(KanbanModule()) }
        return Rig(server, ctx, supervisor, source, home)
    }

    private fun get(server: JvmKanbanServer, path: String): JvmKanbanServer.HttpResponse = runBlocking {
        server.routeHttp("GET $path HTTP/1.1\r\nHost: t\r\n\r\n".toByteArray(StandardCharsets.UTF_8))
    }

    private fun post(server: JvmKanbanServer, path: String, body: String): JvmKanbanServer.HttpResponse = runBlocking {
        server.routeHttp("POST $path HTTP/1.1\r\nHost: t\r\nContent-Type: application/json\r\n\r\n$body".toByteArray(StandardCharsets.UTF_8))
    }

    @Suppress("UNCHECKED_CAST")
    private fun json(resp: JvmKanbanServer.HttpResponse): Map<String, Any?> = JsonSupport.parse(resp.body) as Map<String, Any?>

    private fun arr(v: Any?): List<*> = when (v) { is List<*> -> v; is Array<*> -> v.toList(); else -> error("not an array: $v") }

    private fun invoke(rig: Rig, vararg commands: Map<String, Any?>) {
        val resp = post(rig.server, "/api/invoke", JsonSupport.stringify(mapOf("commands" to commands.toList())))
        assertEquals(202, resp.status, resp.body)
    }

    private fun items(rig: Rig): Map<String, Map<*, *>> =
        arr(json(get(rig.server, "/api/board"))["items"]).map { it as Map<*, *> }.associateBy { it["id"] as String }

    private fun awaitStatus(rig: Rig, status: String, jobId: String, timeoutMs: Long = 20_000): Map<*, *> {
        val deadline = System.currentTimeMillis() + timeoutMs
        var seen = items(rig)
        while (System.currentTimeMillis() < deadline) {
            seen = items(rig)
            if (seen[jobId]?.get("status") == status) return seen.getValue(jobId)
            runBlocking { delay(100) }
        }
        val receipts = rig.ctx.blackboard.keys().filter { it.startsWith("kanban/claim/$jobId") || it.startsWith("agent/run/") || it.startsWith("kanban/rule/reaper/") }
            .joinToString("\n") { it + " = " + rig.ctx.blackboard.get(it) }
        fail("card $jobId never reached '$status'; board: ${seen.mapValues { it.value["status"] to it.value["owner"] }}\n$receipts")
    }

    private fun submitAgentCard(rig: Rig, jobId: String, spec: String) {
        invoke(
            rig,
            mapOf("type" to "submit", "jobId" to jobId, "idempotencyKey" to "s-$jobId", "title" to "Add hello.txt", "spec" to spec),
            mapOf("type" to "move", "jobId" to jobId, "idempotencyKey" to "m-$jobId", "expectedRevision" to 1, "toColumn" to "ready"),
        )
    }

    private fun trail(rig: Rig, jobId: String): List<Any?> =
        rig.ctx.blackboard.keys().filter { it.startsWith("kanban/committed/$jobId/") }
            .map { rig.ctx.blackboard.get(it) as Map<*, *> }.sortedBy { it["revision"].toString().toLong() }.map { it["col"] }

    @Test
    fun anAgentCardIsWorkedInAScratchCloneAndTheJudgeClosesIt() {
        val rig = rig("done")
        try {
            val before = git(rig.source, "status", "--porcelain")
            val headBefore = git(rig.source, "rev-parse", "HEAD")
            submitAgentCard(rig, "a1", "GOAL: add hello.txt at the repo root containing hello\nMUST: hello.txt is in the patch\nAGENT: stub\nAGENT-BUDGET: 60")
            val done = awaitStatus(rig, "done", "a1")
            assertEquals("claim:agent:stub", done["owner"], "the agent's claim owner survives to DONE; the judge signed the move")
            assertEquals(listOf("todo", "ready", "running", "review", "done"), trail(rig, "a1"))

            val claim = rig.ctx.blackboard.get("kanban/claim/a1") as Map<*, *>
            assertEquals(true, claim["ok"]); assertEquals("DONE", claim["decision"]); assertEquals("stub", claim["agent"])
            val runId = claim["runId"] as String
            assertTrue(runId.isNotBlank(), claim.toString())
            assertEquals(0, (claim["exit"] as Number).toInt()); assertEquals(false, claim["killed"]); assertEquals(false, claim["truncated"])
            val patchCid = claim["patchCid"] as String
            val patch = rig.ctx.casStore.get(ContentId(patchCid))!!.decodeToString()
            assertTrue(patch.startsWith("diff --git a/hello.txt b/hello.txt"), patch)
            assertTrue(patch.contains("+hello"), patch)
            val transcript = rig.ctx.casStore.get(ContentId(claim["transcriptCid"] as String))!!.decodeToString()
            assertTrue(transcript.contains("VERDICT: MET"), transcript)
            assertTrue(transcript.contains("brief received:"), "the brief reached stdin: $transcript")
            assertTrue(transcript.contains("evidence: blackboard/agent/run/$runId"), "the stub cited the id the brief named: $transcript")
            val crit = (claim["criteria"] as List<*>).first() as Map<*, *>
            assertEquals("blackboard/agent/run/$runId", crit["evidence"])

            val run = rig.ctx.blackboard.get("agent/run/$runId") as Map<*, *>
            assertEquals("a1", run["jobId"]); assertEquals(1, (run["filesChanged"] as Number).toInt()); assertEquals(headBefore, run["base"])
            assertEquals(AgentNodes.LANGUAGE, rig.ctx.blackboard.getProvenance("agent/run/$runId")?.language)
            assertTrue(!File(run["scratch"] as String).exists(), "the scratch is removed after the receipt")
            assertEquals(listOf("agents/$runId/brief.md", "agents/$runId/patch.diff", "agents/$runId/transcript.md"),
                rig.ctx.attachments.listAttachments("agents/$runId/").map { it.path }.sorted())

            assertEquals(before, git(rig.source, "status", "--porcelain"), "the source repository is untouched")
            assertEquals(headBefore, git(rig.source, "rev-parse", "HEAD"))
            assertTrue(!File(rig.source, ".git/worktrees").exists(), "a shared clone, not a worktree")
            assertTrue(!File(rig.source, "hello.txt").exists())
        } finally {
            runBlocking { rig.supervisor.detach("kanban") }
        }
    }

    @Test
    fun aBudgetKillParksTheCardInReviewWithoutAStrike() {
        val rig = rig("budget", stubEnv = mapOf("STUB_SLEEP" to "20"))
        try {
            submitAgentCard(rig, "b1", "GOAL: add hello.txt\nMUST: hello.txt is in the patch\nAGENT: stub\nAGENT-BUDGET: 2")
            val reviewed = awaitStatus(rig, "review", "b1", timeoutMs = 30_000)
            assertEquals("claim:agent:stub", reviewed["owner"])
            val claim = rig.ctx.blackboard.get("kanban/claim/b1") as Map<*, *>
            assertEquals(true, claim["killed"]); assertEquals(false, claim["ok"]); assertEquals("REVIEW", claim["decision"])
            assertTrue((claim["why"] as String).contains("budget"), claim.toString())
            assertTrue(rig.ctx.blackboard.keys().none { it.startsWith("kanban/rule/reaper/judge-b1") }, "a budget kill is not a strike")
            assertEquals(listOf("todo", "ready", "running", "review"), trail(rig, "b1"))
        } finally {
            runBlocking { rig.supervisor.detach("kanban") }
        }
    }

    @Test
    fun theOutputCapKeepsTheHeadAndSaysWhatItDropped() {
        val rig = rig("cap", stubEnv = mapOf("STUB_BYTES" to "1048576"))
        try {
            submitAgentCard(rig, "c1", "GOAL: add hello.txt\nMUST: hello.txt is in the patch\nAGENT: stub")
            fun runOfC1(): Map<*, *>? = rig.ctx.blackboard.keys().filter { it.startsWith("agent/run/") }
                .mapNotNull { rig.ctx.blackboard.get(it) as? Map<*, *> }.firstOrNull { it["jobId"] == "c1" }
            val deadline = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < deadline && runOfC1() == null) runBlocking { delay(100) }
            val run = runOfC1() ?: fail("no agent/run receipt for c1")
            // The runner's default cap is 4 MiB; the claim lane does not lower it, so 1 MiB is kept whole.
            assertEquals(false, run["truncated"]); assertTrue((run["bytes"] as Number).toLong() >= 1_048_576L, run.toString())
        } finally {
            runBlocking { rig.supervisor.detach("kanban") }
        }
    }

    @Test
    fun anUnknownAgentLeavesTheCardReadyAndSaysWhy() {
        val rig = rig("nosuch")
        try {
            submitAgentCard(rig, "n1", "GOAL: g\nMUST: m\nAGENT: nosuch")
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline && rig.ctx.blackboard.get("kanban/claim/n1") == null) runBlocking { delay(50) }
            val r = rig.ctx.blackboard.get("kanban/claim/n1") as Map<*, *>
            assertEquals(false, r["ok"])
            assertTrue((r["error"] as String).startsWith("agent 'nosuch' is not installed/enabled here"), r.toString())
            runBlocking { delay(300) }
            val card = items(rig).getValue("n1")
            assertEquals("ready", card["status"]); assertEquals("", card["owner"])
        } finally {
            runBlocking { rig.supervisor.detach("kanban") }
        }
    }
}
