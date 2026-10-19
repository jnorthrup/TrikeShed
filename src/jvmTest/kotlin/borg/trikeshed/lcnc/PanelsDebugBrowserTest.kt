package borg.trikeshed.lcnc

import borg.trikeshed.lcnc.ccek.*
import borg.trikeshed.lib.*
import borg.trikeshed.litebike.JvmKanbanServer.HttpResponse
import borg.trikeshed.module.ModuleContext
import borg.trikeshed.parse.*
import borg.trikeshed.userspace.nio.platform.spi.SystemOperations
import borg.trikeshed.vm.*
import kotlinx.coroutines.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

/** Real paused module runs and production Panels; browser proofs never substitute for execution. */
class PanelsDebugBrowserTest {
    @Test
    @org.junit.jupiter.api.Timeout(value = 12, unit = java.util.concurrent.TimeUnit.MINUTES)
    fun authoredLineCommandsKeepTheOriginalRun() = runBlocking<Unit> {
        org.junit.jupiter.api.Assumptions.assumeTrue(SystemOperations.default.getenv("TRIKESHED_AUTHORED_DEBUG_UI") == "1")
        val token = java.util.UUID.randomUUID().toString()
        val calls = AtomicInteger()
        val posts = CopyOnWriteArrayList<String>()
        val bodies = CopyOnWriteArrayList<Map<String, Any?>>()
        val holds = CopyOnWriteArrayList<Map<String, Any?>>()
        val proofs = CopyOnWriteArrayList<String>()
        val failures = CopyOnWriteArrayList<String>()
        val stages = listOf("adapter", "authored", "generated", "completed")
        val actions = listOf("source-into", "source-line", "source-line", "continue")
        val binding = ccekReactorBinding(coroutineContext)
        val host = HypervisorVmHost()
        val source = "let count = 0;\nfor (let i = 0; i < 3; i++) {\n  count += 1;\n}\nreturn count;"
        val program = LcncProgram("authored-debug", s_[LcncNode("source", SubVmLegos.POLYGLOT,
            mapOf("language" to "js", "source" to source))], emptySeriesOf())
        lateinit var bound: ModuleContext
        lateinit var execution: Deferred<HttpResponse>
        lateinit var node: Map<String, Any?>
        suspend fun route(method: String, path: String, body: String = "") =
            requireNotNull(bound.routes.match(path.substringBefore('?'))?.route(method, path, body, null))
        fun commands(count: Int) {
            assertEquals(List(count) { "/api/lcnc/run/debug" }, posts.toList(), "Browser must not admit, replay, or cancel a run")
            assertEquals(count, bodies.size)
            for ((index, body) in bodies.withIndex()) {
                assertEquals(token, body["token"])
                assertEquals(node["runId"], body["runId"])
                assertEquals((holds[index]["ordinal"] as Number).toInt(), (body["ordinal"] as? Number)?.toInt())
                assertEquals(actions[index], body["action"])
                if (index in 1..2) {
                    assertEquals(if (index == 1) "authored" else "generated", body["coordinates"])
                    assertEquals(if (index == 1) 3 else 4, (body["line"] as? Number)?.toInt())
                } else {
                    assertFalse("coordinates" in body)
                    assertNull(body["line"])
                }
            }
        }
        suspend fun completed(): Map<String, Any?> {
            val response = withTimeout(5000) { execution.await() }
            assertEquals(200, response.status, response.body)
            val result = reifyMap(response.body)
            assertEquals("completed", result["status"])
            assertEquals(node["runId"], result["runId"])
            assertEquals(node["programCid"], result["programCid"])
            assertEquals(3, (assertIs<Map<*, *>>(assertIs<Map<*, *>>(result["outputs"])["source"])["value"] as? Number)?.toInt())
            assertEquals(1, calls.get())
            assertEquals(1, host.ids().size)
            assertTrue(host.ids().none { host.get(it)?.isAlive == true }, "Guest release must precede fixture drain/host.close")
            return result
        }
        suspend fun verify(proof: Map<String, Any?>) {
            val stage = assertIs<String>(proof["stage"])
            assertEquals(stages.getOrNull(proofs.size), stage, "Exactly one ordered proof per stage")
            commands(proofs.size + 1)
            assertEquals(1, calls.get())
            assertEquals(1, (proof["frozenLinks"] as? Number)?.toInt())
            assertEquals(if (stage == "completed") 1 else 0, (proof["receiptLinks"] as? Number)?.toInt())
            assertEquals(stage == "completed", proof["controlsDisabled"])
            assertEquals("Source line coordinates", proof["coordinateLabel"])
            assertEquals(listOf("authored", "generated"), proof["coordinateOptions"])
            val coordinate = if (stage in listOf("adapter", "authored")) "authored" else "generated"
            assertEquals(coordinate, proof["coordinates"])
            assertEquals(if (coordinate == "authored") "Authored source line" else "Generated source line", proof["inputLabel"])
            val status = assertIs<String>(proof["status"])
            assertTrue(status.contains(node["runId"] as String))
            if (stage == "completed") {
                val result = completed()
                val terminal = route("GET", "/api/lcnc/run/debug?token=$token&runId=${node["runId"]}")
                assertEquals(404, terminal.status, terminal.body)
                val receipt = assertIs<Map<*, *>>(reifyMap(terminal.body)["receipt"])
                assertEquals(result["runId"], receipt["runId"])
                assertEquals(result["receiptCid"], receipt["receiptCid"])
                assertTrue(status.contains("Recorded status: completed"))
            } else {
                val response = route("GET", "/api/lcnc/run/debug?token=$token&runId=${node["runId"]}")
                assertEquals(200, response.status, response.body)
                val pause = reifyMap(response.body)
                assertEquals("paused", pause["state"])
                assertEquals(node["runId"], pause["runId"])
                assertEquals(node["programCid"], pause["programCid"])
                assertTrue((pause["ordinal"] as Number).toInt() > (holds.last()["ordinal"] as Number).toInt())
                val site = assertIs<Map<*, *>>(pause["site"])
                assertEquals("source", site["kind"])
                assertEquals("source", site["nodeId"])
                val authored = assertIs<Map<*, *>>(site["authored"])
                assertEquals(2, (authored["generatedStartLine"] as? Number)?.toInt())
                assertEquals(5, (authored["lineCount"] as? Number)?.toInt())
                val view = if (coordinate == "authored") authored else site
                val line = if (stage == "adapter") 1 else if (stage == "authored") 3 else 4
                assertEquals(line.toString(), proof["inputValue"])
                assertEquals((view["lineCount"] as Number).toInt().toString(), proof["inputMax"])
                val code = assertIs<String>(proof["code"])
                assertTrue(code.contains(if (coordinate == "authored") "Authored function body" else "Generated guest source"))
                assertTrue(code.contains("count += 1;"))
                assertTrue(status.contains("Paused"))
                if (stage == "adapter") {
                    assertNull(authored["line"])
                    assertTrue(code.contains("Adapter boundary; no authored line"))
                    assertTrue(status.contains("adapter boundary"))
                } else {
                    assertEquals(3, (authored["line"] as? Number)?.toInt())
                    assertEquals(4, (site["line"] as? Number)?.toInt())
                    val breakpoint = assertIs<Map<*, *>>(view["breakpoint"])
                    assertEquals(true, breakpoint["hit"])
                    assertEquals(line, (breakpoint["requestedLine"] as? Number)?.toInt())
                    assertEquals(line, (breakpoint["resolvedLine"] as? Number)?.toInt())
                    assertTrue(code.contains("Breakpoint: requested $line → stopped at $line"))
                    assertTrue(Regex("> $line\\s+count \\+= 1;").containsMatchIn(code))
                    val locals = assertIs<List<*>>(site["frames"]).flatMap { assertIs<List<*>>(assertIs<Map<*, *>>(it)["locals"]) }
                    assertTrue(locals.any { local -> val value = assertIs<Map<*, *>>(local)
                        value["name"] == "count" && (value["value"] as? Number)?.toInt() == if (stage == "authored") 0 else 1 })
                }
                assertEquals(1, host.ids().count { host.get(it)?.isAlive == true })
                holds += pause
            }
            proofs += stage
        }
        try {
            binding.reactor.open()
            val context = vmRuntimeTestContext(this)
            val actual = SubVmLegos.polyglot(host)
            context.lcncRunners[SubVmLegos.POLYGLOT] = object : LcncNodeRunner,
                LcncServiceBinding by (actual as LcncServiceBinding) {
                override suspend fun sourceDebugging(node: LcncNode, inputs: Map<String, Any?>) = actual.sourceDebugging(node, inputs)
                override suspend fun execute(node: LcncNode, inputs: Map<String, Any?>): Map<String, Any?> {
                    calls.incrementAndGet()
                    return actual.execute(node, inputs)
                }
            }
            retainedSimulationBrowser(context, null, binding, program, executorThreads = 4,
                opened = { active ->
                    bound = active
                    execution = active.scope.async { route("POST", "/api/lcnc/run", jsonOf(mapOf(
                        "debugToken" to token, "timeoutMs" to 600000, "document" to reify(LcncProgramConfix.toJson(program))))) }
                    node = withTimeout(5000) {
                        var value: Map<String, Any?>
                        do {
                            value = reifyMap(route("GET", "/api/lcnc/run/debug?token=$token").body)
                            if (value["state"] != "paused") delay(10)
                        } while (value["state"] != "paused")
                        value
                    }
                    assertEquals("source", assertIs<Map<*, *>>(node["site"])["nodeId"])
                    assertNull(assertIs<Map<*, *>>(node["site"])["kind"])
                    assertEquals(0, calls.get())
                    assertTrue(host.ids().isEmpty())
                    holds += node
                    println("PANELS_DEBUG_QUERY=?debug=$token&run=${node["runId"]}")
                },
                requests = { method, path -> if (path.startsWith("/api/")) {
                    if (method == "POST") posts += path
                    else if (method !in listOf("GET", "HEAD")) failures += "Unexpected browser write: $method $path"
                } },
                requestBodies = { method, path, body -> if (method == "POST" && path.startsWith("/api/")) {
                    try { bodies += reifyMap(body) }
                    catch (failure: Exception) { failures += "Invalid API POST body: ${failure.message}" }
                } },
                testRoute = { method, path, body ->
                    if (method == "POST" && path == "/_test/proof") try {
                        verify(reifyMap(body))
                        HttpResponse(200, "{\"ok\":true}")
                    } catch (failure: Throwable) {
                        failures += failure.message ?: "Browser proof failed"
                        if (failure is CancellationException) throw failure
                        HttpResponse(409, jsonOf(mapOf("ok" to false, "error" to failure.message)))
                    } else null
                },
                observations = { mapOf("runId" to node["runId"], "programCid" to node["programCid"],
                    "calls" to calls.get(), "posts" to posts.toList(), "bodies" to bodies.toList(),
                    "proofs" to proofs.toList(), "failures" to failures.toList()) })
            assertEquals(stages, proofs.toList(), "Missing browser proof; drain cannot substitute for Continue")
            assertTrue(failures.isEmpty(), failures.joinToString("\n"))
            commands(4)
            completed()
        } finally {
            try { withContext(NonCancellable) { binding.reactor.drain() } }
            finally { host.close() }
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(value = 12, unit = java.util.concurrent.TimeUnit.MINUTES)
    fun pausedProgramInspectionKeepsTheObservedRun() = runBlocking<Unit> {
        org.junit.jupiter.api.Assumptions.assumeTrue(SystemOperations.default.getenv("TRIKESHED_PAUSED_SOURCE_UI") == "1")
        val token = java.util.UUID.randomUUID().toString()
        val effects = AtomicInteger()
        val reads = AtomicInteger()
        val posts = CopyOnWriteArrayList<String>()
        val inspections = CopyOnWriteArrayList<Map<String, Any?>>()
        val proofs = CopyOnWriteArrayList<Map<String, Any?>>()
        val failures = CopyOnWriteArrayList<String>()
        val receiptFault = AtomicReference<String?>(null)
        val returnedFaults = CopyOnWriteArrayList<String>()
        val binding = ccekReactorBinding(coroutineContext)
        val host = HypervisorVmHost()
        val source = "return 42;"
        val program = LcncProgram("paused-source", s_[
            LcncNode("source", SubVmLegos.POLYGLOT, mapOf("language" to "js", "source" to source)),
        ], emptySeriesOf())
        lateinit var bound: ModuleContext
        lateinit var execution: Deferred<HttpResponse>
        lateinit var pause: Map<String, Any?>
        lateinit var programCid: String
        lateinit var href: String
        suspend fun route(method: String, path: String, body: String = "") =
            requireNotNull(bound.routes.match(path.substringBefore('?'))?.route(method, path, body, null))
        fun inspectOnly() {
            assertTrue(posts.all { it == "/api/lcnc/workspace" }, "Unexpected browser API POSTs: $posts")
            assertEquals(posts.size, inspections.size, "Every POST must be a captured workspace inspection")
            assertTrue(inspections.isNotEmpty(), "The browser must inspect the retained program")
            for (request in inspections) {
                val selected = assertIs<Map<*, *>>(request["source"])
                assertEquals("cas", selected["kind"])
                assertEquals(programCid, selected["cid"])
                assertTrue(selected["syntax"] == null || selected["syntax"] == "JSON")
                assertFalse("document" in request || "action" in request, "Inspection must select retained bytes")
            }
        }
        suspend fun verify(proof: Map<String, Any?>) {
            val current = route("GET", "/api/lcnc/run/debug?token=$token&runId=${pause["runId"]}")
            assertEquals(200, current.status, current.body)
            assertEquals(pause, reifyMap(current.body), "Opening Confix must preserve the actual pause")
            assertEquals(0, effects.get(), "Inspection cannot dispatch the guest")
            assertTrue(reads.get() >= 3, "Observe repeated real browser polls before reporting proof")
            val link = assertIs<Map<*, *>>(proof["link"])
            assertEquals(1, (link["count"] as? Number)?.toInt(), "Missing or duplicated frozen-program link")
            assertEquals(href, link["href"], "Report the link's getAttribute('href')")
            assertEquals("_blank", link["target"])
            assertEquals("noopener", link["rel"])
            val confix = assertIs<Map<*, *>>(proof["confix"])
            assertEquals(0, (confix["nodes"] as? Number)?.toInt())
            assertEquals(0, (confix["wires"] as? Number)?.toInt())
            for (key in listOf("inspectionOnly", "runDisabled", "liveDisabled", "sourceVisible"))
                assertEquals(true, confix[key], key)
            assertTrue(assertIs<String>(confix["sourceText"]).contains(source), "Retained source must be visible")
            val observer = assertIs<Map<*, *>>(proof["observer"])
            assertEquals(true, observer["live"], "The original observer must remain live in its original tab")
            assertEquals(pause["runId"], observer["runId"])
            assertEquals((pause["ordinal"] as Number).toLong(), (observer["ordinal"] as? Number)?.toLong())
            // Fault only the observation transport: neither response is a real run completion.
            val terminal = assertIs<List<*>>(proof["terminal"])
            assertEquals(listOf("changed", "malformed"), terminal.map { (it as Map<*, *>)["kind"] })
            assertEquals(listOf("changed", "malformed"), returnedFaults.toList())
            for (sample in terminal) {
                val item = sample as Map<*, *>
                assertEquals(true, item["closeVisible"])
                assertEquals(true, item["controlsDisabled"])
                assertEquals(1, (item["count"] as? Number)?.toInt())
                assertEquals(href, item["href"], "Refused evidence cannot replace the observed frozen program")
                assertEquals(0, (item["receiptCount"] as? Number)?.toInt())
                assertTrue(assertIs<String>(item["error"]).startsWith("Retained evidence refused:"))
            }
            inspectOnly()
        }
        try {
            binding.reactor.open()
            val context = vmRuntimeTestContext(this)
            val actual = SubVmLegos.polyglot(host)
            context.lcncRunners[SubVmLegos.POLYGLOT] = object : LcncNodeRunner,
                LcncServiceBinding by (actual as LcncServiceBinding) {
                override suspend fun sourceDebugging(node: LcncNode, inputs: Map<String, Any?>): Boolean =
                    actual.sourceDebugging(node, inputs)
                override suspend fun execute(node: LcncNode, inputs: Map<String, Any?>): Map<String, Any?> {
                    effects.incrementAndGet()
                    return actual.execute(node, inputs)
                }
            }
            retainedSimulationBrowser(context, null, binding, program, executorThreads = 4,
                opened = { active ->
                    bound = active
                    execution = active.scope.async {
                        route("POST", "/api/lcnc/run", jsonOf(mapOf("debugToken" to token, "timeoutMs" to 600000,
                            "document" to reify(LcncProgramConfix.toJson(program)))))
                    }
                    pause = withTimeout(5000) {
                        var value: Map<String, Any?>
                        do {
                            value = reifyMap(route("GET", "/api/lcnc/run/debug?token=$token").body)
                            if (value["state"] != "paused") delay(10)
                        } while (value["state"] != "paused")
                        value
                    }
                    assertEquals("source", assertIs<Map<*, *>>(pause["site"])["nodeId"])
                    assertEquals(0, effects.get())
                    programCid = assertIs<String>(pause["programCid"])
                    href = "/panels?content=${java.net.URLEncoder.encode(programCid, "UTF-8")}&path=%5B%5D"
                    println("PANELS_DEBUG_QUERY=?debug=$token&run=${pause["runId"]}")
                    println("PANELS_DEBUG_PROGRAM_CID=$programCid")
                },
                requests = { method, path ->
                    if (method == "GET" && path == "/api/lcnc/run/debug") reads.incrementAndGet()
                    if (path.startsWith("/api/")) {
                        if (method == "POST") posts += path
                        else if (method !in listOf("GET", "HEAD")) failures += "Unexpected browser write: $method $path"
                    }
                },
                requestBodies = { method, path, body ->
                    if (method == "POST" && path == "/api/lcnc/workspace") inspections += reifyMap(body)
                },
                testRoute = { method, path, body ->
                    if (method == "POST" && path == "/_test/receipt-fault") {
                        val kind = reifyMap(body)["kind"] as? String
                        require(kind == null || kind in listOf("changed", "malformed"))
                        receiptFault.set(kind)
                        HttpResponse(200, "{}")
                    } else if (method == "GET" && path == "/api/lcnc/run/debug" && receiptFault.get() != null) {
                        val kind = requireNotNull(receiptFault.get())
                        returnedFaults += kind
                        HttpResponse(404, jsonOf(mapOf("error" to "fixture receipt fault", "receipt" to mapOf(
                            "runId" to pause["runId"], "status" to "completed", "receiptCid" to programCid,
                            "programCid" to if (kind == "changed") "sha256:" + "0".repeat(64) else "invalid-cid"))))
                    } else if (method == "POST" && path == "/_test/proof") {
                        val proof = reifyMap(body)
                        proofs += proof
                        // Preserve RED even if the browser ignores this refusal and calls /_test/finish.
                        try {
                            verify(proof)
                            HttpResponse(200, "{\"ok\":true}")
                        } catch (failure: Throwable) {
                            failures += failure.message ?: "Browser proof failed"
                            if (failure is CancellationException) throw failure
                            HttpResponse(409, jsonOf(mapOf("ok" to false, "error" to failure.message)))
                        }
                    } else null
                },
                observations = { mapOf("reads" to reads.get(), "effects" to effects.get(), "posts" to posts.toList(),
                    "inspections" to inspections.toList(), "proofs" to proofs.toList(), "failures" to failures.toList(),
                    "runId" to pause["runId"], "ordinal" to pause["ordinal"], "programCid" to programCid, "href" to href) })
            // The real module drain releases the node hold and joins its one actual guest invocation.
            val response = execution.await()
            assertEquals(200, response.status, response.body)
            val completed = reifyMap(response.body)
            assertEquals("completed", completed["status"])
            assertEquals(pause["runId"], completed["runId"])
            assertEquals(programCid, completed["programCid"])
            val outputs = assertIs<Map<*, *>>(assertIs<Map<*, *>>(completed["outputs"])["source"])
            assertEquals(42, (outputs["value"] as? Number)?.toInt())
            assertEquals(1, effects.get())
            assertTrue(host.ids().none { host.get(it)?.isAlive == true }, "Guest must be released before host.close")
            assertEquals(1, proofs.size, "A real browser proof is required")
            assertTrue(failures.isEmpty(), failures.joinToString("\n"))
            inspectOnly()
        } finally {
            try { withContext(NonCancellable) { binding.reactor.drain() } }
            finally { host.close() }
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(value = 12, unit = java.util.concurrent.TimeUnit.MINUTES)
    fun stoppedObservationRejectsLateSnapshotsAndErrors() = runBlocking<Unit> {
        org.junit.jupiter.api.Assumptions.assumeTrue(SystemOperations.default.getenv("TRIKESHED_PANELS_DEBUG_UI") == "1")
        val token = java.util.UUID.randomUUID().toString()
        val effects = AtomicInteger()
        val reads = AtomicInteger()
        val posts = CopyOnWriteArrayList<String>()
        val proofs = CopyOnWriteArrayList<Map<*, *>>()
        val gate = AtomicReference<Observation?>()
        val binding = ccekReactorBinding(coroutineContext)
        val program = LcncProgram("debug-observation", s_[LcncNode("effect", "test.effect")], emptySeriesOf())
        lateinit var bound: ModuleContext
        lateinit var execution: Deferred<HttpResponse>
        lateinit var pause: Map<*, *>
        suspend fun route(method: String, path: String, body: String = "") =
            requireNotNull(bound.routes.match(path.substringBefore('?'))?.route(method, path, body, null))
        suspend fun snapshot() = route("GET", "/api/lcnc/run/debug?token=$token")
        try {
            binding.reactor.open()
            val context = vmRuntimeTestContext(this)
            context.lcncRunners["test.effect"] = LcncNodeRunner { _, _ ->
                effects.incrementAndGet(); mapOf("value" to 42)
            }
            retainedSimulationBrowser(context, null, binding, program,
                executorThreads = 8,
                opened = { active ->
                    bound = active
                    execution = active.scope.async {
                        route("POST", "/api/lcnc/run", jsonOf(mapOf("debugToken" to token, "timeoutMs" to 600000,
                            "document" to reify(LcncProgramConfix.toJson(program)))))
                    }
                    pause = withTimeout(5000) {
                        var value: Map<*, *>
                        do { value = reify(snapshot().body) as Map<*, *>; if (value["state"] != "paused") delay(10) }
                        while (value["state"] != "paused")
                        value
                    }
                    println("PANELS_DEBUG_QUERY=?debug=$token&run=${pause["runId"]}")
                },
                requests = { method, path -> if (method == "POST" && path.startsWith("/api/")) posts += path },
                testRoute = { method, path, body -> when {
                    method == "POST" && path == "/_test/hold" -> {
                        val mode = (reify(body) as Map<*, *>)["mode"] as String
                        require(mode in setOf("snapshot", "http", "json"))
                        check(gate.get()?.release?.isCompleted != false)
                        gate.set(Observation(mode))
                        HttpResponse(200, "{}")
                    }
                    method == "POST" && path == "/_test/release" -> {
                        requireNotNull(gate.get()).release.complete(Unit)
                        HttpResponse(200, "{}")
                    }
                    method == "POST" && path == "/_test/proof" -> {
                        val proof = reify(body) as Map<*, *>
                        val observed = requireNotNull(gate.get())
                        check(observed.returned.isCompleted)
                        assertEquals(pause, reify(snapshot().body), "Stopping observation must leave the actual pause unchanged")
                        proofs += proof + mapOf("mode" to observed.mode, "effects" to effects.get(), "posts" to posts.size)
                        HttpResponse(200, "{}")
                    }
                    method == "GET" && path == "/api/lcnc/run/debug" -> {
                        reads.incrementAndGet()
                        val response = route(method, "$path?token=$token&runId=${pause["runId"]}")
                        val observed = gate.get()
                        if (observed != null && !observed.release.isCompleted) {
                            observed.held.complete(Unit)
                            observed.release.await()
                            observed.returned.complete(Unit)
                            when (observed.mode) {
                                "http" -> HttpResponse(503, "{\"error\":\"held observation unavailable\"}")
                                "json" -> HttpResponse(200, "{")
                                else -> response
                            }
                        } else response
                    }
                    else -> null
                } },
                observations = { mapOf("reads" to reads.get(), "effects" to effects.get(), "posts" to posts.toList(),
                    "held" to (gate.get()?.held?.isCompleted == true),
                    "returned" to (gate.get()?.returned?.isCompleted == true), "proofs" to proofs.toList()) },
                cleanup = { gate.get()?.release?.complete(Unit) })
            assertEquals(listOf("snapshot", "http", "json"), proofs.map { it["mode"] })
            for (proof in proofs) {
                assertEquals(proof["before"], proof["after"], "Late ${proof["mode"]} response repainted a stopped debugger")
                assertEquals(true, proof["disabled"])
                assertEquals(0, proof["effects"])
                assertEquals(0, proof["posts"], "Observation never admits, advances, or cancels a run")
            }
            // The fixture module's drain releases its own pause and joins the one admitted effect.
            assertEquals("completed", (reify(execution.await().body) as Map<*, *>)["status"])
            assertEquals(1, effects.get())
        } finally {
            gate.get()?.release?.complete(Unit)
            withContext(NonCancellable) { binding.reactor.drain() }
        }
    }

    private class Observation(val mode: String) {
        val held = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val returned = CompletableDeferred<Unit>()
    }
}
