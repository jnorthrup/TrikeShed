package borg.trikeshed.lcnc

import borg.trikeshed.couch.*
import borg.trikeshed.job.*
import borg.trikeshed.kanban.module.WorkspaceService
import borg.trikeshed.module.ModuleContext
import borg.trikeshed.parse.*
import borg.trikeshed.util.oroboros.CouchAttachmentGateway
import borg.trikeshed.vm.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class WorkspaceServiceTest {
    @Test fun languageConstructionsRequireCapabilitiesAndBothRunnersWithoutExecuting() = runTest {
        val ctx = vmRuntimeTestContext(this)
        val service = WorkspaceService(ctx)
        var executions = 0
        val runner = LcncNodeRunner { _, _ -> executions++; emptyMap() }
        val available = VmLanguageCapability(VmLanguage.JAVA, true, true, "test capability")
        assertNull(service.language(available)["construction"])
        ctx.lcncRunners[SubVmLegos.POLYGLOT] = runner
        assertNull(service.language(available)["construction"])
        ctx.lcncRunners[SubVmLegos.CAMEL] = runner
        val revision = ctx.blackboard.snapshot().revision
        val construction = assertIs<Map<*, *>>(service.language(available)["construction"])
        val name = assertIs<String>(construction["name"])
        val program = LcncProgramConfix.fromJson(name, jsonOf(construction["document"]))
        assertEquals("preset-polyglot-camel-java", name)
        assertTrue(LcncTypeCheck.check(program).isEmpty())
        val route = assertIs<Map<*, *>>(service.language(available)["routeConstruction"])
        assertEquals("preset-camel-authoring-java", route["name"])
        assertTrue(LcncTypeCheck.check(LcncProgramConfix.fromJson(route["name"] as String,
            jsonOf(route["document"]))).isEmpty())
        assertNull(service.language(available.copy(available = false))["construction"])
        assertNull(service.language(available.copy(available = false))["routeConstruction"])
        assertEquals(revision, ctx.blackboard.snapshot().revision)
        assertEquals(0, executions)
    }

    @Test fun inspectionDoesNotRunSaveOrPublishAndRejectsMalformedWindows() = runTest {
        val ctx = vmRuntimeTestContext(this)
        var executions = 0
        ctx.lcncRunners["text.value"] = LcncNodeRunner { _, _ -> executions++; emptyMap() }
        val service = WorkspaceService(ctx)
        val revision = ctx.blackboard.snapshot().revision
        val request = mapOf("name" to "canvas", "document" to mapOf(
            "nodes" to listOf(mapOf("id" to "n", "type" to "text.value", "params" to mapOf("value" to "雪"))),
            "wires" to emptyList<Any>()), "path" to listOf("nodes", 0, "params"), "limit" to 2)
        val response = service.route("POST", jsonOf(request))
        assertEquals(200, response.status, response.body)
        val confix = (reify(response.body) as Map<*, *>)["confix"] as Map<*, *>
        assertEquals(mapOf("value" to "雪"), confix["value"])
        assertEquals(2, (confix["rows"] as List<*>).size)
        val root = (confix["rows"] as List<*>).first() as Map<*, *>
        val children = assertIs<List<*>>(root["children"])
        assertEquals((root["childCount"] as Number).toInt(), children.size)
        assertTrue(children.isNotEmpty() && children.all { it is Number })
        assertEquals(0, executions)
        assertEquals(revision, ctx.blackboard.snapshot().revision)
        val binding = ((reify(response.body) as Map<*, *>)["bindings"] as List<*>).single() as Map<*, *>
        assertEquals("KOTLIN", binding["binding"])
        assertEquals("LcncNodeKey.TEXT_VALUE", (binding["context"] as Map<*, *>)["key"])
        assertEquals(400, service.route("POST", jsonOf(request + ("offset" to 0.5))).status)
        assertEquals(400, service.route("POST", jsonOf(request + ("path" to "nodes"))).status)
        assertEquals(405, service.route("DELETE", "").status)
        assertEquals(400, service.route("POST", jsonOf(request) + " trailing").status)
        val cursorRequest = request + ("cursor" to mapOf("path" to listOf(0), "axes" to listOf(listOf(2), listOf(0, 1))))
        val indexed = service.route("POST", jsonOf(cursorRequest))
        assertEquals(200, indexed.status, indexed.body)
        val cursor = ((reify(indexed.body) as Map<*, *>)["confix"] as Map<*, *>)["cursor"] as Map<*, *>
        assertEquals(2, (cursor["indexes"] as List<*>).size)
        for (index in cursor["indexes"] as List<*>) {
            for (group in (index as Map<*, *>)["groups"] as List<*>) {
                val ordinals = assertIs<List<*>>((group as Map<*, *>)["ordinals"])
                assertTrue(ordinals.isNotEmpty() && ordinals.size <= 2)
                assertTrue(ordinals.all { it is Number && it.toInt() in 0 until (cursor["rowCount"] as Number).toInt() })
            }
        }
        for (invalid in listOf(mapOf("sourceCid" to "stale"), mapOf("path" to listOf(0.5)),
            mapOf("selections" to listOf(listOf(0))))) {
            assertEquals(400, service.route("POST", jsonOf(request + ("cursor" to invalid))).status)
        }
        assertEquals(0, executions)
        assertEquals(revision, ctx.blackboard.snapshot().revision)
        assertEquals(400, service.route("POST", jsonOf(mapOf("document" to mapOf(
            "nodes" to listOf(mapOf("type" to "text.value")), "wires" to emptyList<Any>())))).status)
    }

    suspend fun post(service: WorkspaceService, body: String, status: Int = 200): Map<*, *> {
        val response = service.route("POST", body)
        assertEquals(status, response.status, response.body)
        val result = assertIs<Map<*, *>>(reifyStrict(response.body))
        if (status == 400) {
            assertTrue(assertIs<String>(result["error"]).isNotBlank())
            assertFalse("confix" in result)
        }
        return result
    }

    @Test fun shakeDemoPostPagesAndSelectsParamsWithoutExecutionOrWrites() = runTest {
        var writes = 0
        val cas = object : CasStore() {
            override fun put(bytes: ByteArray): ContentId { writes++; return super.put(bytes) }
        }
        val store = CouchStoreFactory.casBacked(cas)
        val base = vmRuntimeTestContext(this)
        val ctx = ModuleContext(
            couch = Couch("workspace-inspection", store, cas), rete = base.rete, productions = base.productions,
            beliefBag = null, turnReview = null, blackboard = base.blackboard, casStore = cas,
            attachments = CouchAttachmentGateway(store, cas), routes = base.routes, scope = this,
            clock = base.clock, stateDir = base.stateDir,
        )
        var executions = 0
        for (contract in LcncContracts.all())
            ctx.lcncRunners[contract.type] = LcncNodeRunner { _, _ -> executions++; emptyMap() }
        val service = WorkspaceService(ctx)
        val program = LcncShakeDemo.build().program
        val document = assertIs<Map<*, *>>(reifyStrict(LcncProgramConfix.toJson(program)))
        val request = mapOf("name" to program.name, "document" to document, "limit" to 7)
        val board = ctx.blackboard.snapshot()
        val sequence = store.changes.lastCommittedSequence
        val initialWrites = writes

        val response = post(service, jsonOf(request))
        assertEquals(program.name, response["name"])
        val root = assertIs<Map<*, *>>(response["confix"])
        assertEquals(true, root["found"])
        assertEquals(true, root["valueOmitted"])
        assertNull(root["value"])
        assertEquals(WorkspaceFacets.MAX_VALUE_BYTES, assertIs<Number>(root["valueLimit"]).toInt())
        assertTrue(assertIs<Number>(root["valueBytes"]).toInt() > WorkspaceFacets.MAX_VALUE_BYTES)
        val rows = assertIs<List<*>>(root["rows"])
        assertEquals(7, rows.size)
        assertTrue(assertIs<Number>(root["tokens"]).toInt() > rows.size)
        val next = assertIs<Number>(root["next"]).toInt()
        assertEquals(rows.size, next)
        val cursor = mapOf("sourceCid" to assertIs<String>(root["cid"]))
        val page = assertIs<Map<*, *>>(post(service, jsonOf(request + mapOf("offset" to next, "cursor" to cursor)))["confix"])
        assertEquals(root["cid"], page["cid"])
        assertEquals(next, assertIs<Number>(page["offset"]).toInt())
        val pageRows = assertIs<List<*>>(page["rows"])
        assertEquals(rows.size, pageRows.size)
        pageRows.forEachIndexed { i, row ->
            assertEquals(next + i, assertIs<Number>(assertIs<Map<*, *>>(row)["token"]).toInt())
        }

        val selected = assertIs<Map<*, *>>(post(service, jsonOf(request + mapOf(
            "path" to listOf("nodes", 0, "params"), "cursor" to cursor)))["confix"])
        val params = assertIs<Map<*, *>>(assertIs<List<*>>(document["nodes"]).first())["params"]
        assertEquals(root["cid"], selected["cid"])
        assertEquals(true, selected["found"])
        assertEquals(false, selected["valueOmitted"])
        assertEquals(params, selected["value"])
        assertTrue(assertIs<Number>(selected["valueBytes"]).toInt() in 1..WorkspaceFacets.MAX_VALUE_BYTES)
        assertEquals(0, executions)
        assertEquals(board, ctx.blackboard.snapshot())
        assertEquals(board.revision, assertIs<Number>(response["blackboardRevision"]).toLong())
        assertEquals(sequence, store.changes.lastCommittedSequence)
        assertEquals(initialWrites, writes)
    }

    @Test fun requestLimitCountsUtf8Bytes() = runTest {
        val service = WorkspaceService(vmRuntimeTestContext(this))
        val body = jsonOf(mapOf("document" to mapOf("nodes" to emptyList<Any>(), "wires" to emptyList<Any>()),
            "padding" to "雪".repeat(WorkspaceFacets.MAX_BYTES / 3 + 1)))
        assertTrue(body.length < WorkspaceFacets.MAX_BYTES)
        assertTrue(body.encodeToByteArray().size > WorkspaceFacets.MAX_BYTES)
        assertTrue(assertIs<String>(post(service, body, 400)["error"]).contains("byte"))
    }

    @Test fun traversalLimitRejectsValuesBeyondTheWorkspaceBudget() = runTest {
        val service = WorkspaceService(vmRuntimeTestContext(this))
        val body = jsonOf(mapOf("document" to mapOf("nodes" to emptyList<Any>(), "wires" to emptyList<Any>(),
            "values" to List(WorkspaceFacets.MAX_TOKENS + 1) { "" }), "limit" to 1))
        assertTrue(body.encodeToByteArray().size < WorkspaceFacets.MAX_BYTES)
        assertEquals("work_limit", post(service, body, 400)["error"])
    }

    @Test fun excessiveDepthAndMalformedNestedJsonRemainErrors() = runTest {
        val service = WorkspaceService(vmRuntimeTestContext(this))
        val prefix = """{"document":{"nodes":[],"wires":[],"value":"""
        val depth = WorkspaceFacets.valueBudget().maxDepth + 1
        val deep = prefix + "[".repeat(depth) + "0" + "]".repeat(depth) + "}}"
        assertEquals("depth_limit", post(service, deep, 400)["error"])
        post(service, prefix + "[".repeat(8) + "0" + "]".repeat(7) + "}}", 400)
        val parserDepth = prefix + "[".repeat(257) + "0" + "]".repeat(257) + "}}"
        assertTrue(assertIs<String>(post(service, parserDepth, 400)["error"]).contains("nesting"))
    }

    @Test fun requestWrappersPathsWindowsAndCursorCidsRemainValidated() = runTest {
        val service = WorkspaceService(vmRuntimeTestContext(this))
        val request = mapOf("document" to mapOf("nodes" to listOf(mapOf("id" to "n", "type" to "text.value",
            "params" to mapOf("value" to "fresh"))), "wires" to emptyList<Any>()))
        post(service, jsonOf(request))
        post(service, "[]", 400)
        for (invalid in listOf(
            mapOf("document" to "{}"), mapOf("document" to mapOf("nodes" to emptyList<Any>())),
            mapOf("document" to mapOf("nodes" to "[]", "wires" to emptyList<Any>())),
            mapOf("path" to "nodes"), mapOf("path" to listOf(-1)), mapOf("path" to listOf(0.5)),
            mapOf("path" to listOf(true)), mapOf("path" to List(33) { "nodes" }),
            mapOf("offset" to -1), mapOf("offset" to 0.5),
            mapOf("limit" to 0), mapOf("limit" to WorkspaceFacets.MAX_ROWS + 1),
            mapOf("cursor" to "{}"), mapOf("cursor" to mapOf("sourceCid" to "stale")),
            mapOf("cursor" to mapOf("sourceCid" to 1)), mapOf("cursor" to mapOf("path" to listOf(0.5))),
            mapOf("cursor" to mapOf("offset" to -1)), mapOf("cursor" to mapOf("groupOffset" to -1)),
            mapOf("cursor" to mapOf("axes" to listOf(listOf(3)))),
            mapOf("cursor" to mapOf("selections" to listOf(listOf(0)))),
        )) post(service, jsonOf(request + invalid), 400)
    }
}
