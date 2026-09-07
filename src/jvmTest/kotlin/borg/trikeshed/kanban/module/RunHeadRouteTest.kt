package borg.trikeshed.kanban.module

import borg.trikeshed.couch.CouchDatabase
import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.dag.ReteNetwork
import borg.trikeshed.dag.ReteProductionRegistry
import borg.trikeshed.docs.RunBlock
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.job.CasStore
import borg.trikeshed.lcnc.LcncConsumedLedger
import borg.trikeshed.lcnc.LcncContracts
import borg.trikeshed.lcnc.LcncNode
import borg.trikeshed.lcnc.LcncNodeRunner
import borg.trikeshed.lcnc.LcncProgram
import borg.trikeshed.lcnc.LcncRunHead
import borg.trikeshed.lcnc.LcncStaleMarker
import borg.trikeshed.lcnc.LcncWire
import borg.trikeshed.lib.toSeries
import borg.trikeshed.litebike.JvmKanbanServer
import borg.trikeshed.module.ModuleContext
import borg.trikeshed.module.ModuleRouteRegistry
import borg.trikeshed.module.ModuleSupervisor
import borg.trikeshed.parse.json.JsonSupport
import borg.trikeshed.parse.json.ValueBudget
import borg.trikeshed.util.oroboros.CouchAttachmentGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The run head route (AutoTools, Cut B): `GET /api/lcnc/runs?program=&inputs=&show=` answers which
 * recorded run IS the build of a page's block, and what lamp it burns.
 *
 * The rig is [LcncRunProgramRouteTest]'s: an in-memory CAS, an empty blackboard, a program loader
 * with explicit fixtures in front of the presets, and — the gift that makes case six free — a
 * FROZEN clock, so two runs share `startedAtMs` and only the board's commit sequence orders them.
 *
 * What this suite cannot reach is Stale, which needs a mounted project and its tendon;
 * [CorpusStaleRebuildRouteTest] carries that case.
 */
class RunHeadRouteTest {

    private fun tempDir(name: String): File =
        File(System.getProperty("java.io.tmpdir"), "run-head-$name-${System.nanoTime()}").apply { mkdirs() }

    private fun echoProgram(name: String, suffix: String = "") = LcncProgram(
        name,
        listOf(
            LcncNode("a", LcncContracts.SCOPE_IN, params = mapOf("name" to "text")),
            LcncNode("b", "test.echo", params = mapOf("suffix" to suffix)),
            LcncNode("c", LcncContracts.SCOPE_OUT, params = mapOf("name" to "result")),
        ).toSeries(),
        listOf(LcncWire("a", "value", "b", "x"), LcncWire("b", "y", "c", "value")).toSeries(),
    )

    private class Rig(
        val server: JvmKanbanServer,
        val ctx: ModuleContext,
        val supervisor: ModuleSupervisor,
        val programs: MutableMap<String, LcncProgram>,
    )

    private fun rig(name: String, failing: Boolean = false): Rig {
        val cas = CasStore.inMemory()
        val couchStore = CouchStoreFactory.casBacked(cas)
        val routes = ModuleRouteRegistry()
        val programs = linkedMapOf<String, LcncProgram>("echo" to echoProgram("echo"))
        val ctx = ModuleContext(
            couchDb = CouchDatabase("run-head-$name", couchStore, cas),
            rete = ReteNetwork(),
            productions = ReteProductionRegistry(),
            beliefBag = null,
            turnReview = null,
            blackboard = ConfixBlackboard.empty(),
            casStore = cas,
            attachments = CouchAttachmentGateway(couchStore, cas),
            routes = routes,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            clock = { 1234L },
            stateDir = tempDir("$name-home"),
            programLoader = { n ->
                programs[n] ?: borg.trikeshed.lcnc.LcncPresets.all()[n]
                    ?.let { borg.trikeshed.lcnc.LcncProgramConfix.fromJson(n, it) }
            },
        )
        ctx.lcncRunners["test.echo"] = LcncNodeRunner { node, inputs ->
            if (failing) error("the provider refused")
            mapOf("y" to inputs["x"].toString().uppercase() + node.params["suffix"].orEmpty())
        }
        val server = JvmKanbanServer(moduleRoutes = routes)
        val supervisor = ModuleSupervisor(ctx)
        runBlocking { supervisor.attach(KanbanModule()) }
        return Rig(server, ctx, supervisor, programs)
    }

    private fun post(server: JvmKanbanServer, path: String, body: String) = runBlocking {
        server.routeHttp("POST $path HTTP/1.1\r\nHost: t\r\nContent-Type: application/json\r\n\r\n$body".toByteArray(StandardCharsets.UTF_8))
    }

