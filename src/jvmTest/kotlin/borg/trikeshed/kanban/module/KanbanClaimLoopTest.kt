package borg.trikeshed.kanban.module

import borg.trikeshed.couch.CouchDatabase
import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.dag.ReteNetwork
import borg.trikeshed.dag.ReteProductionRegistry
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.job.CasStore
import borg.trikeshed.kanban.BoardClaimWorker
import borg.trikeshed.lcnc.LcncNodeRunner
import borg.trikeshed.litebike.JvmKanbanServer
import borg.trikeshed.module.ModuleContext
import borg.trikeshed.module.ModuleRouteRegistry
import borg.trikeshed.module.ModuleSupervisor
import borg.trikeshed.parse.json.JsonSupport
import borg.trikeshed.util.oroboros.CouchAttachmentGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The loop, end to end through the module: READY → claim fires → RUNNING
 * (owner claim:brain) → the brain answers → `kanban/claim/<jobId>` → REVIEW.
 * The brain is a fake `prompt.chat` runner in ctx.lcncRunners — the exact seam
 * the daemon fills with ModelMux.
 */
class KanbanClaimLoopTest {

    private fun tempDir(name: String): File =
        File(System.getProperty("java.io.tmpdir"), "kanban-claim-$name-${System.nanoTime()}").apply { mkdirs() }

    private class Rig(val server: JvmKanbanServer, val ctx: ModuleContext, val supervisor: ModuleSupervisor)

    private fun rig(name: String, brain: LcncNodeRunner?, models: List<String> = listOf("hermes-newest", "older")): Rig {
        val cas = CasStore.inMemory()
        val couchStore = CouchStoreFactory.casBacked(cas)
        val routes = ModuleRouteRegistry()
        val ctx = ModuleContext(
            couchDb = CouchDatabase("kanban-claim-test-$name", couchStore, cas),
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
            stateDir = tempDir(name),
        )
        brain?.let { ctx.lcncRunners["prompt.chat"] = it }
        ctx.lcncRunners["mux.models"] = LcncNodeRunner { _, _ -> mapOf("models" to models.map { mapOf("id" to it, "caps" to emptyList<String>(), "provider" to "fake") }) }
        val server = JvmKanbanServer(moduleRoutes = routes)
        val supervisor = ModuleSupervisor(ctx)
        runBlocking { supervisor.attach(KanbanModule()) }
        return Rig(server, ctx, supervisor)
    }

    private fun get(server: JvmKanbanServer, path: String): JvmKanbanServer.HttpResponse = runBlocking {
        server.routeHttp("GET $path HTTP/1.1\r\nHost: t\r\n\r\n".toByteArray(StandardCharsets.UTF_8))
    }

    private fun post(server: JvmKanbanServer, path: String, body: String): JvmKanbanServer.HttpResponse = runBlocking {
        server.routeHttp("POST $path HTTP/1.1\r\nHost: t\r\nContent-Type: application/json\r\n\r\n$body".toByteArray(StandardCharsets.UTF_8))
    }

    @Suppress("UNCHECKED_CAST")
    private fun json(resp: JvmKanbanServer.HttpResponse): Map<String, Any?> = JsonSupport.parse(resp.body) as Map<String, Any?>

    private fun arr(v: Any?): List<*> = when (v) {
        is List<*> -> v
        is Array<*> -> v.toList()
        else -> error("not an array: $v")
    }

    private fun invoke(rig: Rig, vararg commands: Map<String, Any?>): List<Map<*, *>> {
        val resp = post(rig.server, "/api/invoke", JsonSupport.stringify(mapOf("commands" to commands.toList())))
        assertEquals(202, resp.status, resp.body)
        return arr(json(resp)["results"]).map { it as Map<*, *> }
    }

    private fun items(rig: Rig): Map<String, Map<*, *>> =
        arr(json(get(rig.server, "/api/board"))["items"]).map { it as Map<*, *> }.associateBy { it["id"] as String }

