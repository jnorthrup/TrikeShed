package borg.trikeshed.lcnc

import borg.trikeshed.lcnc.ccek.*
import borg.trikeshed.job.*
import borg.trikeshed.lib.*
import borg.trikeshed.litebike.JvmKanbanServer.HttpResponse
import borg.trikeshed.module.ModuleContext
import borg.trikeshed.parse.*
import borg.trikeshed.userspace.nio.platform.spi.SystemOperations
import kotlinx.coroutines.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

/** Real case queue/CAS/WAL and production Panels, with a controlled model transport only. */
class CaseBrowserTest {
    @Test
    @org.junit.jupiter.api.Timeout(value = 12, unit = java.util.concurrent.TimeUnit.MINUTES)
    fun explicitCaseCommandsRetainLostResponseIdentityAndRequireCurrentReadback() = runBlocking<Unit> {
        org.junit.jupiter.api.Assumptions.assumeTrue(SystemOperations.default.getenv("TRIKESHED_CASE_UI") == "1")
        val binding = ccekReactorBinding(coroutineContext)
        val requests = CopyOnWriteArrayList<String>()
        val responses = CopyOnWriteArrayList<Map<String, Any?>>()
        val seats = CopyOnWriteArrayList<String>()
        val legacyRecords = CopyOnWriteArrayList<String>()
        val inspections = CopyOnWriteArrayList<Map<String, Any?>>()
        val mutations = CopyOnWriteArrayList<String>()
        lateinit var bound: ModuleContext
        val programName = "preset-tribunal-case-v2"
        val program = LcncProgramConfix.fromJson(programName, LcncPresets.all().getValue(programName))
        var lost = false
        try {
            binding.reactor.open()
            val context = vmRuntimeTestContext(this)
            context.lcncRunners.putAll(PureNodes.registry { 1L })
            context.lcncRunners.putAll(TribunalNodes.registry(TribunalDialog { node, _, prompt ->
                seats += node.id
                (if (node.id == "n4")
                    """{"disposition":"granted","needsClarification":false,"clarificationQuestion":"","mistrial":false}"""
                else "${node.id}:$prompt") to "fixture/${node.id}"
            }, tribunalRecord(context.casStore) { cid -> legacyRecords += cid.value; cid.value }))
            retainedSimulationBrowser(context, null, binding,
                LcncProgram("case-canvas", s_[LcncNode("untouched", "json.value", mapOf("value" to "7"))], 0 j { error("no wires") }),
                executorThreads = 4,
                opened = { bound = it },
                requestBodies = { method, path, body ->
                    if (method == "POST" && path == "/api/lcnc/workspace") inspections += reifyMap(body)
                    else if (method !in setOf("GET", "HEAD") && path != "/api/lcnc/rdf/align") mutations += "$method $path"
                },
                testRoute = { method, path, body ->
                    if (method == "POST" && path == "/api/lcnc/run") {
                        requests += body
                        val response = bound.routes.match(path)!!.route(method, path, body, null)!!
                        assertEquals(200, response.status, response.body)
                        val result = reifyMap(response.body)
                        responses += result
                        val command = reifyMap(body)["case"] as Map<*, *>
                        if (command["requestedTarget"] == "argue" && !lost) {
                            lost = true
                            HttpResponse(500, jsonOf(result + mapOf("ok" to false, "status" to "failed",
                                "error" to "fixture response failure after the accepted argument")))
                        } else response
                    } else null
                },
                observations = { mapOf("requests" to requests.map(::reifyMap), "seats" to seats.toList(),
                    "legacyRecords" to legacyRecords.toList(), "responses" to responses.toList(), "inspections" to inspections.toList(),
                    "mutations" to mutations.toList()) })
            val commands = requests.map { reifyMap(it)["case"] as Map<*, *> }
            assertEquals(listOf("initialize", "read", "transition", "read", "transition", "read",
                "transition", "read", "transition", "read", "transition", "read"), commands.map { it["intent"] })
            assertEquals(listOf("argue", "argue", "rebut", "deliberate", "rule"), commands.mapNotNull { it["requestedTarget"] })
            assertEquals(requests[2], requests[4], "Read case must preserve the exact lost-response command")
            assertEquals(responses[2]["case"], responses[4]["case"], "Exact retry is the original case result")
            for (index in listOf(2, 6, 8, 10)) {
                val head = responses[index - 1]["case"] as Map<*, *>
                assertEquals(head["revision"], commands[index]["expectedRevision"])
                assertEquals(head["stateCid"], commands[index]["priorStateCid"])
            }
            assertTrue(requests.all { reifyMap(it)["expectProgramCid"] == LcncBlackboard.cidOf(program) })
            assertEquals(listOf("n2", "n3", "n4"), seats.toList())
            assertTrue(legacyRecords.isEmpty(), "Case settlement must replace the ambient legacy record callback")
            val final = responses.last()["case"] as Map<*, *>
            assertEquals("settled", final["phase"])
            assertEquals("closed", final["lifecycle"])
            val terminal = responses[10]
            val ledger = reifyMap(assertNotNull(context.casStore.get(ContentId(terminal["invocationsCid"] as String))))
            val entries = assertIs<List<*>>(ledger["entries"]).map { assertIs<Map<*, *>>(it) }
            val record = entries.single { it["type"] == "tribunal.record" }
            assertEquals("completed", record["status"])
            assertEquals(listOf("case.rule"), record["path"])
            assertFalse(entries.any { it["type"] in setOf("mux.chat", "legal.ingest", "legal.evidence") })
            val report = assertIs<Map<*, *>>(assertIs<Map<*, *>>(terminal["returns"])["report"])
            assertEquals(final["terminalReceiptCid"], report["recorded"])
            val snapshot = CanonicalCbor.decodeJobSnapshot(assertNotNull(context.casStore.get(ContentId(report["recorded"] as String))))
            assertEquals("closed", snapshot.lifecycle)
            assertEquals(final["stateCid"], snapshot.stateCid)
            assertTrue(inspections.any { (it["source"] as? Map<*, *>)?.get("cid") == final["stateCid"] },
                "The retained case state must be inspected through Confix")
            assertTrue(mutations.isEmpty(), "Case controls must not save or execute the canvas: $mutations")
            println("CASE_UI_PROOF=" + jsonOf(mapOf("commands" to commands, "seats" to seats.toList(),
                "recordInvocation" to record, "terminalReport" to report,
                "legacyRecords" to legacyRecords.toList(), "final" to final, "mutations" to mutations.toList())))
        } finally { withContext(NonCancellable) { binding.reactor.drain() } }
    }