    private fun get(server: JvmKanbanServer, path: String) = runBlocking {
        server.routeHttp("GET $path HTTP/1.1\r\nHost: t\r\n\r\n".toByteArray(StandardCharsets.UTF_8))
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun head(rig: Rig, program: String = "echo", inputs: String? = """{"text":"hi","n":3}""", show: String? = null) =
        get(rig.server, "/api/lcnc/runs?program=" + enc(program) +
            (inputs?.let { "&inputs=" + enc(it) } ?: "") + (show?.let { "&show=" + enc(it) } ?: ""))

    @Suppress("UNCHECKED_CAST")
    private fun json(resp: JvmKanbanServer.HttpResponse): Map<String, Any?> = JsonSupport.parse(resp.body) as Map<String, Any?>

    // ── the refusals ────────────────────────────────────────────────────────

    @Test
    fun theRouteRefusesWhatItCannotAnswer() {
        val rig = rig("refusals")
        try {
            // A block naming a program this daemon does not hold must not read Never built with a
            // Build button that would 404: it says the program is absent.
            val ghost = head(rig, program = "ghost")
            assertEquals(404, ghost.status, ghost.body)
            assertEquals("no_such_program", json(ghost)["error"])
            assertEquals("ghost", json(ghost)["program"])

            assertEquals(400, get(rig.server, "/api/lcnc/runs").status)
            assertEquals("program_required", json(get(rig.server, "/api/lcnc/runs"))["error"])

            val listed = get(rig.server, "/api/lcnc/runs?program=echo&inputs=%5B%5D")
            assertEquals(400, listed.status, listed.body)
            assertEquals("inputs_must_be_object", json(listed)["error"])

            val garbage = get(rig.server, "/api/lcnc/runs?program=echo&inputs=" + enc("{not json"))
            assertEquals(400, garbage.status, garbage.body)
            assertEquals("bad_inputs", json(garbage)["error"])

            val wrongVerb = post(rig.server, "/api/lcnc/runs", "{}")
            assertEquals(405, wrongVerb.status, wrongVerb.body)
        } finally { runBlocking { rig.supervisor.detach("kanban") } }
    }

    // ── never built, then built ─────────────────────────────────────────────

    @Test
    fun aTargetWithNoRunIsNeverBuiltAndOneRunMakesItCompleted() {
        val rig = rig("built")
        try {
            val before = head(rig)
            assertEquals(200, before.status, before.body)
            val cold = json(before)
            assertEquals("never_built", cold["lamp"])
            assertEquals("Never built", cold["word"])
            assertNull(cold["runId"])
            assertNull(cold["receiptCid"])
            assertEquals("""{"n":3, "text":"hi"}""", cold["inputsCanonical"])
            // The version is derived from the loader, not read off a board entry, so it is the
            // version the next Build would actually run.
            val entry = rig.ctx.blackboard.get("lcnc/program/echo") as? Map<*, *>
            if (entry != null) assertEquals(entry["programCid"], cold["programCid"])

            val run = post(rig.server, "/api/lcnc/run", """{"program":"echo","inputs":{"text":"hi","n":3}}""")
            assertEquals(200, run.status, run.body)
            val receipt = json(run)

            val warm = json(head(rig, show = "b"))
            assertEquals("completed", warm["lamp"])
            assertEquals("Completed", warm["word"])
            assertEquals(receipt["runId"], warm["runId"])
            assertEquals(receipt["receiptCid"], warm["receiptCid"])
            assertEquals(receipt["programCid"], warm["programCid"])
            assertEquals(mapOf("y" to "HI"), warm["shown"], "the named node's recorded output rides the frame: $warm")
            assertEquals(false, warm["shownMissing"])
            assertEquals(1, (warm["runs"] as Number).toInt())
            assertEquals(0, (warm["activeRuns"] as Number).toInt())
            assertNull(warm["stale"])

            // No show: the run's returns. A node that never ran is said, not guessed at.
            assertEquals(mapOf("result" to "HI"), json(head(rig))["shown"])
            val ghostNode = json(head(rig, show = "no-such-node"))
            assertEquals(true, ghostNode["shownMissing"])
            assertNull(ghostNode["shown"])
        } finally { runBlocking { rig.supervisor.detach("kanban") } }
    }

    // ── the identity ────────────────────────────────────────────────────────

    @Test
    fun theTargetIsTheCanonicalRequestNotTheTextOfIt() {
        val rig = rig("identity")
        try {
            post(rig.server, "/api/lcnc/run", """{"program":"echo","inputs":{"text":"hi","n":3}}""")
            // A different key order and 3 vs 3.0 name the same target: this is the canonicalisation
            // proof, and the reason a page may write its block however it likes.
            val reordered = json(head(rig, inputs = """{"n":3.0,"text":"hi"}"""))
            assertEquals("completed", reordered["lamp"])
            assertEquals("""{"n":3, "text":"hi"}""", reordered["inputsCanonical"])
            // Different inputs are a different target, with no cross-talk.
            val other = json(head(rig, inputs = """{"text":"bye","n":3}"""))
            assertEquals("never_built", other["lamp"])
            assertNull(other["runId"])
            assertNotEquals(reordered["inputsKey"], other["inputsKey"])
        } finally { runBlocking { rig.supervisor.detach("kanban") } }
    }

    @Test
    fun aRepublishedProgramHasNotBeenBuiltYet() {
        val rig = rig("version")
        try {
            val first = json(post(rig.server, "/api/lcnc/run", """{"program":"echo","inputs":{"text":"hi","n":3}}"""))
            assertEquals("completed", json(head(rig))["lamp"])
            // The author edits the program. The old receipt describes a version that is no longer
            // what Build would run, so the block is Never built again — the plan's invariant 2.
            rig.programs["echo"] = echoProgram("echo", suffix = "!")
            val after = json(head(rig))
            assertEquals("never_built", after["lamp"])
            assertNull(after["receiptCid"])
            assertNotEquals(first["programCid"], after["programCid"])
            // And building the new version makes it the head, without disturbing the old receipt.
            val second = json(post(rig.server, "/api/lcnc/run", """{"program":"echo","inputs":{"text":"hi","n":3}}"""))
            val now = json(head(rig, show = "b"))
            assertEquals("completed", now["lamp"])
            assertEquals(second["receiptCid"], now["receiptCid"])
            assertEquals(mapOf("y" to "HI!"), now["shown"])
            assertEquals(2, rig.ctx.blackboard.keys().count { it.startsWith("lcnc/run/") })
        } finally { runBlocking { rig.supervisor.detach("kanban") } }
    }

    @Test
    fun underAFrozenClockTheBoardSequenceDecidesWhichRunIsTheHead() {
        val rig = rig("sequence")
        try {
            val first = json(post(rig.server, "/api/lcnc/run", """{"program":"echo","inputs":{"text":"hi","n":3}}"""))
            val second = json(post(rig.server, "/api/lcnc/run", """{"program":"echo","inputs":{"text":"hi","n":3}}"""))
            assertEquals(first["startedAtMs"], second["startedAtMs"], "the rig's clock is frozen; only sequence orders these")
            assertTrue((second["sequence"] as Number).toLong() > (first["sequence"] as Number).toLong())
            val warm = json(head(rig))
            assertEquals(second["runId"], warm["runId"], "the newest run is the head: $warm")
            assertEquals(second["receiptCid"], warm["receiptCid"])
            assertEquals(2, (warm["runs"] as Number).toInt())
        } finally { runBlocking { rig.supervisor.detach("kanban") } }
    }

    // ── the marker that outgrew the frame ───────────────────────────────────

    /**
     * The finding's own breaking input, end to end: a completed run whose `lcnc/stale/<runId>`
     * names hundreds of moved documents. `LcncStaleMarker.merge` folds one row per moved document
     * and never expires one, so this marker is exactly what the module's production sink writes
     * over a long-lived run — and projected whole it used to trip the route's `ValueBudget` and
     * answer 413, which a page reads as "Unavailable" with NO button, permanently, on the one
     * target the whole cut exists to rebuild.
     */
    @Test
    fun aStaleMarkerNamingHundredsOfDocumentsIsAnsweredNotRefused() {
        val rig = rig("fat-marker")
        try {
            val receipt = json(post(rig.server, "/api/lcnc/run", """{"program":"echo","inputs":{"text":"hi","n":3}}"""))
            val runId = receipt["runId"].toString()
            var marker: Any? = null
            for (i in 1..700) {
                marker = LcncStaleMarker.merge(
                    marker,
                    mapOf(
                        "runId" to runId, "receiptKey" to (LcncRunHead.RUN_PREFIX + runId),
                        "receiptCid" to receipt["receiptCid"].toString(),
                        "programKey" to "lcnc/program/echo", "programCid" to receipt["programCid"].toString(),
                        "project" to "genesis-notes", "kind" to LcncConsumedLedger.PROJECT, "id" to "note-$i.md",
                        "oldCid" to "sha256:" + "c".repeat(64), "newCid" to "sha256:" + i.toString().padStart(64, '0'),
                        "sequence" to "$i", "deleted" to "false",
                    ),
                    atMs = 9000L,
                )
            }
            rig.ctx.blackboard.put(LcncStaleMarker.key(runId), marker, "lcnc-stale")

            val answer = head(rig, show = "b")
            assertEquals(200, answer.status, answer.body.take(400))
            val body = json(answer)
            assertEquals("stale", body["lamp"], body.toString())
            assertEquals(700, ((body["stale"] as Map<*, *>)["count"] as Number).toInt(), "how many moved is never capped")
            assertEquals(LcncRunHead.CONSUMED_LIMIT, ((body["stale"] as Map<*, *>)["inputs"] as List<*>).size)
            assertEquals(true, body["staleTruncated"])
            assertEquals(LcncRunHead.CONSUMED_LIMIT, (body["moved"] as List<*>).size)
            assertEquals(true, body["movedTruncated"])
            assertNull(ValueBudget().violation(body), "the daemon's own bytes must pass its own preflight")

            // And the page's reader gets a Stale frame with exactly one Rebuild to press.
            val state = RunBlock.state(JsonSupport.parse(answer.body))!!
            assertEquals(LcncRunHead.Lamp.STALE, state.lamp, state.reason)
            assertFalse(state.refused, state.reason)
            val spec = RunBlock.parse(0, """{"program":"echo","inputs":{"text":"hi","n":3},"show":"b"}""") as RunBlock.Spec
            val html = RunBlock.frameHtml(spec, state)
            assertEquals(1, Regex("<button").findAll(html).count(), html)
            assertTrue(html.contains("data-run-rebuild=\"0\">Rebuild<"), html)
            assertTrue(html.contains("ds-run-lamp-stale"), html)
        } finally { runBlocking { rig.supervisor.detach("kanban") } }
    }

    // ── failure ─────────────────────────────────────────────────────────────

    @Test
    fun aFailedRunBurnsFailedAndTheLastGoodArtifactStillPaints() {
        val rig = rig("failed")
        try {
            val good = json(post(rig.server, "/api/lcnc/run", """{"program":"echo","inputs":{"text":"hi","n":3}}"""))
            // The same target, now with a node that throws.
            rig.ctx.lcncRunners["test.echo"] = LcncNodeRunner { _, _ -> error("the provider refused") }
            val bad = post(rig.server, "/api/lcnc/run", """{"program":"echo","inputs":{"text":"hi","n":3}}""")
            assertEquals(400, bad.status, bad.body)
            assertEquals("failed", json(bad)["status"])

            val verdict = json(head(rig, show = "b"))
            assertEquals("failed", verdict["lamp"])
            assertEquals("Failed", verdict["word"])
            assertTrue(verdict["reason"].toString().contains("execution"), verdict["reason"].toString())
            assertTrue(verdict["reason"].toString().contains("provider refused"), verdict["reason"].toString())
            // What make does with the old object file: the artifact is still the last completed run.
            assertEquals(good["receiptCid"], verdict["receiptCid"])
            assertEquals(good["runId"], verdict["runId"], "Rebuild names the artifact, not the failure")
            assertEquals(json(bad)["runId"], verdict["latestRunId"])
            assertEquals(mapOf("y" to "HI"), verdict["shown"])

            // THE ENVELOPE. This is an ANSWER: it carries ok and a lamp, the run's own message rides
            // as `runError`, and the word `error` — which the route's REFUSALS own — is absent.
            assertEquals(true, verdict["ok"])
            assertFalse(verdict.containsKey("error"), "a 200 body must not speak the refusal's vocabulary: $verdict")
            assertTrue(verdict["runError"].toString().contains("provider refused"), verdict.toString())
            assertEquals("failed", verdict["status"])
            assertEquals("execution", verdict["phase"])

            // And the bytes the daemon actually writes, read by the page's own reader: a failed
            // target keeps its Build button. Without it a run that broke — or one a restart stamped
            // `interrupted` — would be unbuildable from the page forever.
            val state = RunBlock.state(JsonSupport.parse(head(rig, show = "b").body))!!
            assertEquals(LcncRunHead.Lamp.FAILED, state.lamp, verdict.toString())
            assertFalse(state.refused, state.reason)
            val spec = RunBlock.parse(0, """{"program":"echo","inputs":{"text":"hi","n":3},"show":"b"}""") as RunBlock.Spec
            val html = RunBlock.frameHtml(spec, state)
            assertEquals(1, Regex("<button").findAll(html).count(), html)
            assertTrue(html.contains("data-run-build=\"0\">Build<"), html)
            assertTrue(html.contains("ds-run-lamp-failed"), html)
        } finally { runBlocking { rig.supervisor.detach("kanban") } }
    }
}
