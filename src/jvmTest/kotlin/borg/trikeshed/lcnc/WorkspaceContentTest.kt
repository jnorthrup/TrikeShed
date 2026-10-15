package borg.trikeshed.lcnc

import borg.trikeshed.couch.*
import borg.trikeshed.job.*
import borg.trikeshed.kanban.module.WorkspaceService
import borg.trikeshed.kanban.module.KanbanModule
import borg.trikeshed.module.ModuleContext
import borg.trikeshed.parse.*
import borg.trikeshed.util.oroboros.CouchAttachmentGateway
import borg.trikeshed.vm.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.*

class WorkspaceContentTest {
    fun context(scope: CoroutineScope, cas: CasStore, stateDir: java.io.File? = null): ModuleContext {
        val base = vmRuntimeTestContext(scope)
        val store = CouchStoreFactory.casBacked(cas)
        return ModuleContext(Couch("content-facets", store, cas), base.rete, base.productions,
            null, null, base.blackboard, cas, CouchAttachmentGateway(store, cas), base.routes,
            scope, base.clock, stateDir ?: base.stateDir)
    }

    fun source(cid: String) = mapOf("kind" to "cas", "cid" to cid)

    @Test fun completedPolyglotCamelRunAndItsInvocationsShareTheReadOnlyFacetSurface(): Unit = runBlocking {
        var writes = 0
        val cas = object : CasStore() {
            override fun put(bytes: ByteArray): ContentId { writes++; return super.put(bytes) }
        }
        val owner = SupervisorJob(coroutineContext[Job])
        val ctx = context(CoroutineScope(coroutineContext + owner), cas,
            Files.createTempDirectory("lcnc-content-facets-").toFile())
        val host = HypervisorVmHost()
        ctx.lcncRunners.putAll(PureNodes.registry { 0L })
        ctx.lcncRunners[SubVmLegos.POLYGLOT] = SubVmLegos.polyglot(host)
        ctx.lcncRunners[SubVmLegos.CAMEL] = SubVmLegos.camel(host)
        ctx.lcncRunners["display"] = LcncNodeRunner { _, inputs -> mapOf("rendered" to inputs["x"]) }
        val module = KanbanModule().open(ctx)
        try {
            val program = LcncWorkspacePresets.polyglot(VmLanguage.JAVASCRIPT)
            val result = ctx.routes.match("/api/lcnc/run")!!.route("POST", "/api/lcnc/run",
                jsonOf(mapOf("name" to program.name, "document" to reifyStrict(LcncProgramConfix.toJson(program)))), null)!!
            assertEquals(200, result.status, result.body)
            val run = reifyMap(result.body)
            assertEquals("completed", run["status"])
            val cid = assertIs<String>(run["receiptCid"])
            val outputs = assertIs<Map<*, *>>(run["outputs"])
            val value = (outputs["function"] as Map<*, *>)["value"]
            assertEquals("human-required", (value as Map<*, *>)["review"])
            assertEquals(value, reify((outputs["route"] as Map<*, *>)["routed"] as String))
            assertTrue(host.ids().none { host.get(it)?.isAlive == true })
            val board = ctx.blackboard.snapshot()
            val before = writes
            val service = WorkspaceService(ctx)
            suspend fun inspect(cid: String, path: List<Any>): Any? {
                val response = service.route("POST", jsonOf(mapOf("source" to source(cid), "path" to path)))
                assertEquals(200, response.status, response.body)
                val confix = reifyMap(response.body)["confix"] as Map<*, *>
                assertEquals(cid, confix["cid"])
                assertEquals(false, confix["valueOmitted"])
                return confix["value"]
            }
            assertEquals(value, inspect(cid, listOf("lcncRun", "outputs", "function", "value")))
            assertEquals((outputs["route"] as Map<*, *>)["receipt"], inspect(cid, listOf("lcncRun", "outputs", "route", "receipt")))
            val ledgerCid = assertIs<String>(run["invocationsCid"])
            val ledger = reifyMap(assertNotNull(cas.get(ContentId(ledgerCid))))
            val entries = assertIs<List<*>>(ledger["entries"])
            val position = entries.indexOfFirst { (it as Map<*, *>)["nodeId"] == "function" }
            assertTrue(position >= 0)
            assertEquals(entries[position], inspect(ledgerCid, listOf("entries", position)))
            assertEquals(before, writes)
            assertEquals(board, ctx.blackboard.snapshot())
            assertTrue(host.ids().none { host.get(it)?.isAlive == true })
        } finally {
            try { module.drain(); module.close() } finally { owner.cancelAndJoin(); host.close() }
        }
    }

