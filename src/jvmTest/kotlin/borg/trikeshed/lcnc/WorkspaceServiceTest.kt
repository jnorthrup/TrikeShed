package borg.trikeshed.lcnc

import borg.trikeshed.couch.*
import borg.trikeshed.job.*
import borg.trikeshed.lib.*
import borg.trikeshed.kanban.module.WorkspaceService
import borg.trikeshed.module.ModuleContext
import borg.trikeshed.parse.*
import borg.trikeshed.pointcut.VmFacet
import borg.trikeshed.util.oroboros.CouchAttachmentGateway
import borg.trikeshed.vm.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import kotlin.test.*

class WorkspaceServiceTest {
    @Test fun catalogUsesExecutionContextPrecedenceAndDoesNotAssumeAnUnboundHost() = runTest {
        fun host(facet: VmFacet) = object : VmHost {
            override val languages = setOf(facet)
            override fun spawn(spec: VmSpec): VmHandle = error("Catalog must not spawn a guest")
        }
        val module = CoroutineScope(coroutineContext + VmHostKey(host(VmFacet.GRAAL_PYTHON)))
        val ctx = vmRuntimeTestContext(module)
        ctx.lcncRunners[SubVmLegos.POLYGLOT] = SubVmLegos.polyglot(host(VmFacet.GRAAL_JS))
        suspend fun available(context: ModuleContext) = WorkspaceService(context).capabilities()
            .view.filter { it.available }.map { it.language.id }
        assertEquals(listOf("python"), available(ctx))
        val mux = vmRuntimeTestContext(module, VmHostKey(host(VmFacet.GRAAL_RUBY)))
        mux.lcncRunners.putAll(ctx.lcncRunners)
        assertEquals(listOf("ruby"), available(mux))
        withContext(VmHostKey(host(VmFacet.GRAAL_JS))) {
            assertEquals(listOf("js"), available(mux))
            mux.lcncRunners.clear()
            assertTrue(available(mux).isEmpty(), "A caller's host cannot supply a missing runner")
        }
    }

    @Test fun catalogUsesTheRegisteredHostWithoutSpawningAGuest() = runTest {
        val ctx = vmRuntimeTestContext(this)
        var probes = 0
        val host = object : VmHost {
            override val languages: Set<VmFacet> get() {
                probes++
                return setOf(VmFacet.GRAAL_JS)
            }
            override fun spawn(spec: VmSpec): VmHandle = error("Catalog must not spawn a guest")
        }
        ctx.lcncRunners[SubVmLegos.POLYGLOT] = SubVmLegos.polyglot(host)
        ctx.lcncRunners[SubVmLegos.CAMEL] = SubVmLegos.camel(host)
        val revision = ctx.blackboard.snapshot().revision
        val service = WorkspaceService(ctx)
        val response = service.route("GET", "")
        assertEquals(200, response.status)
        val catalog = reifyMap(response.body)
        val languages = assertIs<List<*>>(catalog["languages"]).map { assertIs<Map<*, *>>(it) }
        assertTrue(probes > 0, "Language discovery must inspect the registered runner's host")
        assertEquals(listOf("js"), languages.filter { it["available"] == true }.map { it["id"] })
        assertNotNull(languages.single { it["id"] == "js" }["construction"])
        withContext(VmHostKey(VmHost.NONE)) {
            val disabled = assertIs<List<*>>(service.catalog()["languages"]).map { assertIs<Map<*, *>>(it) }
            assertTrue(disabled.none { it["available"] == true || it["construction"] != null })
        }
        ctx.lcncRunners[SubVmLegos.POLYGLOT] = LcncNodeRunner { _, _ -> error("Discovery must not execute") }
        val unknown = assertIs<List<*>>(service.catalog()["languages"]).map { assertIs<Map<*, *>>(it) }
        assertTrue(unknown.none { it["available"] == true || it["construction"] != null },
            "A runner without declared host bindings cannot inherit the global host's capability")
        assertEquals(revision, ctx.blackboard.snapshot().revision)
    }