    /** Poll the rendered board until every named card sits in [status], or fail with what was seen. */
    private fun awaitStatus(rig: Rig, status: String, vararg jobIds: String, timeoutMs: Long = 10_000): Map<String, Map<*, *>> {
        val deadline = System.currentTimeMillis() + timeoutMs
        var seen = items(rig)
        while (System.currentTimeMillis() < deadline) {
            seen = items(rig)
            if (jobIds.all { seen[it]?.get("status") == status }) return seen
            runBlocking { delay(100) }
        }
        fail("cards ${jobIds.toList()} never reached '$status'; board: ${seen.mapValues { it.value["status"] to it.value["owner"] }}")
    }

    @Test
    fun theJudgeClosesAMetCardAndParksAHumanReviewCard() {
        val prompts = CopyOnWriteArrayList<Map<String, String>>()
        // The brain answers in the RFC reply shape, citing the card's own fact on the plane.
        val brain = LcncNodeRunner { node, _ ->
            prompts.add(node.params)
            val id = node.id.removePrefix("claim-")
            mapOf(
                "ok" to true,
                "content" to "VERDICT: MET\nMUST-1: MET — evidence: board/$id\nACTION: Next: split it into a test first.",
                "model" to node.params["model"], "cached" to false,
            )
        }
        val rig = rig("loop", brain)
        try {
            invoke(
                rig,
                mapOf("type" to "submit", "jobId" to "c1", "idempotencyKey" to "s1", "title" to "Wire the reaper",
                    "spec" to "GOAL: the reaper is wired\nMUST: name the file that registers it"),
                mapOf("type" to "submit", "jobId" to "c2", "idempotencyKey" to "s2", "title" to "Prove it live", "tags" to listOf("human-review")),
                mapOf("type" to "move", "jobId" to "c1", "idempotencyKey" to "m1", "expectedRevision" to 1, "toColumn" to "ready"),
                mapOf("type" to "move", "jobId" to "c2", "idempotencyKey" to "m2", "expectedRevision" to 1, "toColumn" to "ready"),
            )
            // c1: every MUST met with evidence on the plane → the judge closes it (RUNNING → REVIEW → DONE)
            val done = awaitStatus(rig, "done", "c1")
            assertEquals("claim:brain", done.getValue("c1")["owner"], "DONE keeps the claimant as owner; the judge signed the move")
            // c2: tagged for a person → REVIEW no matter what the reply said
            val reviewed = awaitStatus(rig, "review", "c2")
            assertEquals("claim:brain", reviewed.getValue("c2")["owner"])

            assertEquals(2, prompts.size, "one brain call per claim: $prompts")
            val byJob = prompts.associateBy { it["prompt"]!!.substringAfter("Card ").substringBefore(" ") }
            val prompt = byJob.getValue("c1")["prompt"].toString()
            assertTrue(prompt.startsWith("Card c1 — brief (RFC 2119"), prompt)
            assertTrue("MUST-1: name the file that registers it" in prompt, prompt)
            assertTrue("VERDICT: MET | NOT-MET | NEEDS-HUMAN" in prompt, prompt)
            assertEquals("hermes-newest", byJob.getValue("c1")["model"], "blank model → the first mux.models card")
            assertEquals(BoardClaimWorker.MAX_TOKENS, byJob.getValue("c1")["maxTokens"])

            val r1 = rig.ctx.blackboard.get("kanban/claim/c1") as Map<*, *>
            assertEquals(true, r1["ok"]); assertEquals("DONE", r1["decision"]); assertEquals("MET", r1["verdict"])
            val crit = (r1["criteria"] as List<*>).first() as Map<*, *>
            assertEquals("MUST-1", crit["label"]); assertEquals(true, crit["met"]); assertEquals("board/c1", crit["evidence"])
            val r2 = rig.ctx.blackboard.get("kanban/claim/c2") as Map<*, *>
            assertEquals("REVIEW", r2["decision"]); assertTrue((r2["why"] as String).contains("person"), r2.toString())
            assertEquals("kanban-claim", rig.ctx.blackboard.getProvenance("kanban/claim/c1")?.language)
            assertTrue(rig.ctx.blackboard.keys().any { it.startsWith("kanban/rule/claim/claim-c1-r") })

            // the trail: the claimant never moved to DONE from RUNNING — REVIEW sat between
            val commits = rig.ctx.blackboard.keys().filter { it.startsWith("kanban/committed/c1/") }
                .map { rig.ctx.blackboard.get(it) as Map<*, *> }.sortedBy { it["revision"].toString().toLong() }.map { it["col"] }
            assertEquals(listOf("todo", "ready", "running", "review", "done"), commits)
        } finally {
            runBlocking { rig.supervisor.detach("kanban") }
        }
    }