    @Test
    @org.junit.jupiter.api.Timeout(value = 12, unit = java.util.concurrent.TimeUnit.MINUTES)
    fun frozenCaseContinuesAfterNamedDriftAndDeletionWithoutRepublishing() = runBlocking<Unit> {
        org.junit.jupiter.api.Assumptions.assumeTrue(SystemOperations.default.getenv("TRIKESHED_CASE_UI") == "1")
        val binding = ccekReactorBinding(coroutineContext)
        val requests = CopyOnWriteArrayList<String>()
        val responses = CopyOnWriteArrayList<Map<String, Any?>>()
        val seats = CopyOnWriteArrayList<Map<String, Any?>>()
        val changes = CopyOnWriteArrayList<Map<String, Any?>>()
        val mutations = CopyOnWriteArrayList<String>()
        val contentReads = CopyOnWriteArrayList<String>()
        val legacyRecords = CopyOnWriteArrayList<String>()
        val programName = "browser-frozen-case"
        val caseId = "frozen-browser-matter"
        val original = LcncProgramConfix.fromJson(programName,
            LcncPresets.all().getValue("preset-tribunal-case-v2"))
        val changed = original.copy(view = LcncView(17.0, 23.0, 0.75))
        val originalCid = LcncBlackboard.cidOf(original)
        val changedCid = LcncBlackboard.cidOf(changed)
        assertNotEquals(originalCid, changedCid)
        LcncCaseProgram.validate(original)
        LcncCaseProgram.validate(changed)
        val named = AtomicReference<LcncProgram?>(original)
        lateinit var bound: ModuleContext
        try {
            binding.reactor.open()
            val context = vmRuntimeTestContext(this)
            context.lcncRunners.putAll(PureNodes.registry { 1L })
            context.lcncRunners.putAll(TribunalNodes.registry(TribunalDialog { node, _, prompt ->
                val invocation = LcncWorkgroupKey.require()
                seats += mapOf("nodeId" to node.id, "prompt" to prompt,
                    "programCid" to invocation.b.b.value, "workgroup" to invocation.a.name,
                    "claimId" to invocation.b.a.claimId)
                assertEquals(originalCid, invocation.b.b.value,
                    "Actual counsel must execute the retained program, not the changed named definition")
                "${node.id}:$prompt" to "fixture/${node.id}"
            }, tribunalRecord(context.casStore) { cid -> legacyRecords += cid.value; cid.value }))
            retainedSimulationBrowser(context, null, binding,
                LcncProgram("frozen-case-canvas", s_[LcncNode("untouched", "json.value", mapOf("value" to "7"))],
                    0 j { error("no wires") }),
                executorThreads = 4,
                programLoader = { name -> if (name == programName) named.get() else context.programLoader(name) },
                opened = {
                    bound = it
                    assertSame(original, bound.programLoader(programName))
                    bound.blackboard.put(LcncBlackboard.programKey(programName),
                        LcncBlackboard.programEntry(programName, original, LcncContracts.all().associateBy { c -> c.type }),
                        "case-frozen-fixture")
                },
                requestBodies = { method, path, _ ->
                    if (method == "GET" && path == "/api/lcnc/content") contentReads += path
                    else if (method !in setOf("GET", "HEAD") &&
                        path !in setOf("/api/lcnc/workspace", "/api/lcnc/rdf/align")) mutations += "$method $path"
                },
                testRoute = { method, path, body ->
                    when {
                        method == "POST" && path == "/_test/name-change" -> {
                            val operation = reifyMap(body)["operation"]
                            when (operation) {
                                "drift" -> {
                                    check(changes.isEmpty()) { "Drift must be the first source change" }
                                    check(requests.size == 2 && responses.size == 2 && seats.isEmpty()) {
                                        "Drift only after explicit initialize/read, before counsel"
                                    }
                                    check(responses.map { (it["case"] as Map<*, *>)["programCid"] }.all { it == originalCid })
                                    named.set(changed)
                                    bound.blackboard.put(LcncBlackboard.programKey(programName),
                                        LcncBlackboard.programEntry(programName, changed, LcncContracts.all().associateBy { it.type }),
                                        "case-frozen-fixture")
                                }
                                "delete" -> {
                                    check(changes.map { it["operation"] } == listOf("drift")) { "Deletion must follow drift" }
                                    check(seats.map { it["nodeId"] } == listOf("n2")) { "Delete only after actual retained argument completion" }
                                    check(requests.size == 5 && responses.size == 5) { "Delete only after retained read/argue" }
                                    val completed = responses.last()["case"] as Map<*, *>
                                    check(completed["lane"] == "argue" && completed["phase"] == "completed" &&
                                        completed["programCid"] == originalCid)
                                    named.set(null)
                                    bound.blackboard.remove(LcncBlackboard.programKey(programName))
                                }
                                else -> error("Use operation drift or delete")
                            }
                            val resolved = bound.programLoader(programName)
                            assertSame(if (operation == "drift") changed else null, resolved)
                            // This goes through the REAL named route/loader. A stale-CID read must
                            // fail before queueing, rather than a presentation-only catalog change.
                            val probe = jsonOf(mapOf("program" to programName, "expectProgramCid" to originalCid,
                                "case" to mapOf("intent" to "read", "caseId" to caseId)))
                            val result = bound.routes.match("/api/lcnc/run")!!.route("POST", "/api/lcnc/run", probe, null)!!
                            assertEquals(if (operation == "drift") 409 else 404, result.status, result.body)
                            val observation = mapOf("operation" to operation, "loaderCid" to resolved?.let(LcncBlackboard::cidOf),
                                "namedProbeStatus" to result.status, "namedProbe" to reifyMap(result.body),
                                "seats" to seats.toList(), "requests" to requests.size)
                            changes += observation
                            HttpResponse(200, jsonOf(observation))
                        }
                        method == "POST" && path == "/api/lcnc/run" -> {
                            requests += body
                            val result = bound.routes.match(path)!!.route(method, path, body, null)!!
                            assertEquals(200, result.status, result.body)
                            responses += reifyMap(result.body)
                            result
                        }
                        else -> null
                    }
                },
                observations = { mapOf("requests" to requests.map(::reifyMap), "responses" to responses.toList(),
                    "seats" to seats.toList(), "changes" to changes.toList(), "contentReads" to contentReads.size,
                    "legacyRecords" to legacyRecords.toList(), "mutations" to mutations.toList(),
                    "originalCid" to originalCid, "changedCid" to changedCid,
                    "namedCid" to named.get()?.let(LcncBlackboard::cidOf)) })
            // On the named-only baseline, prove initialize/read and REAL loader drift before
            // the missing counsel effect. Missing new UI controls alone are not the RED oracle.
            assertTrue(requests.size >= 3 && responses.size >= 3, "Complete initialize/read and post-drift named read first")
            assertEquals(listOf("initialize", "read", "read"), requests.take(3).map { reifyMap(it)["case"] as Map<*, *> }
                .map { it["intent"] })
            assertEquals("drift", changes.firstOrNull()?.get("operation"))
            assertEquals(409, changes.first()["namedProbeStatus"])
            assertEquals(changedCid, reifyMap(requests[2])["expectProgramCid"])
            val initialized = assertIs<Map<*, *>>(responses[0]["case"])
            val observed = assertIs<Map<*, *>>(responses[2]["case"])
            for (key in listOf("programCid", "revision", "stateCid", "snapshotCid"))
                assertEquals(initialized[key], observed[key], "Post-drift named read must expose the original case")
            assertTrue(seats.any { it["nodeId"] == "n2" },
                "Retained continuation must execute actual argument after named drift; observations=" +
                    jsonOf(mapOf("changes" to changes.toList(), "responses" to responses.toList())))
            assertEquals(listOf("n2", "n3"), seats.map { it["nodeId"] }, "No model may run while loading or reading")
            assertEquals(listOf("drift", "delete"), changes.map { it["operation"] })
            assertEquals(listOf(409, 404), changes.map { it["namedProbeStatus"] })
            assertNull(named.get(), "The fixture's actual named loader must stay deleted")
            assertNull(bound.blackboard.get(LcncBlackboard.programKey(programName)), "Continuation must not republish the name")
            assertTrue(contentReads.isNotEmpty(), "Explicit frozen loading must read actual retained content")
            assertTrue(legacyRecords.isEmpty())
            assertTrue(mutations.isEmpty(), "Frozen continuation must not save or execute the canvas: $mutations")
            val sent = requests.map(::reifyMap)
            val commands = sent.map { assertIs<Map<*, *>>(it["case"]) }
            assertEquals(listOf("initialize", "read", "read", "read", "transition", "read", "transition", "read"),
                commands.map { it["intent"] })
            assertEquals(listOf("argue", "rebut"), commands.mapNotNull { it["requestedTarget"] })
            assertEquals(changedCid, sent[2]["expectProgramCid"], "Named read must observe actual source drift")
            for (index in 0..2) assertEquals(programName, sent[index]["program"])
            for (index in 3..7) {
                assertFalse(sent[index].containsKey("program"), "Retained commands must bypass named lookup")
                assertEquals(programName, sent[index]["name"], "The prior workgroup supplies its inline label")
                assertEquals(originalCid, sent[index]["expectProgramCid"])
                val submitted = LcncProgramConfix.fromJson(programName, jsonOf(sent[index]["document"]))
                assertEquals(originalCid, LcncBlackboard.cidOf(submitted), "The document must preserve exact retained program bytes")
            }
            for (index in listOf(4, 6)) {
                val read = assertIs<Map<*, *>>(responses[index - 1]["case"])
                assertEquals(read["revision"], commands[index]["expectedRevision"])
                assertEquals(read["stateCid"], commands[index]["priorStateCid"])
            }
            val first = assertIs<Map<*, *>>(responses[0]["case"])
            for (index in listOf(1, 2, 3)) {
                val read = assertIs<Map<*, *>>(responses[index]["case"])
                for (key in listOf("programCid", "revision", "stateCid", "snapshotCid")) assertEquals(first[key], read[key])
            }
            val completed = assertIs<Map<*, *>>(responses.last()["case"])
            assertEquals(originalCid, completed["programCid"])
            assertEquals("rebut", completed["lane"])
            assertEquals("completed", completed["phase"])
            assertEquals("active", completed["lifecycle"])
            assertEquals(7L, (completed["revision"] as Number).toLong())
            println("CASE_FROZEN_UI_PROOF=" + jsonOf(mapOf("requests" to sent, "responses" to responses.toList(),
                "seats" to seats.toList(), "changes" to changes.toList(), "contentReads" to contentReads.size,
                "originalCid" to originalCid, "changedCid" to changedCid, "final" to completed,
                "mutations" to mutations.toList(), "legacyRecords" to legacyRecords.toList())))
        } finally { withContext(NonCancellable) { binding.reactor.drain() } }
    }
}