    @Test fun inspectionDistinguishesInterpreterScopesFromMissingRunnersWithoutExecution() = runTest {
        val ctx = vmRuntimeTestContext(this)
        var executions = 0
        ctx.lcncRunners["text.value"] = LcncNodeRunner { _, _ -> executions++; emptyMap() }
        ctx.lcncRunners[LcncContracts.SCOPE] = LcncNodeRunner { _, _ -> executions++; error("unused scope runner") }
        val service = WorkspaceService(ctx)
        val revision = ctx.blackboard.snapshot().revision
        val children = s_[
            LcncNode("in", LcncContracts.SCOPE_IN, mapOf("name" to "evidence", "default" to "retained")),
            LcncNode("out", LcncContracts.SCOPE_OUT, mapOf("name" to "evidence")),
        ]
        val program = LcncProgram("scope-owner", s_[
            LcncNode("ring", LcncContracts.SCOPE, children = children),
            LcncNode("inline", "text.value", children = s_[LcncNode("nested", "note")]),
            LcncNode("named", LcncContracts.SCOPE, mapOf("program" to "not-loaded-by-inspection")),
            LcncNode("registry-scope", LcncContracts.SCOPE),
            LcncNode("missing", "vm.polyglot"),
            LcncNode("value", "text.value"),
        ], s_[LcncWire("in", "value", "out", "value")])
        val result = post(service, jsonOf(mapOf("document" to reify(LcncProgramConfix.toJson(program)))))
        val bindings = assertIs<List<*>>(result["bindings"]).map { assertIs<Map<*, *>>(it) }.associateBy { it["id"] }
        for (id in listOf("ring", "in", "out", "inline", "named")) {
            val binding = bindings.getValue(id)
            assertEquals("INTERPRETER", binding["binding"], id)
            assertEquals("LcncScopeFrame.Key", assertIs<Map<*, *>>(binding["context"])["key"], id)
            assertEquals(emptyList<String>(), binding["requiredKeys"])
        }
        assertEquals("UNBOUND", bindings.getValue("missing")["binding"])
        assertEquals("KOTLIN", bindings.getValue("value")["binding"])
        assertEquals("KOTLIN", bindings.getValue("registry-scope")["binding"])
        assertEquals(0, executions)
        assertEquals(revision, ctx.blackboard.snapshot().revision)
    }

    @Test fun receiptDiscoveryKeepsNamedHistoryAndExactInlineVersionsSeparate() = runTest {
        val ctx = vmRuntimeTestContext(this)
        val service = WorkspaceService(ctx)
        var executions = 0
        ctx.lcncRunners["text.value"] = LcncNodeRunner { _, _ -> executions++; emptyMap() }
        val program = LcncProgram("canvas", s_[LcncNode("source", "text.value", mapOf("value" to "review"))], emptySeriesOf())
        val cid = LcncBlackboard.cidOf(program)
        val otherCid = LcncBlackboard.cidOf(program.copy(nodes = s_[program.nodes[0].copy(params = mapOf("value" to "different"))]))
        val key = LcncBlackboard.programKey(program.name)
        fun record(id: String, programKey: String?, programCid: String?, label: String = program.name) {
            ctx.blackboard.put("lcnc/run/$id", mapOf("runId" to id, "program" to label,
                "programKey" to programKey, "programCid" to programCid, "status" to "failed",
                "receiptCid" to ContentId.of(id.encodeToByteArray()).value, "startedAtMs" to 123L,
                "sequence" to id.length.toLong()), "fixture")
        }
        record("named-old", key, otherCid)
        record("named-current", key, cid)
        record("inline-exact", null, cid, "unrelated-label")
        record("inline-stale", null, otherCid)
        record("inline-unversioned", null, null)
        record("other-named", LcncBlackboard.programKey("other"), cid)
        val board = ctx.blackboard.snapshot()
        val sequence = ctx.couch.store.changes.lastCommittedSequence
        val request = mapOf("name" to program.name, "document" to reifyStrict(LcncProgramConfix.toJson(program)))
        repeat(2) {
            val result = post(service, jsonOf(request))
            val runs = assertIs<List<*>>(result["runs"]).map { assertIs<Map<*, *>>(it) }.associateBy { it["runId"] }
            assertEquals(setOf("named-old", "named-current", "inline-exact"), runs.keys)
            assertEquals(false, runs.getValue("named-old")["sameVersion"])
            assertEquals(key, runs.getValue("named-current")["programKey"])
            assertEquals(true, runs.getValue("inline-exact")["sameVersion"])
            assertNull(runs.getValue("inline-exact")["programKey"])
            assertEquals(ContentId.of("inline-exact".encodeToByteArray()).value, runs.getValue("inline-exact")["receiptCid"])
        }
        assertEquals(board, ctx.blackboard.snapshot())
        assertEquals(sequence, ctx.couch.store.changes.lastCommittedSequence)
        assertEquals(0, executions)
    }

