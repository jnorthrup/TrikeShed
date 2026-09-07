package borg.trikeshed.kanban.module

import borg.trikeshed.couch.CouchChangesFactElement
import borg.trikeshed.couch.CouchDatabase
import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.cursor.BlackboardContext
import borg.trikeshed.dag.ReteNetwork
import borg.trikeshed.dag.ReteProductionRegistry
import borg.trikeshed.forge.server.JvmProjectCorpus
import borg.trikeshed.forge.server.ProjectDbRegistry
import borg.trikeshed.forge.server.ProjectScopes
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.job.CasStore
import borg.trikeshed.lcnc.InMemoryPromptReads
import borg.trikeshed.lcnc.LcncNodeRunner
import borg.trikeshed.lcnc.LcncPromptSeeds
import borg.trikeshed.lcnc.LcncRunFacts
import borg.trikeshed.lcnc.LcncStaleMarker
import borg.trikeshed.lcnc.ProjectNodes
import borg.trikeshed.lcnc.PromptNodes
import borg.trikeshed.litebike.JvmKanbanServer
import borg.trikeshed.memory.CouchIndexBridge
import borg.trikeshed.memory.MemoryIndexLayer
import borg.trikeshed.memory.MemoryStore
import borg.trikeshed.module.ModuleContext
import borg.trikeshed.module.ModuleRouteRegistry
import borg.trikeshed.module.ModuleSupervisor
import borg.trikeshed.parse.json.JsonSupport
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.util.oroboros.CouchAttachmentGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The corpus story, end to end on the module rig (Forge genesis, Cut S): Corpus digest runs over a
 * mounted project, its consumed facts land in the project's partition, an edited file marks the run
 * stale, a second edit raises the count under one key, rebuild re-executes the same program version
 * over the moved inputs, names the old receipt, and retires the marker and the old facts.
 */
class CorpusStaleRebuildRouteTest {

    private fun tempDir(name: String): File = File(System.getProperty("java.io.tmpdir"), "corpus-stale-$name-${System.nanoTime()}").apply { mkdirs() }

    private class Rig(val server: JvmKanbanServer, val ctx: ModuleContext, val supervisor: ModuleSupervisor, val scopes: ProjectScopes, val project: String, val tendon: CouchChangesFactElement)

    private fun rig(name: String): Rig {
        val cas = CasStore.inMemory()
        val couchStore = CouchStoreFactory.casBacked(cas)
        val routes = ModuleRouteRegistry()
        val home = tempDir("$name-home")
        val ctx = ModuleContext(
            couchDb = CouchDatabase("corpus-stale-$name", couchStore, cas),
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
            programLoader = { n -> borg.trikeshed.lcnc.LcncPresets.all()[n]?.let { borg.trikeshed.lcnc.LcncProgramConfix.fromJson(n, it) } },
        )
        // The mounted project, its tendon into the module's one network, and the runners the preset needs.
        val gateway = CouchAttachmentGateway(couchStore, cas)
        val registry = ProjectDbRegistry("corpus-stale-$name")
        var tendon: CouchChangesFactElement? = null
        registry.onMount = { pdb -> tendon = CouchChangesFactElement(pdb.db, ctx.rete, admit = { true }) }
        val scopes = ProjectScopes(JvmFileOperations(), gateway, CouchIndexBridge(gateway, MemoryIndexLayer(MemoryStore(cas, couchStore))), cas, null, projectDbs = registry, ledgerFile = File(home, "mount-ledger.tsv"), filesRoot = File(home, "files"))
        val folder = tempDir("$name-notes")
        File(folder, "a.md").writeText("# Alpha\n\nthe first note")
        File(folder, "b.md").writeText("# Beta\n\nthe second note")
        File(folder, "c.txt").writeText("not markdown")
        val scope = runBlocking { scopes.mount(folder.absolutePath) }
        runBlocking { tendon!!.drainFrames() }
        ctx.lcncRunners.putAll(ProjectNodes.registry(JvmProjectCorpus(registry, scopes)))
        val prompts = InMemoryPromptReads { 1L }.apply { LcncPromptSeeds.all().forEach { put(it) } }
        ctx.lcncRunners.putAll(PromptNodes.registry(prompts))
        // The pure and council runners the daemon registers, as the corpus execution test fakes them.
        ctx.lcncRunners.putAll(borg.trikeshed.lcnc.PureNodes.registry { System.currentTimeMillis() })
        ctx.lcncRunners.putAll(mapOf(
            "note" to LcncNodeRunner { _, _ -> emptyMap() },
            "display" to LcncNodeRunner { _, inputs -> mapOf("shown" to inputs["x"]) },
            "text.fold" to LcncNodeRunner { node, inputs ->
                val brief = (inputs["brief"] ?: inputs["brief?"])?.toString().orEmpty()
                val parts = (inputs["parts"] as? List<*>)?.map { it.toString() } ?: listOfNotNull(inputs["parts"]?.toString())
                mapOf("text" to (listOf(brief) + parts).joinToString(node.params["separator"] ?: "\n\n"))
            },
            "prompt.chat" to LcncNodeRunner { _, inputs ->
                val prompt = (inputs["prompt"] ?: inputs["prompt?"]).toString()
                mapOf("content" to "summary of <" + prompt.substringAfterLast("\n\n") + ">", "model" to "stub", "ok" to true, "error" to "", "cached" to false)
            },
        ))
        val server = JvmKanbanServer(moduleRoutes = routes)
        val supervisor = ModuleSupervisor(ctx)
        runBlocking { supervisor.attach(KanbanModule()) }
        return Rig(server, ctx, supervisor, scopes, scope.name, tendon!!)
    }

