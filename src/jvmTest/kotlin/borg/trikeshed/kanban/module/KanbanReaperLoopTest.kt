package borg.trikeshed.kanban.module

import borg.trikeshed.couch.CouchDatabase
import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.dag.ReteNetwork
import borg.trikeshed.dag.ReteProductionRegistry
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.job.CasStore
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
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The dead tree, reaped. A claim whose worker never comes back (the brain hangs;
 * the daemon restarts) leaves a RUNNING card owned by claim:brain. Each restart
 * fifteen-plus minutes later: the seeded facts carry the owner, the first tick
 * fires the reaper, the card goes READY and is claimed afresh (strikes 1 and 2);
 * the third strike parks it in BLOCKED with the owner cleared. The strike count
 * is the blackboard's `kanban/rule/reaper/<activationId>` receipts — never the worker's memory.
 */
class KanbanReaperLoopTest {

    private fun tempDir(name: String): File =
        File(System.getProperty("java.io.tmpdir"), "kanban-reaper-$name-${System.nanoTime()}").apply { mkdirs() }

    private class Rig(val server: JvmKanbanServer, val ctx: ModuleContext, val supervisor: ModuleSupervisor)

    private fun rig(name: String, clock: () -> Long): Rig {
        val cas = CasStore.inMemory()
        val couchStore = CouchStoreFactory.casBacked(cas)
        val routes = ModuleRouteRegistry()
        val ctx = ModuleContext(
            couchDb = CouchDatabase("kanban-reaper-test-$name", couchStore, cas),
            rete = ReteNetwork(),
            productions = ReteProductionRegistry(),
            beliefBag = null,
            turnReview = null,
            blackboard = ConfixBlackboard.empty(),
            casStore = cas,
            attachments = CouchAttachmentGateway(couchStore, cas),
            routes = routes,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            clock = clock,
            stateDir = tempDir(name),
        )
        // a brain that never answers: the worker is stuck between RUNNING and REVIEW forever
        ctx.lcncRunners["prompt.chat"] = LcncNodeRunner { _, _ -> awaitCancellation() }
        ctx.lcncRunners["mux.models"] = LcncNodeRunner { _, _ -> mapOf("models" to listOf(mapOf("id" to "hung-model"))) }
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

    private fun card(rig: Rig, jobId: String): Map<*, *> =
        arr(json(get(rig.server, "/api/board"))["items"]).map { it as Map<*, *> }.first { it["id"] == jobId }

    /** Poll the rendered board until the card sits in [status] at [revision], or fail with what was seen. */
    private fun await(rig: Rig, jobId: String, status: String, revision: Long, timeoutMs: Long = 10_000): Map<*, *> {
        val deadline = System.currentTimeMillis() + timeoutMs
        var seen = card(rig, jobId)
        while (System.currentTimeMillis() < deadline) {
            seen = card(rig, jobId)
            if (seen["status"] == status && (seen["revision"] as Number).toLong() == revision) return seen
            runBlocking { delay(100) }
        }
        fail("card $jobId never reached '$status' r$revision; saw $seen")
    }

    private fun strikes(rig: Rig, jobId: String): List<Map<*, *>> =
        rig.ctx.blackboard.keys().filter { it.startsWith("kanban/rule/reaper/") }
            .map { rig.ctx.blackboard.get(it) as Map<*, *> }.filter { it["jobId"] == jobId }
            .sortedBy { it["strike"].toString().toInt() }

    @Test
    fun threeStrikesAndTheDeadClaimIsBlockedWithNoOwner() {
        var now = 1_000_000L
        val rig = rig("strikes") { now }
        try {
            invoke(
                rig,
                mapOf("type" to "submit", "jobId" to "z", "idempotencyKey" to "s", "title" to "Never comes back"),
                mapOf("type" to "move", "jobId" to "z", "idempotencyKey" to "m", "expectedRevision" to 1, "toColumn" to "ready"),
            )
            // the claim lands (r3) and the brain hangs: RUNNING, claim:brain, and it stays there
            var seen = await(rig, "z", "running", 3)
            assertEquals("claim:brain", seen["owner"])
            runBlocking { delay(300) }
            assertEquals("running", card(rig, "z")["status"], "a hung brain never reaches REVIEW by itself")
            assertEquals(0, strikes(rig, "z").size, "nothing reaped inside the threshold")

            // restart 1, sixteen minutes later: reaped to READY (r4), claimed afresh (r5), hung again
            runBlocking { rig.supervisor.detach("kanban") }
            now += 16 * 60 * 1000L
            runBlocking { rig.supervisor.attach(KanbanModule()) }
            seen = await(rig, "z", "running", 5)
            assertEquals("claim:brain", seen["owner"])
            assertEquals(listOf("1"), strikes(rig, "z").map { it["strike"] })
            assertEquals("ready", strikes(rig, "z")[0]["toColumn"])
            assertEquals("3", strikes(rig, "z")[0]["expectedRevision"], "the reaper quoted the RUNNING revision it saw")

            // restart 2: strike 2, the same way (r6 READY, r7 RUNNING)
            runBlocking { rig.supervisor.detach("kanban") }
            now += 16 * 60 * 1000L
            runBlocking { rig.supervisor.attach(KanbanModule()) }
            seen = await(rig, "z", "running", 7)
            assertEquals(listOf("1", "2"), strikes(rig, "z").map { it["strike"] })

            // restart 3: the third strike is BLOCKED, owner cleared, and the claim leaves it alone
            runBlocking { rig.supervisor.detach("kanban") }
            now += 16 * 60 * 1000L
            runBlocking { rig.supervisor.attach(KanbanModule()) }
            seen = await(rig, "z", "blocked", 8)
            assertEquals("", seen["owner"], "a human sees it: no claim:* owner any more")
            assertEquals(listOf("1", "2", "3"), strikes(rig, "z").map { it["strike"] })
            runBlocking { delay(300) }
            assertEquals("blocked", card(rig, "z")["status"], "BLOCKED is not READY: nothing claims it")

            // and the trail on the board says exactly that
            val trail = rig.ctx.blackboard.keys().filter { it.startsWith("kanban/committed/z/") }
                .map { rig.ctx.blackboard.get(it) as Map<*, *> }.sortedBy { it["revision"].toString().toLong() }.map { it["col"] }
            assertEquals(listOf("todo", "ready", "running", "ready", "running", "ready", "running", "blocked"), trail)
            assertTrue(rig.ctx.blackboard.get("kanban/claim/z") == null, "the hung brain never wrote a claim receipt")
        } finally {
            runBlocking { rig.supervisor.detach("kanban") }
            rig.ctx.scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }
}