    @Test
    fun aFailingBrainStrikesOutToBlocked() {
        val brain = LcncNodeRunner { node, _ -> mapOf("ok" to false, "error" to "quota exhausted for ${node.params["model"]}", "model" to node.params["model"], "content" to "") }
        val rig = rig("failing", brain)
        try {
            invoke(
                rig,
                mapOf("type" to "submit", "jobId" to "f1", "idempotencyKey" to "s1", "title" to "Doomed"),
                mapOf("type" to "move", "jobId" to "f1", "idempotencyKey" to "m1", "expectedRevision" to 1, "toColumn" to "ready"),
            )
            // strike 1 and 2 hand it back to READY (re-claimed each time); strike 3 parks it in BLOCKED
            val blocked = awaitStatus(rig, "blocked", "f1", timeoutMs = 20_000)
            assertEquals("", blocked.getValue("f1")["owner"], "BLOCKED clears the claimant so a person sees it")
            val r = rig.ctx.blackboard.get("kanban/claim/f1") as Map<*, *>
            assertEquals(false, r["ok"]); assertEquals("RETRY", r["decision"])
            assertEquals("quota exhausted for hermes-newest", r["error"])
            val strikes = rig.ctx.blackboard.keys().filter { it.startsWith("kanban/rule/reaper/judge-f1-") }
            assertEquals(3, strikes.size, "three judge strikes: $strikes")
            val commits = rig.ctx.blackboard.keys().filter { it.startsWith("kanban/committed/f1/") }
                .map { rig.ctx.blackboard.get(it) as Map<*, *> }.sortedBy { it["revision"].toString().toLong() }.map { it["col"] }
            assertEquals(listOf("todo", "ready", "running", "ready", "running", "ready", "running", "blocked"), commits)
        } finally {
            runBlocking { rig.supervisor.detach("kanban") }
        }
    }

    @Test
    fun withoutABrainTheClaimIsNotTakenAndTheCardStaysReady() {
        val rig = rig("brainless", brain = null)
        try {
            invoke(
                rig,
                mapOf("type" to "submit", "jobId" to "n1", "idempotencyKey" to "s1", "title" to "Nobody home"),
                mapOf("type" to "move", "jobId" to "n1", "idempotencyKey" to "m1", "expectedRevision" to 1, "toColumn" to "ready"),
            )
            val deadline = System.currentTimeMillis() + 3_000
            while (System.currentTimeMillis() < deadline && rig.ctx.blackboard.get("kanban/claim/n1") == null) runBlocking { delay(50) }
            val r = rig.ctx.blackboard.get("kanban/claim/n1") as Map<*, *>
            assertEquals(false, r["ok"])
            assertTrue((r["error"] as String).startsWith("no brain"), r.toString())
            runBlocking { delay(300) }
            val card = items(rig).getValue("n1")
            assertEquals("ready", card["status"], "no brain, no claim: the card waits for one")
            assertEquals("", card["owner"])
        } finally {
            runBlocking { rig.supervisor.detach("kanban") }
        }
    }
}