    private fun post(server: JvmKanbanServer, path: String, body: String): JvmKanbanServer.HttpResponse = runBlocking {
        server.routeHttp("POST $path HTTP/1.1\r\nHost: t\r\nContent-Type: application/json\r\n\r\n$body".toByteArray(StandardCharsets.UTF_8))
    }

    private fun get(server: JvmKanbanServer, path: String): JvmKanbanServer.HttpResponse = runBlocking {
        server.routeHttp("GET $path HTTP/1.1\r\nHost: t\r\n\r\n".toByteArray(StandardCharsets.UTF_8))
    }

    @Suppress("UNCHECKED_CAST")
    private fun json(resp: JvmKanbanServer.HttpResponse): Map<String, Any?> = JsonSupport.parse(resp.body) as Map<String, Any?>

    private fun consumedFacts(rig: Rig) = rig.ctx.rete.workingMemory.query(BlackboardContext(rig.project), "kind" to LcncRunFacts.KIND)

    private fun awaitMarker(rig: Rig, runId: String, count: Int): Map<*, *> {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val m = rig.ctx.blackboard.get(LcncStaleMarker.key(runId)) as? Map<*, *>
            if (m != null && (m["count"] as? Number)?.toInt() == count) return m
            runBlocking { delay(100) }
        }
        error("no stale marker with count $count for $runId; have ${rig.ctx.blackboard.get(LcncStaleMarker.key(runId))}")
    }

    @Test
    fun anEditedDocumentMarksTheRunStaleAndRebuildRefreshesIt() {
        val rig = rig("flow")
        try {
            val first = post(rig.server, "/api/lcnc/run", """{"program":"preset-corpus","inputs":{"project":"${rig.project}"}}""")
            assertEquals(200, first.status, first.body)
            val receipt = json(first)
            val runId = receipt["runId"] as String
            val consumed = receipt["consumed"] as List<Map<*, *>>
            assertEquals(listOf("project-index", "project", "prompt", "project"), consumed.map { it["kind"] }, consumed.toString())
            assertEquals(3, consumedFacts(rig).size, "the listing and both documents are facts in the project's partition")
            assertNull(rig.ctx.blackboard.get(LcncStaleMarker.key(runId)), "nothing moved yet")

            // a.md edited through the project store: within the tick, the marker names it and only it.
            val aBefore = consumed.first { it["id"] == "${rig.project}/a.md" }["cid"] as String
            rig.scopes.uploadPut(rig.project, "a.md", "# Alpha\n\nthe first note, revised".encodeToByteArray())
            runBlocking { rig.tendon.drainFrames() }
            val one = awaitMarker(rig, runId, 1)
            val input = (one["inputs"] as List<Map<*, *>>).single()
            assertEquals("a.md", input["id"]); assertEquals(aBefore, input["oldCid"]); assertNotEquals(aBefore, input["newCid"]); assertEquals(false, input["deleted"])
            assertEquals(receipt["receiptCid"], one["receiptCid"]); assertEquals("lcnc/program/preset-corpus", one["programKey"])
            assertEquals(LcncStaleMarker.LANGUAGE, rig.ctx.blackboard.getProvenance(LcncStaleMarker.key(runId))?.language)

            // b.md too: the count is 2, the key is still one.
            rig.scopes.uploadPut(rig.project, "b.md", "# Beta\n\nchanged as well".encodeToByteArray())
            runBlocking { rig.tendon.drainFrames() }
            val two = awaitMarker(rig, runId, 2)
            assertEquals(setOf("a.md", "b.md"), (two["inputs"] as List<Map<*, *>>).map { it["id"] }.toSet())
            assertEquals(1, rig.ctx.blackboard.keys().count { it.startsWith(LcncStaleMarker.PREFIX) })

            // Rebuild: the same program version, the same inputs, over the documents as they are now.
            val rebuilt = post(rig.server, "/api/lcnc/run/rebuild", """{"runId":"$runId"}""")
            assertEquals(200, rebuilt.status, rebuilt.body)
            val fresh = json(rebuilt)
            assertEquals(true, fresh["ok"]); assertEquals(receipt["receiptCid"], fresh["rebuildOf"]); assertEquals(runId, fresh["rebuildOfRunId"])
            assertEquals(receipt["programCid"], fresh["programCid"]); assertEquals(receipt["inputs"], fresh["inputs"])
            assertNotEquals(receipt["inputFingerprint"], fresh["inputFingerprint"], "the inputs moved, so the fingerprint moved")
            val freshA = (fresh["consumed"] as List<Map<*, *>>).first { it["id"] == "${rig.project}/a.md" }["cid"]
            assertEquals((two["inputs"] as List<Map<*, *>>).first { it["id"] == "a.md" }["newCid"], freshA, "the rebuild read the new a.md")
            assertNull(rig.ctx.blackboard.get(LcncStaleMarker.key(runId)), "the old marker is gone")
            val facts = consumedFacts(rig)
            assertEquals(3, facts.size, "only the new run's facts remain: $facts")
            assertTrue(facts.all { it.fields["runId"] == fresh["runId"] })
            assertEquals(2, rig.ctx.blackboard.keys().count { it.startsWith("lcnc/run/") })

            assertEquals(404, post(rig.server, "/api/lcnc/run/rebuild", """{"runId":"nope"}""").status)
            assertEquals(400, post(rig.server, "/api/lcnc/run/rebuild", "not json").status)
        } finally {
            runBlocking { rig.supervisor.detach("kanban") }
        }
    }

    /**
     * The run head over the same story (AutoTools, Cut B): what a page's `lcnc-run` block is told
     * at each step. This is the one lamp the lighter rig cannot reach — Stale needs a mounted
     * project, its tendon and the production sink's marker.
     */
    @Test
    fun theRunHeadRouteBurnsTheSameFourLampsAPageShows() {
        val rig = rig("head")
        val inputs = """{"project":"${rig.project}"}"""
        fun head(show: String? = null): Map<String, Any?> {
            val path = "/api/lcnc/runs?program=preset-corpus&inputs=" + URLEncoder.encode(inputs, "UTF-8") +
                (show?.let { "&show=" + URLEncoder.encode(it, "UTF-8") } ?: "")
            val response = get(rig.server, path)
            assertEquals(200, response.status, response.body)
            return json(response)
        }
        fun awaitLamp(lamp: String): Map<String, Any?> {
            val deadline = System.currentTimeMillis() + 10_000
            var last: Map<String, Any?> = emptyMap()
            while (System.currentTimeMillis() < deadline) {
                last = head()
                if (last["lamp"] == lamp) return last
                runBlocking { delay(100) }
            }
            error("the head never read $lamp; last was $last")
        }
        try {
            val cold = head()
            assertEquals("never_built", cold["lamp"])
            assertEquals("Never built", cold["word"])
            assertNull(cold["runId"])

            val first = json(post(rig.server, "/api/lcnc/run", """{"program":"preset-corpus","inputs":$inputs}"""))
            val runId = first["runId"] as String
            val warm = head(show = "n-show")
            assertEquals("completed", warm["lamp"])
            assertEquals(first["receiptCid"], warm["receiptCid"])
            assertEquals(runId, warm["runId"])
            // The display sink's output key differs by rig (the daemon's returns "x", this stub
            // "shown"), so the assertion is that the node's map is there, never on a fixed key.
            assertTrue((warm["shown"] as Map<*, *>).values.first().toString().contains("a.md"), warm["shown"].toString())
            assertEquals(false, warm["shownMissing"])
            assertNull(warm["stale"])

            // a.md edited through the upload lane: within the tick the block reads Stale and names it.
            rig.scopes.uploadPut(rig.project, "a.md", "# Alpha\n\nthe first note, revised".encodeToByteArray())
            runBlocking { rig.tendon.drainFrames() }
            val stale = awaitLamp("stale")
            assertEquals("Stale", stale["word"])
            assertEquals(listOf("a.md"), stale["moved"])
            assertTrue(stale["reason"].toString().contains("a.md"), stale["reason"].toString())
            assertEquals(1, ((stale["stale"] as Map<*, *>)["count"] as Number).toInt())
            assertEquals(first["receiptCid"], stale["receiptCid"], "the artifact is still the last good build")

            // Rebuild names the run the block was showing; the head flips back with the lineage.
            val rebuilt = post(rig.server, "/api/lcnc/run/rebuild", """{"runId":"${stale["runId"]}"}""")
            assertEquals(200, rebuilt.status, rebuilt.body)
            val fresh = json(rebuilt)
            val after = head(show = "n-show")
            assertEquals("completed", after["lamp"])
            assertEquals(fresh["receiptCid"], after["receiptCid"])
            assertEquals(first["receiptCid"], after["rebuildOf"])
            assertEquals(runId, after["rebuildOfRunId"])
            assertNull(after["stale"])
            assertTrue((after["shown"] as Map<*, *>).values.first().toString().contains("revised"),
                "the rebuilt digest read the new a.md: ${after["shown"]}")
        } finally {
            runBlocking { rig.supervisor.detach("kanban") }
        }
    }
}
