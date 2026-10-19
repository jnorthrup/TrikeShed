package borg.trikeshed.lcnc

import borg.trikeshed.context.nuid.*
import borg.trikeshed.forge.server.*
import borg.trikeshed.job.*
import borg.trikeshed.lcnc.ccek.*
import borg.trikeshed.lib.*
import borg.trikeshed.memory.*
import borg.trikeshed.modelmux.BrainClient
import borg.trikeshed.module.ModuleContext
import borg.trikeshed.parse.*
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.userspace.nio.platform.spi.SystemOperations
import keymux.KeyMux
import modelmux.ModelMux
import kotlinx.coroutines.*
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

/** Production saves, workgroup registration, queued execution and receipt transfer; native UI actions only. */
class WorkgroupsBrowserTest {
    @org.junit.jupiter.api.io.TempDir lateinit var directory: Path

    @Test
    @org.junit.jupiter.api.Timeout(value = 12, unit = java.util.concurrent.TimeUnit.MINUTES)
    fun savedDeclarationsRunInTheirSubnetAndPollinateAnExplicitRecipient() = runBlocking<Unit> {
        org.junit.jupiter.api.Assumptions.assumeTrue(
            SystemOperations.default.getenv("TRIKESHED_WORKGROUPS_UI") == "1")
        val sourceName = "browser-tasks"
        val targetName = "browser-publish"
        val sourceScope = "local.tasks"
        val targetScope = "local.publish"
        val supplied = mapOf("operator" to "native-browser", "value" to 9.0)
        val scene = LcncProgram("workgroups-canvas", s_[
            LcncNode("constant", "json.value", mapOf("value" to "7"), x = 20.0, y = 20.0),
            LcncNode("result", "scope.out", mapOf("name" to "result", "kind" to "json"), x = 340.0, y = 20.0),
            LcncNode("input", "scope.in", mapOf("name" to "evidence", "kind" to "json", "default" to "{}"), x = 20.0, y = 280.0),
            LcncNode("evidence", "scope.out", mapOf("name" to "evidence", "kind" to "json"), x = 340.0, y = 280.0),
        ], s_[
            LcncWire("constant", "value", "result", "value"),
            LcncWire("input", "value", "evidence", "value"),
        ], controls = LcncConfixControls(humanOversight = false,
            matingPoints = s_[LcncPatchMatingPoint("constant-result", "constant", "value", "result", "value")]),
            view = LcncView(20.0, 20.0, 0.85))
        assertTrue(LcncTypeCheck.check(scene).isEmpty())
        val saves = CopyOnWriteArrayList<Map<String, Any?>>()
        val commands = CopyOnWriteArrayList<Map<String, Any?>>()
        val runs = CopyOnWriteArrayList<Map<String, Any?>>()
        val inventories = CopyOnWriteArrayList<Map<String, Any?>>()
        val calls = CopyOnWriteArrayList<Map<String, Any?>>()
        val binding = ccekReactorBinding(coroutineContext)
        val context = vmRuntimeTestContext(this)
        val pure = PureNodes.registry { 1L }
        context.lcncRunners.putAll(pure)
        val literal = pure.getValue("json.value")
        context.lcncRunners["json.value"] = LcncPureRunner { node, inputs ->
            val actual = literal.run(node, inputs)
            val invocation = LcncWorkgroupKey.require()
            calls += mapOf("nodeId" to node.id, "outputs" to actual,
                "workgroup" to invocation.a.name, "programCid" to invocation.b.b.value,
                "claimId" to invocation.b.a.claimId, "subnet" to invocation.b.a.nuid.subnet.toString())
            actual
        }
        val publisher = LcncPublisher(context.blackboard, { context.lcncRunners }, context.attachments, context.rete)
        val memory = MemoryIndexLayer(MemoryStore(context.casStore, context.couch.store))
        val scopes = ProjectScopes(JvmFileOperations(), context.attachments,
            CouchIndexBridge(context.attachments, memory), context.casStore, null)
        val keys = KeyMux {}
        val ledger = ProgramLedger(context.attachments, context.casStore,
            borg.trikeshed.common.File(directory.toString(), "programs/ledger.jsonl"), publisher) { 123L }
        val realPatchWire = PatchWire(
            BrainClient(apiKey = "fixture", keyMux = keys, modelMux = ModelMux(keys) {}),
            scopes, attachments = context.attachments, publisher = publisher, programs = ledger)
        lateinit var bound: ModuleContext
        fun snapshot(): Map<String, Any?> = mapOf("saves" to saves.toList(), "commands" to commands.toList(),
            "runs" to runs.toList(), "inventories" to inventories.toList(), "calls" to calls.toList(),
            "stored" to context.attachments.listAttachments("panels/").map { mapOf("path" to it.path, "cid" to it.contentId.value) })
        try {
            binding.reactor.open()
            retainedSimulationBrowser(context, null, binding, scene,
                stateDirectory = directory.resolve("state").toFile(), executorThreads = 4,
                programLoader = publisher::load, opened = { bound = it },
                testRoute = { method, path, body ->
                    when {
                        path == "/api/panels" || path.startsWith("/api/panels/") && path != "/api/panels/retained-check" -> {
                            val response = realPatchWire.route(method, path,
                                "$method $path HTTP/1.1\r\nContent-Type: application/json\r\n\r\n$body", null)
                            if (method == "POST" && response != null) saves += mapOf("path" to path,
                                "document" to reifyMap(body), "status" to response.status, "response" to reifyMap(response.body))
                            response
                        }
                        method == "GET" && path == "/api/lcnc/workgroups" -> {
                            val response = bound.routes.match(path)!!.route(method, path, body, null)!!
                            inventories += reifyMap(response.body)
                            response
                        }
                        method == "POST" && path == "/api/lcnc/run" -> {
                            val response = bound.routes.match(path)!!.route(method, path, body, null)!!
                            val result = reifyMap(response.body)
                            commands += mapOf("request" to reifyMap(body), "status" to response.status, "response" to result)
                            if (result["receiptCid"] != null) runs += result
                            response
                        }
                        else -> null
                    }
                }, observations = ::snapshot)

            // Current toolbar Save + canvas Run are the positive baseline before new controls are required.
            val initial = assertNotNull(saves.firstOrNull { it["path"] == "/api/panels/$sourceName" }, jsonOf(snapshot()))
            assertEquals(200, initial["status"], jsonOf(initial))
            assertEquals("ok", assertIs<Map<*, *>>(initial["response"])["verdict"])
            val initialProgram = LcncProgramConfix.fromJson(sourceName, jsonOf(initial["document"]))
            assertNull(initialProgram.controls.subnet)
            val baseline = assertNotNull(runs.firstOrNull(), jsonOf(snapshot()))
            assertEquals("completed", baseline["status"], jsonOf(baseline))
            assertEquals(true, baseline["ok"])
            assertEquals("local", baseline["subnet"])
            assertEquals(7, (assertIs<Map<*, *>>(baseline["returns"])["result"] as Number).toInt())
            assertEquals("{}", assertIs<Map<*, *>>(baseline["returns"])["evidence"], "scope.in defaults are literal strings")
            assertTrue(assertIs<Map<*, *>>(commands.first()["request"]).containsKey("document"),
                "The existing canvas Run remains the baseline path")
            assertEquals(1, calls.count { (it["claimId"] as Number).toLong() == (baseline["claimId"] as Number).toLong() })

            val declaredSave = assertNotNull(saves.firstOrNull { saved ->
                saved["path"] == "/api/panels/$sourceName" &&
                    LcncProgramConfix.fromJson(sourceName, jsonOf(saved["document"])).controls.subnet == sourceScope
            }, "Workgroups Apply subnet and Save must retain local.tasks before a named scoped run: ${jsonOf(snapshot())}")
            assertEquals(200, declaredSave["status"], jsonOf(declaredSave))
            val producer = LcncProgramConfix.fromJson(sourceName, jsonOf(declaredSave["document"]))
            val targetSave = assertNotNull(saves.firstOrNull { it["path"] == "/api/panels/$targetName" }, jsonOf(snapshot()))
            assertEquals(200, targetSave["status"], jsonOf(targetSave))
            val recipient = LcncProgramConfix.fromJson(targetName, jsonOf(targetSave["document"]))
            assertEquals(targetScope, recipient.controls.subnet)
            fun withoutSubnet(program: LcncProgram) = LcncProgramConfix.toJson(program.copy(controls = program.controls.copy(subnet = null)))
            assertEquals(withoutSubnet(initialProgram), withoutSubnet(producer), "Apply must preserve every other authored field")
            assertEquals(withoutSubnet(initialProgram), withoutSubnet(recipient), "Rename and Apply must preserve every other authored field")
            assertFalse(producer.controls.humanOversight)
            assertEquals(1, producer.controls.matingPoints.size)
            assertEquals(3, saves.size, "Exactly initial source, declared source and declared recipient saves")
            assertEquals(2, context.attachments.listAttachments("panels/").size, "Offered examples must not become stored programs")
            assertEquals(2, ledger.history(sourceName).size)
            assertEquals(1, ledger.history(targetName).size)

            assertEquals(3, runs.size, "Exactly one inline baseline and two explicit named runs; inspecting cannot rerun them")
            val source = runs[1]
            val target = runs[2]
            val sourceCommand = assertIs<Map<*, *>>(commands[1]["request"])
            val targetCommand = assertIs<Map<*, *>>(commands[2]["request"])
            assertEquals(sourceName, sourceCommand["program"])
            assertEquals(targetName, targetCommand["program"])
            assertFalse(sourceCommand.containsKey("document"))
            assertFalse(targetCommand.containsKey("document"))
            assertEquals(mapOf("evidence" to supplied), sourceCommand["inputs"])
            assertEquals(emptyMap<String, Any?>(), targetCommand["inputs"])
            assertEquals(source["receiptCid"], targetCommand["sourceReceiptCid"])
            assertEquals("local.tasks.worker", sourceCommand["subnet"])
            assertEquals("local.publish.worker", targetCommand["subnet"])
            assertEquals(supplied, assertIs<Map<*, *>>(source["returns"])["evidence"])
            for ((run, program, requested) in listOf(
                Triple(source, producer, "local.tasks.worker"), Triple(target, recipient, "local.publish.worker"))) {
                assertEquals("completed", run["status"], jsonOf(run))
                assertEquals(true, run["ok"])
                assertEquals(requested, run["subnet"])
                assertEquals(program.controls.subnet, run["claimedAtSubnet"])
                assertEquals(LcncBlackboard.cidOf(program), run["programCid"])
                assertEquals("lcnc/program/${program.name}@${LcncBlackboard.cidOf(program)}", run["workgroup"])
                assertEquals(1, calls.count { (it["claimId"] as Number).toLong() == (run["claimId"] as Number).toLong() }, "Each explicit run executes the actual literal once")
                val call = calls.single { (it["claimId"] as Number).toLong() == (run["claimId"] as Number).toLong() }
                for (key in listOf("workgroup", "programCid", "subnet")) assertEquals(run[key], call[key])
                assertEquals("constant", call["nodeId"])
                val receiptCid = ContentId(assertIs<String>(run["receiptCid"]))
                val retained = assertNotNull(context.casStore.get(receiptCid))
                assertEquals(receiptCid, ContentId.of(retained))
                val commit = reifyMap(retained)
                assertEquals("complete", commit["type"])
                val stored = assertIs<Map<*, *>>(commit["lcncRun"])
                for (key in listOf("runId", "status", "programCid", "workgroup", "claimId", "subnet", "claimedAtSubnet", "returns", "sourceReceiptCid"))
                    assertEquals(run[key], stored[key], "The actual committed command retains $key")
                val programCid = ContentId(assertIs<String>(run["programCid"]))
                assertEquals(programCid, ContentId.of(assertNotNull(context.casStore.get(programCid))))
                val ledgerCid = ContentId(assertIs<String>(run["invocationsCid"]))
                val invocationBytes = assertNotNull(context.casStore.get(ledgerCid))
                assertEquals(ledgerCid, ContentId.of(invocationBytes))
                val entries = assertIs<List<*>>(reifyMap(invocationBytes)["entries"]).map { assertIs<Map<*, *>>(it) }
                assertEquals(1, entries.count { it["nodeId"] == "constant" && it["status"] == "completed" })
                val registration = inventories.flatMap { assertIs<List<*>>(it["workgroups"]).map { row -> assertIs<Map<*, *>>(row) } }
                    .firstOrNull { it["program"] == program.name && it["programCid"] == run["programCid"] }
                assertNotNull(registration, "An actual inventory read must name the saved registration: ${jsonOf(snapshot())}")
                assertEquals(program.controls.subnet, registration["scope"])
            }
            val evidence = assertIs<Map<*, *>>(assertIs<Map<*, *>>(target["returns"])["evidence"])
            assertEquals(source["receiptCid"], target["sourceReceiptCid"])
            assertEquals(source["receiptCid"], evidence["receiptCid"])
            assertEquals(source["runId"], evidence["runId"])
            assertEquals(source["programCid"], evidence["programCid"])
            assertEquals(source["returns"], evidence["returns"])
            assertTrue(assertIs<List<*>>(target["consumed"]).any { item ->
                val row = assertIs<Map<*, *>>(item)
                row["kind"] == "lcnc-run" && row["id"] == "lcnc/run/${source["runId"]}" && row["cid"] == source["receiptCid"]
            })
            assertEquals(3, calls.size, "Receipt pollination must not replay the producer")
            val refused = commands.drop(3)
            assertTrue(refused.size <= 1 && refused.all { it["status"] == 404 && assertIs<Map<*, *>>(it["response"])["error"] == "no_workgroup" },
                "The final unrelated Requested subnet may be refused locally or by the actual route; it cannot produce a receipt")
            println("WORKGROUPS_UI_PROOF=" + jsonOf(snapshot()))
        } finally {
            withContext(NonCancellable) {
                try { scopes.drain() } finally { try { memory.close() } finally { binding.reactor.drain() } }
            }
        }
    }
}