    @Test fun inlineReceiptDiscoveryRetainsTheEightRunWindowAndSequenceOrder() = runTest {
        val ctx = vmRuntimeTestContext(this)
        val service = WorkspaceService(ctx)
        val program = LcncProgram("canvas", emptySeriesOf(), emptySeriesOf())
        val cid = LcncBlackboard.cidOf(program)
        repeat(12) { i -> ctx.blackboard.put("lcnc/run/$i", mapOf("runId" to "$i", "programCid" to cid,
            "status" to "completed", "startedAtMs" to if (i == 0) 124L else 123L, "sequence" to i), "fixture") }
        val result = post(service, jsonOf(mapOf("name" to program.name,
            "document" to reifyStrict(LcncProgramConfix.toJson(program)))))
        val ids = assertIs<List<*>>(result["runs"]).map { assertIs<Map<*, *>>(it)["runId"] }
        assertEquals(listOf("0", "11", "10", "9", "8", "7", "6", "5"), ids)
    }

    @Test fun inlineDiscoveryUsesFrozenDefinitionsForPresentationChangesButNotEdits() = runTest {
        val ctx = vmRuntimeTestContext(this)
        val service = WorkspaceService(ctx)
        var executions = 0
        ctx.lcncRunners["text.value"] = LcncNodeRunner { _, _ -> executions++; emptyMap() }
        val original = LcncProgram("canvas", s_[LcncNode("source", "text.value", mapOf("value" to "review"), x = 10.0)], emptySeriesOf())
        val cid = ctx.casStore.put(LcncProgramConfix.toJson(original).encodeToByteArray()).value
        val malformed = ctx.casStore.put("{}".encodeToByteArray()).value
        for ((id, frozen) in listOf("retained" to cid, "malformed" to malformed,
            "missing" to ContentId.of("missing".encodeToByteArray()).value)) {
            ctx.blackboard.put("lcnc/run/$id", mapOf("runId" to id, "programCid" to frozen,
                "status" to "failed", "startedAtMs" to 123L), "fixture")
        }
        val board = ctx.blackboard.snapshot()
        suspend fun inspect(program: LcncProgram): List<Map<*, *>> = assertIs<List<*>>(post(service,
            jsonOf(mapOf("name" to program.name, "document" to reifyStrict(LcncProgramConfix.toJson(program)))))["runs"])
            .map { assertIs<Map<*, *>>(it) }
        val moved = original.copy(nodes = s_[original.nodes[0].copy(x = 450.0)])
        val retained = inspect(moved).single()
        assertEquals("retained", retained["runId"])
        assertEquals(false, retained["sameVersion"])
        assertEquals("presentation", retained["programRelation"])
        assertNull(retained["programKey"])
        assertTrue(inspect(moved.copy(nodes = s_[moved.nodes[0].copy(params = mapOf("value" to "edited"))])).isEmpty())
        assertEquals(board, ctx.blackboard.snapshot())
        assertEquals(0, executions)
    }