    @Test fun retainedBytesKeepTheirIdentityPathsAndCursorScopesWithoutWrites() = runTest {
        var writes = 0
        val cas = object : CasStore() {
            override fun put(bytes: ByteArray): ContentId { writes++; return super.put(bytes) }
        }
        val ctx = context(this, cas)
        val service = WorkspaceService(ctx)
        var executions = 0
        ctx.lcncRunners["vm.polyglot"] = LcncNodeRunner { _, _ -> executions++; emptyMap() }
        // Whitespace belongs to the immutable bytes, not a reserialized substitute.
        val bytes = """{ "outputs": { "fn.雪": { "value": [null, false, 7, "患者"] }, "omitted": {"truncated":"work_limit"} } }""".encodeToByteArray()
        val cid = cas.put(bytes).value
        val before = writes
        val board = ctx.blackboard.snapshot()
        val sequence = ctx.couch.store.changes.lastCommittedSequence
        val request = mapOf("source" to source(cid), "path" to listOf("outputs", "fn.雪", "value", 3),
            "limit" to 2, "cursor" to mapOf("path" to listOf(0), "axes" to listOf(listOf(2), listOf(0)), "sourceCid" to cid))
        suspend fun inspect(request: Map<String, Any?>): Map<*, *> {
            val response = service.route("POST", jsonOf(request))
            assertEquals(200, response.status, response.body)
            val result = reifyMap(response.body)
            assertEquals(source(cid), result["source"])
            return assertIs<Map<*, *>>(result["confix"])
        }
        repeat(2) {
            val result = inspect(request)
            assertEquals(cid, result["cid"])
            assertEquals("患者", result["value"])
            assertEquals(bytes.size, (result["bytes"] as Number).toInt())
            assertEquals(2, ((result["cursor"] as Map<*, *>)["indexes"] as List<*>).size)
            assertEquals(2, (result["rows"] as List<*>).size)
            val page = inspect(request + ("offset" to 2))
            assertEquals(2, (page["offset"] as Number).toInt())
        }
        assertEquals(mapOf("truncated" to "work_limit"), inspect(request + ("path" to listOf("outputs", "omitted")))["value"])
        assertEquals(false, inspect(request + ("path" to listOf("absent")))["found"])
        assertEquals(before, writes)
        assertEquals(board, ctx.blackboard.snapshot())
        assertEquals(sequence, ctx.couch.store.changes.lastCommittedSequence)
        assertEquals(0, executions)
    }

    @Test fun unavailableCorruptMalformedOrUnboundedContentNeverFallsBackToCanvas() = runTest {
        val corrupt = ContentId.of("corrupt reference".encodeToByteArray())
        val cas = object : CasStore() {
            override fun get(cid: ContentId): ByteArray? = if (cid == corrupt) "{}".encodeToByteArray() else super.get(cid)
        }
        val ctx = context(this, cas)
        val service = WorkspaceService(ctx)
        val valid = cas.put("null".encodeToByteArray()).value
        suspend fun inspect(cid: String, status: Int, fields: Map<String, Any?> = emptyMap()) {
            val response = service.route("POST", jsonOf(mapOf("source" to source(cid)) + fields))
            assertEquals(status, response.status, response.body)
        }
        inspect(valid, 200)
        inspect(ContentId.of("absent".encodeToByteArray()).value, 404)
        inspect(corrupt.value, 409)
        inspect("bad", 400)
        for (raw in listOf("{} garbage", "[1,]", "{", "", "\"bad\nstring\""))
            inspect(cas.put(raw.encodeToByteArray()).value, 422)
        inspect(cas.put(byteArrayOf(0xff.toByte())).value, 422)
        inspect(cas.put(ByteArray(WorkspaceFacets.MAX_BYTES + 1) { 32 }).value, 413)
        inspect(cas.put(("[".repeat(65) + "0" + "]".repeat(65)).encodeToByteArray()).value, 400)
        inspect(cas.put(("[" + List(WorkspaceFacets.MAX_TOKENS + 1) { "0" }.joinToString(",") + "]").encodeToByteArray()).value, 400)
        inspect(valid, 400, mapOf("document" to mapOf("nodes" to emptyList<Any>(), "wires" to emptyList<Any>())))
        inspect(valid, 400, mapOf("cursor" to mapOf("sourceCid" to corrupt.value)))
        inspect(valid, 400, mapOf("limit" to 129))
        inspect(valid, 400, mapOf("path" to List(33) { "a" }))
        val response = service.route("PUT", jsonOf(mapOf("source" to source(valid))))
        assertEquals(400, response.status)
        val large = cas.put(jsonOf("x".repeat(WorkspaceFacets.MAX_VALUE_BYTES + 1)).encodeToByteArray()).value
        val preview = service.route("POST", jsonOf(mapOf("source" to source(large))))
        assertEquals(200, preview.status, preview.body)
        val confix = reifyMap(preview.body)["confix"] as Map<*, *>
        assertEquals(true, confix["valueOmitted"])
        assertNull(confix["value"])
        assertEquals(large, confix["cid"])
    }
}
