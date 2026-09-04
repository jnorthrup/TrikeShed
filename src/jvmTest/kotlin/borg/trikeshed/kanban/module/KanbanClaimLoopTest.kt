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
    fun readyCardsAreClaimedWorkedAndParkedInReview() {
        val prompts = CopyOnWriteArrayList<Map<String, String>>()
        val brain = LcncNodeRunner { node, _ ->
            prompts.add(node.params)
            mapOf("ok" to true, "content" to "Next: split '${node.params["prompt"]}' into a test first.", "model" to node.params["model"], "cached" to false)
        }
        val rig = rig("loop", brain)
        try {
            invoke(
                rig,
                mapOf("type" to "submit", "jobId" to "c1", "idempotencyKey" to "s1", "title" to "Wire the reaper"),
                mapOf("type" to "submit", "jobId" to "c2", "idempotencyKey" to "s2", "title" to "Prove it live"),
                mapOf("type" to "move", "jobId" to "c1", "idempotencyKey" to "m1", "expectedRevision" to 1, "toColumn" to "ready"),
                mapOf("type" to "move", "jobId" to "c2", "idempotencyKey" to "m2", "expectedRevision" to 1, "toColumn" to "ready"),
            )
            val reviewed = awaitStatus(rig, "review", "c1", "c2")
            assertEquals("claim:brain", reviewed.getValue("c1")["owner"], "the claim owns the card, and REVIEW keeps the owner")
            assertEquals("claim:brain", reviewed.getValue("c2")["owner"])

            // the brain was asked ONCE per card, with the card as the brief, the newest model, 256 tokens
            assertEquals(2, prompts.size, "one brain call per claim: $prompts")
            val byJob = prompts.associateBy { it["prompt"]!!.substringAfter("Card ").substringBefore(":") }
            assertEquals(BoardClaimWorker.brief("c1", "Wire the reaper"), byJob.getValue("c1")["prompt"])
            assertEquals("hermes-newest", byJob.getValue("c1")["model"], "blank model → the first mux.models card")
            assertEquals("256", byJob.getValue("c1")["maxTokens"])

            // the receipt a human reads in REVIEW
            val r1 = rig.ctx.blackboard.get("kanban/claim/c1") as Map<*, *>
            assertEquals(true, r1["ok"])
            assertEquals("claim:brain", r1["owner"])
            assertEquals("hermes-newest", r1["model"])
            assertTrue((r1["content"] as String).startsWith("Next:"), "$r1")
            assertEquals("kanban-claim", rig.ctx.blackboard.getProvenance("kanban/claim/c1")?.language)
            // the rule receipts are on the board too
            assertTrue(rig.ctx.blackboard.keys().any { it.startsWith("kanban/rule/claim/claim-c1-r") })

            // nothing moved itself to DONE, and the claimant may not: the guard from the review column
            val rev = (reviewed.getValue("c1")["revision"] as Number).toLong()
            val self = invoke(rig, mapOf("type" to "move", "jobId" to "c1", "idempotencyKey" to "d-self", "expectedRevision" to rev, "toColumn" to "done", "actor" to "claim:brain"))
            assertEquals("rejected", self[0]["verdict"])
            assertTrue((self[0]["reason"] as String).contains("second pair of eyes"), self[0].toString())
            val jim = invoke(rig, mapOf("type" to "move", "jobId" to "c1", "idempotencyKey" to "d-jim", "expectedRevision" to rev, "toColumn" to "done", "actor" to "jim"))
            assertEquals("committed", jim[0]["verdict"], jim[0].toString())
            assertEquals("done", items(rig).getValue("c1")["status"])
        } finally {
            runBlocking { rig.supervisor.detach("kanban") }
        }
    }

    @Test
    fun aFailingBrainStillParksTheCardInReviewWithTheErrorOnTheReceipt() {
        val brain = LcncNodeRunner { node, _ -> mapOf("ok" to false, "error" to "quota exhausted for ${node.params["model"]}", "model" to node.params["model"], "content" to "") }
        val rig = rig("failing", brain)
        try {
            invoke(
                rig,
                mapOf("type" to "submit", "jobId" to "f1", "idempotencyKey" to "s1", "title" to "Doomed"),
                mapOf("type" to "move", "jobId" to "f1", "idempotencyKey" to "m1", "expectedRevision" to 1, "toColumn" to "ready"),
            )
            val reviewed = awaitStatus(rig, "review", "f1")
            assertEquals("claim:brain", reviewed.getValue("f1")["owner"])
            val r = rig.ctx.blackboard.get("kanban/claim/f1") as Map<*, *>
            assertEquals(false, r["ok"])
            assertEquals("quota exhausted for hermes-newest", r["error"])
            // and RUNNING → DONE was never an option for it: the card went RUNNING then REVIEW
            val commits = rig.ctx.blackboard.keys().filter { it.startsWith("kanban/committed/f1/") }
                .map { rig.ctx.blackboard.get(it) as Map<*, *> }.sortedBy { it["revision"].toString().toLong() }.map { it["col"] }
            assertEquals(listOf("todo", "ready", "running", "review"), commits)
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