    @Test fun inlineComparisonBudgetDoesNotHideExactMatchesOrNamedHistory() = runTest {
        val ctx = vmRuntimeTestContext(this)
        val service = WorkspaceService(ctx)
        val program = LcncProgram("canvas", s_[LcncNode("source", "text.value", mapOf("value" to "review"), x = 100.0)], emptySeriesOf())
        val presentation = program.copy(nodes = s_[program.nodes[0].copy(x = 10.0)])
        val presentationCid = ctx.casStore.put(LcncProgramConfix.toJson(presentation).encodeToByteArray()).value
        ctx.blackboard.put("lcnc/run/older-presentation", mapOf("runId" to "older-presentation",
            "programCid" to presentationCid, "status" to "completed", "startedAtMs" to 122L), "fixture")
        repeat(34) { i -> ctx.blackboard.put("lcnc/run/other-$i", mapOf("runId" to "other-$i",
            "programCid" to ContentId.of("missing-$i".encodeToByteArray()).value,
            "status" to "completed", "startedAtMs" to 124L, "sequence" to i), "fixture") }
        for (id in listOf("exact", "named")) ctx.blackboard.put("lcnc/run/$id", mapOf("runId" to id,
            "programKey" to if (id == "named") LcncBlackboard.programKey(program.name) else null,
            "programCid" to if (id == "exact") LcncBlackboard.cidOf(program) else ContentId.of("old".encodeToByteArray()).value,
            "status" to "completed", "startedAtMs" to 123L), "fixture")
        val result = post(service, jsonOf(mapOf("name" to program.name,
            "document" to reifyStrict(LcncProgramConfix.toJson(program)))))
        assertEquals(true, result["runComparisonLimited"])
        assertEquals(setOf("exact", "named"), assertIs<List<*>>(result["runs"]).map { assertIs<Map<*, *>>(it)["runId"] }.toSet())
    }

    @Test fun frozenProgramComparisonIsReadOnlyAndKeepsExactIdentityDistinct() = runTest {
        var writes = 0
        val cas = object : CasStore() {
            override fun put(bytes: ByteArray): ContentId { writes++; return super.put(bytes) }
        }
        val store = CouchStoreFactory.casBacked(cas)
        val base = vmRuntimeTestContext(this)
        val ctx = ModuleContext(couch = Couch("comparison", store, cas), rete = base.rete,
            productions = base.productions, beliefBag = null, turnReview = null, blackboard = base.blackboard,
            casStore = cas, attachments = CouchAttachmentGateway(store, cas), routes = base.routes,
            scope = this, clock = base.clock, stateDir = base.stateDir)
        var executions = 0
        ctx.lcncRunners["text.value"] = LcncNodeRunner { _, _ -> executions++; emptyMap() }
        val service = WorkspaceService(ctx)
        val original = LcncProgram("receipt", s_[LcncNode("source", "text.value", mapOf("value" to "雪"), x = 1270.0)], emptySeriesOf())
        val cid = cas.put(LcncProgramConfix.toJson(original).encodeToByteArray()).value
        val initialWrites = writes
        val board = ctx.blackboard.snapshot()
        val sequence = store.changes.lastCommittedSequence
        suspend fun inspect(program: LcncProgram, expected: String): Map<*, *> {
            val result = post(service, jsonOf(mapOf("name" to program.name,
                "document" to reifyStrict(LcncProgramConfix.toJson(program)), "compareProgramCid" to cid)))
            val comparison = assertIs<Map<*, *>>(result["comparison"])
            assertEquals(cid, comparison["programCid"])
            assertEquals(expected, comparison["relation"])
            return result
        }
        assertEquals(cid, inspect(original, "identical")["programCid"])
        val moved = original.copy(nodes = s_[original.nodes[0].copy(x = 1354.0)])
        assertNotEquals(cid, inspect(moved, "presentation")["programCid"])
        inspect(moved.copy(nodes = s_[moved.nodes[0].copy(params = mapOf("value" to "edited"))]), "different")
        assertEquals(initialWrites, writes)
        assertEquals(board, ctx.blackboard.snapshot())
        assertEquals(sequence, store.changes.lastCommittedSequence)
        assertEquals(0, executions)
    }

    @Test fun comparisonCannotInventAMatchForMissingMalformedOrNonProgramContent() = runTest {
        val ctx = vmRuntimeTestContext(this)
        val service = WorkspaceService(ctx)
        val request = mapOf("document" to mapOf("nodes" to emptyList<Any>(), "wires" to emptyList<Any>()))
        val missing = ContentId.of("missing".encodeToByteArray()).value
        val result = post(service, jsonOf(request + ("compareProgramCid" to missing)))
        assertEquals("unavailable", assertIs<Map<*, *>>(result["comparison"])["relation"])
        for (invalid in listOf("bad", 123, ctx.casStore.put("{}".encodeToByteArray()).value,
            ctx.casStore.put("{\"nodes\":[],\"wires\":[],\"unknown\":true}".encodeToByteArray()).value)) {
            post(service, jsonOf(request + ("compareProgramCid" to invalid)), 400)
        }
    }

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
        val documentRoute = assertIs<Map<*, *>>(service.language(available)["documentRoutingConstruction"])
        val retained = LcncProgramConfix.fromJson(documentRoute["name"] as String, jsonOf(documentRoute["document"]))
        assertEquals("preset-document-routing-java", retained.name)
        assertTrue(LcncTypeCheck.check(retained).isEmpty(), LcncTypeCheck.check(retained).joinToString { it.render() })
        assertEquals("", retained.nodes.view.single { it.type == "document.sheet" }.params["receiptCid"])
        assertEquals("[]", retained.nodes.view.single { it.id == "assumptions" }.params["value"])
        assertEquals("java", retained.nodes.view.single { it.type == "vm.polyglot" }.params["language"])
        assertFalse(retained.nodes.view.any { it.type in setOf("document.curate", "document.tasks", "council.seat") })
        assertNull(service.language(available.copy(available = false))["construction"])
        assertNull(service.language(available.copy(available = false))["routeConstruction"])
        assertNull(service.language(available.copy(available = false))["documentRoutingConstruction"])
        assertEquals(revision, ctx.blackboard.snapshot().revision)
        assertEquals(0, executions)
    }

    @Test fun nativeCamelConstructionsDoNotRequireThePolyglotFunctionRunner() = runTest {
        val ctx = vmRuntimeTestContext(this)
        val service = WorkspaceService(ctx)
        var executions = 0
        val revision = ctx.blackboard.snapshot().revision
        for (language in listOf(VmLanguage.JAVA, VmLanguage.GROOVY)) {
            val capability = VmLanguageCapability(language, false, false, "No GraalJS function adapter")
            assertNull(service.language(capability)["nativeConstruction"])
        }
        ctx.lcncRunners[SubVmLegos.CAMEL] = LcncNodeRunner { _, _ -> executions++; emptyMap() }
        for (language in listOf(VmLanguage.JAVA, VmLanguage.GROOVY)) {
            val descriptor = service.language(VmLanguageCapability(language, false, false, "No GraalJS function adapter"))
            assertNull(descriptor["construction"])
            val native = assertIs<Map<*, *>>(descriptor["nativeConstruction"])
            assertEquals(true, native["available"], native["note"].toString())
            val program = LcncProgramConfix.fromJson(native["name"] as String, jsonOf(native["document"]))
            assertTrue(LcncTypeCheck.check(program).isEmpty())
            val route = program.nodes.view.single { it.type == "vm.camel" }
            assertEquals(language.id, route.params["language"])
            assertEquals("native-dsl", route.params["authoring"])
            assertTrue(route.params.getValue("source").isNotBlank())
            assertTrue(program.controls.humanOversight)
        }
        assertNull(service.language(VmLanguageCapability(VmLanguage.PYTHON, true, true, ""))["nativeConstruction"])
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
