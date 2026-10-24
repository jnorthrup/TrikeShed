package borg.trikeshed.lcnc

import borg.trikeshed.lcnc.ccek.*
import borg.trikeshed.forge.server.*
import borg.trikeshed.job.ContentId
import borg.trikeshed.memory.*
import borg.trikeshed.modelmux.BrainClient
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.agent.keymux.KeyMux
import borg.trikeshed.agent.modelmux.ModelMux
import borg.trikeshed.lib.*
import borg.trikeshed.litebike.JvmKanbanServer.HttpResponse
import borg.trikeshed.parse.*
import borg.trikeshed.userspace.nio.platform.spi.SystemOperations
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

/** Optional real-browser checks: served production JS, real module routes, explicit browser proof. */
class PanelsOwnerBrowserTest {
    /** Existing DOM/HTTP APIs only: this can produce behavioral RED before PanelsElement exists. */
    @Test
    @org.junit.jupiter.api.Timeout(value = 12, unit = java.util.concurrent.TimeUnit.MINUTES)
    fun heldFacetReadsDelayTheProgramPostUntilTheirRelease() = runBlocking<Unit> {
        org.junit.jupiter.api.Assumptions.assumeTrue(SystemOperations.default.getenv("TRIKESHED_PANELS_OWNER_RED_UI") == "1")
        val controls = Controls()
        val posts = CopyOnWriteArrayList<Map<*, *>>()
        val binding = ccekReactorBinding(coroutineContext)
        try {
            binding.reactor.open()
            val context = vmRuntimeTestContext(this)
            context.lcncRunners.putAll(PureNodes.registry { 1L })
            retainedSimulationBrowser(context, null, binding, pulseProgram(),
                executorThreads = 8,
                requestBodies = { method, path, body ->
                    if (method == "POST" && path == "/api/lcnc/run") {
                        val request = reify(body) as Map<*, *>
                        if ("document" in request) posts += request
                    }
                },
                testRoute = { method, path, body -> controls.route(method, path, body, posts.size) },
                cleanup = controls::releaseAll,
                observations = { mapOf("posts" to posts.toList(), "workspace" to controls.workspace(), "held" to controls.held()) })
            controls.assertBrowser()
            val barrier = controls.proof("barrier")
            assertEquals(3, barrier.held["barrier"], "The first three actual facet requests must remain held")
            assertEquals(17.0, (barrier.body["firstValue"] as Number).toDouble())
            assertEquals(1, barrier.posts, "The slider POST entered while all three actual facet reads were held")
            assertEquals(2, posts.size, "The accepted original gesture must finish once the real reads release")
            val pulse = assertIs<Map<*, *>>(posts[1]["pulse"])
            assertEquals(17.0, (assertIs<Map<*, *>>(pulse["outputs"])["value"] as Number).toDouble())
            assertEquals(true, controls.proof("released").body["value17"])
        } finally { withContext(NonCancellable) { controls.releaseAll(); binding.reactor.drain() } }
    }

    @Test
    @org.junit.jupiter.api.Timeout(value = 12, unit = java.util.concurrent.TimeUnit.MINUTES)
    fun sharedPicklistsJoinReadCleanupAndCannotFillARemovedSelect() = runBlocking<Unit> {
        org.junit.jupiter.api.Assumptions.assumeTrue(SystemOperations.default.getenv("TRIKESHED_PANELS_OWNER_LOOKUP_UI") == "1")
        val controls = Controls()
        val calls = AtomicInteger()
        val returned = AtomicInteger()
        val cleaning = AtomicInteger()
        val cleaned = AtomicInteger()
        val columns = ConcurrentHashMap.newKeySet<String>()
        val program = LcncProgram("panels-owner-lookup", s_[
            LcncNode("kept", "kanban.move", x = 0.0, y = 0.0),
            LcncNode("removed", "kanban.move", x = 400.0, y = 0.0),
        ], emptySeriesOf())
        val binding = ccekReactorBinding(coroutineContext)
        try {
            binding.reactor.open()
            val context = vmRuntimeTestContext(this)
            context.lcncRunners.putAll(PureNodes.registry { 1L })
            retainedSimulationBrowser(context, null, binding, program,
                executorThreads = 8,
                opened = { bound ->
                    // Default run must enter this execute before the real module-owned source.
                    val actual = bound.lcncRunners.getValue("board.get")
                    bound.lcncRunners["board.get"] = object : LcncNodeRunner {
                        override val isPure: Boolean get() = actual.isPure
                        override fun outputSources(node: LcncNode, inputs: Map<String, Any?>): Map<String, String> =
                            actual.outputSources(node, inputs)
                        override fun retainedOutputs(node: LcncNode, inputs: Map<String, Any?>,
                                                     retainedInputs: Map<String, Any?>, outputs: Map<String, Any?>): Map<String, Any?> =
                            actual.retainedOutputs(node, inputs, retainedInputs, outputs)
                        override fun retainedOrigins(node: LcncNode, inputs: Map<String, Any?>,
                                                     inputOrigins: Map<String, LcncBindingOrigins>, outputs: Map<String, Any?>,
                                                     terms: LcncBindingTerms): Map<String, LcncBindingOrigins> =
                            actual.retainedOrigins(node, inputs, inputOrigins, outputs, terms)
                        override suspend fun sourceDebugging(node: LcncNode, inputs: Map<String, Any?>): Boolean =
                            actual.sourceDebugging(node, inputs)
                        override suspend fun execute(node: LcncNode, inputs: Map<String, Any?>): Map<String, Any?> {
                            calls.incrementAndGet()
                            try {
                                val result = actual.execute(node, inputs)
                                val json = result["json"] as Map<*, *>
                                (json["columns"] as List<*>).forEach { columns += (it as Map<*, *>)["id"] as String }
                                returned.incrementAndGet()
                                controls.await("read")
                                return result
                            } finally {
                                withContext(NonCancellable) {
                                    cleaning.incrementAndGet()
                                    controls.await("cleanup")
                                    cleaned.incrementAndGet()
                                }
                            }
                        }
                    }
                },
                testRoute = { method, path, body -> controls.route(method, path, body, 0) },
                cleanup = controls::releaseAll,
                observations = { mapOf("calls" to calls.get(), "returned" to returned.get(),
                    "cleaning" to cleaning.get(), "cleaned" to cleaned.get(), "columns" to columns.toList()) })
            controls.assertBrowser()
            assertEquals(2, calls.get(), "Four selects share exactly two resolved optsFrom keys")
            assertEquals(2, returned.get())
            assertEquals(2, cleaning.get())
            assertEquals(2, cleaned.get(), "Close must wait for the actual source's cleanup")
            val proof = controls.proof("lookup").body
            assertEquals(true, proof["closePendingAtRead"])
            assertEquals(true, proof["closePendingAtCleanup"])
            assertEquals(true, proof["closedAfterCleanup"])
            assertEquals(proof["removedBefore"], proof["removedAfter"], "Detached select must never receive late options")
            val kept = assertIs<List<*>>(proof["keptAfter"])
            assertTrue(columns.isNotEmpty())
            assertTrue(columns.all { it in kept }, "The still-connected select proves the real source was consumed")
        } finally { withContext(NonCancellable) { controls.releaseAll(); binding.reactor.drain() } }
    }

    @Test
    @org.junit.jupiter.api.Timeout(value = 12, unit = java.util.concurrent.TimeUnit.MINUTES)
    fun queuedPulsesKeepTheOriginalGestureAndRefuseStopOrDefinitionDrift() = runBlocking<Unit> {
        org.junit.jupiter.api.Assumptions.assumeTrue(SystemOperations.default.getenv("TRIKESHED_PANELS_OWNER_PULSE_UI") == "1")
        val controls = Controls()
        val posts = CopyOnWriteArrayList<Map<*, *>>()
        val lookups = AtomicInteger()
        val binding = ccekReactorBinding(coroutineContext)
        try {
            binding.reactor.open()
            val context = vmRuntimeTestContext(this)
            context.lcncRunners.putAll(PureNodes.registry { 1L })
            retainedSimulationBrowser(context, null, binding, pulseProgram(),
                executorThreads = 8,
                requestBodies = { method, path, body ->
                    if (method == "POST" && path == "/api/lcnc/run") {
                        val request = reify(body) as Map<*, *>
                        if ("document" in request) posts += request
                        if (request["type"] == "board.get") lookups.incrementAndGet()
                    }
                },
                testRoute = { method, path, body -> controls.route(method, path, body, posts.size) },
                cleanup = controls::releaseAll,
                observations = { mapOf("posts" to posts.toList(), "lookups" to lookups.get(), "workspace" to controls.workspace(), "held" to controls.held()) })
            controls.assertBrowser()
            assertEquals(2, lookups.get(), "Second matching node must fill from the real resolved cache without another request")
            val cached = controls.proof("cached")
            assertEquals(0, cached.posts, "The cached-fill control precedes the full Run")
            assertEquals(cached.body["firstValues"], cached.body["secondValues"])
            assertTrue(assertIs<List<*>>(cached.body["secondValues"]).size > 1)
            assertEquals(true, cached.body["connected"])
            assertEquals(true, cached.body["noAdditionalRpc"])
            assertEquals(2, posts.size, "Initial Run and original value17; busy, stopped and changed gestures cannot dispatch")
            assertTrue("pulse" !in posts[0])
            val pulse = assertIs<Map<*, *>>(posts[1]["pulse"])
            assertEquals("slider", pulse["nodeId"])
            assertEquals(17.0, (assertIs<Map<*, *>>(pulse["outputs"])["value"] as Number).toDouble())
            assertIs<String>(posts[1]["baseReceiptCid"])
            for ((checkpoint, phase) in listOf("queued" to "original", "stop-queued" to "stop", "definition-queued" to "definition")) {
                assertEquals(3, controls.proof(checkpoint).workspace[phase], "The actual three worker requests must be held")
            }
            assertEquals(1, controls.proof("queued").posts)
            assertEquals(2, controls.proof("original").posts)
            assertEquals(2, controls.proof("stopped").posts)
            assertEquals(2, controls.proof("changed").posts)
            assertEquals(true, controls.proof("queued").body["busyRefused"])
            assertEquals(true, controls.proof("original").body["value17"])
            assertEquals(true, controls.proof("stopped").body["refusedBeforeRpc"])
            assertEquals(true, controls.proof("changed").body["refusedBeforeRpc"])
        } finally { withContext(NonCancellable) { controls.releaseAll(); binding.reactor.drain() } }
    }

    @Test
    @org.junit.jupiter.api.Timeout(value = 12, unit = java.util.concurrent.TimeUnit.MINUTES)
    fun fullPageAdmissionRefusesAPulseAndDrainsEveryAcceptedRefresh() = runBlocking<Unit> {
        org.junit.jupiter.api.Assumptions.assumeTrue(SystemOperations.default.getenv("TRIKESHED_PANELS_OWNER_BOUND_UI") == "1")
        val controls = Controls()
        val posts = CopyOnWriteArrayList<Map<*, *>>()
        val binding = ccekReactorBinding(coroutineContext)
        try {
            binding.reactor.open()
            val context = vmRuntimeTestContext(this)
            context.lcncRunners.putAll(PureNodes.registry { 1L })
            retainedSimulationBrowser(context, null, binding, pulseProgram(),
                executorThreads = 8,
                requestBodies = { method, path, body ->
                    if (method == "POST" && path == "/api/lcnc/run") {
                        val request = reify(body) as Map<*, *>
                        if ("document" in request) posts += request
                    }
                },
                testRoute = { method, path, body -> controls.route(method, path, body, posts.size) },
                cleanup = controls::releaseAll,
                observations = { mapOf("posts" to posts.toList(), "workspace" to controls.workspace(), "held" to controls.held()) })
            controls.assertBrowser()
            assertEquals(1, posts.size, "The pulse refused at the full page quota must not appear later")
            assertEquals(64, controls.workspaceCount("bounded"),
                "Three held Refresh actions plus admitted queued Refresh actions, each performing the real GET")
            val held = controls.proof("full")
            assertEquals(1, held.posts)
            assertEquals(true, held.body["capacityRefused"])
            assertEquals(true, held.body["closePending"])
            val closed = controls.proof("bounded")
            assertEquals(1, closed.posts)
            assertEquals(true, closed.body["closed"])
        } finally { withContext(NonCancellable) { controls.releaseAll(); binding.reactor.drain() } }
    }

    @Test
    @org.junit.jupiter.api.Timeout(value = 12, unit = java.util.concurrent.TimeUnit.MINUTES)
    fun aHeldProgramReceiptCannotRemountClosedPagePresentation() = runBlocking<Unit> {
        org.junit.jupiter.api.Assumptions.assumeTrue(SystemOperations.default.getenv("TRIKESHED_PANELS_OWNER_CLOSE_UI") == "1")
        val controls = Controls()
        val posts = CopyOnWriteArrayList<Map<*, *>>()
        val entered = AtomicInteger()
        val completed = AtomicReference<Map<*, *>?>()
        val binding = ccekReactorBinding(coroutineContext)
        try {
            binding.reactor.open()
            val context = vmRuntimeTestContext(this)
            context.lcncRunners.putAll(PureNodes.registry { 1L })
            retainedSimulationBrowser(context, null, binding, pulseProgram(),
                executorThreads = 8,
                requestBodies = { method, path, body ->
                    if (method == "POST" && path == "/api/lcnc/run") {
                        val request = reify(body) as Map<*, *>
                        if ("document" in request) posts += request
                    }
                },
                testRoute = { method, path, body ->
                    if (method == "POST" && path == "/api/lcnc/run" && "document" in (reify(body) as Map<*, *>)) {
                        entered.incrementAndGet()
                        controls.await("program-response")
                    }
                    controls.route(method, path, body, posts.size)
                },
                cleanup = controls::releaseAll,
                observations = {
                    val runs = context.blackboard.keys().filter { it.startsWith("lcnc/run/") }
                        .mapNotNull { context.blackboard.get(it) as? Map<*, *> }
                    runs.singleOrNull { it["status"] == "completed" }?.let(completed::set)
                    mapOf("posts" to posts.toList(), "entered" to entered.get(), "runs" to runs)
                })
            controls.assertBrowser()
            assertEquals(1, entered.get())
            assertEquals(1, posts.size, "Closing the page cannot drop or repeat its admitted transport")
            val receipt = assertNotNull(completed.get(), "The browser must observe the real completed run before finishing")
            assertEquals(true, receipt["ok"])
            assertIs<String>(receipt["receiptCid"])
            val before = controls.proof("closing").body
            assertEquals(true, before["closePending"])
            assertEquals(0.0, (before["results"] as Number).toDouble())
            assertEquals(0.0, (before["facets"] as Number).toDouble())
            val after = controls.proof("closed").body
            assertEquals(true, after["closed"])
            assertEquals(true, after["completedReceipt"])
            assertEquals(before["results"], after["results"], "An admitted result cannot attach output DOM after close")
            assertEquals(before["facets"], after["facets"], "A completed run cannot reopen the released facets drawer")
        } finally { withContext(NonCancellable) { controls.releaseAll(); binding.reactor.drain() } }
    }

    @Test
    @org.junit.jupiter.api.Timeout(value = 12, unit = java.util.concurrent.TimeUnit.MINUTES)
    fun activeNodeAndCameraGesturesStopWhileARealReadDrains() = runBlocking<Unit> {
        org.junit.jupiter.api.Assumptions.assumeTrue(SystemOperations.default.getenv("TRIKESHED_PANELS_GESTURE_CLOSE_UI") == "1")
        val controls = Controls()
        val posts = CopyOnWriteArrayList<Map<*, *>>()
        val program = LcncProgram("panels-gesture-close", s_[
            LcncNode("drag", "text.value", mapOf("value" to "drag"), 0.0, 0.0),
            LcncNode("other", "text.value", mapOf("value" to "other"), 420.0, 180.0),
        ], emptySeriesOf())
        val binding = ccekReactorBinding(coroutineContext)
        try {
            binding.reactor.open()
            val context = vmRuntimeTestContext(this)
            context.lcncRunners.putAll(PureNodes.registry { 1L })
            retainedSimulationBrowser(context, null, binding, program,
                executorThreads = 8,
                requestBodies = { method, path, body ->
                    if (method == "POST" && path == "/api/lcnc/run") posts += reify(body) as Map<*, *>
                },
                testRoute = { method, path, body -> controls.route(method, path, body, posts.size) },
                cleanup = controls::releaseAll,
                observations = { mapOf("posts" to posts.toList(), "workspace" to controls.workspace(), "held" to controls.held()) })
            controls.assertBrowser()
            assertTrue(posts.isEmpty(), "A held inspection and camera/node gestures cannot execute a program")
            fun snapshot(proof: Proof, key: String): Map<*, *> = assertIs<Map<*, *>>(proof.body[key])
            fun vector(value: Map<*, *>, key: String): List<*> = assertIs<List<*>>(value[key])
            for (phase in arrayOf("node", "camera")) {
                val active = controls.proof("$phase-active")
                val closing = controls.proof("$phase-closing")
                val stable = controls.proof("$phase-stable")
                val closed = controls.proof("$phase-closed")
                for (proof in arrayOf(active, closing, stable)) {
                    assertEquals(1, proof.workspace[phase], "The actual Refresh request must remain held")
                    assertEquals(1, proof.held[phase])
                    assertEquals(0, proof.posts)
                }
                val before = snapshot(active, "before")
                val moved = snapshot(active, "active")
                assertEquals(1.0, (moved["facets"] as Number).toDouble())
                if (phase == "node") {
                    assertNotEquals(vector(before, "node"), vector(moved, "node"), "Native drag must move the actual authored node")
                    assertNotEquals(vector(before, "nodeStyle"), vector(moved, "nodeStyle"))
                    assertEquals(vector(before, "view"), vector(moved, "view"))
                    assertEquals(false, moved["panning"])
                } else {
                    assertNotEquals(vector(before, "view"), vector(moved, "view"), "Native pan must move the real camera")
                    assertNotEquals(before["world"], moved["world"])
                    assertEquals(vector(before, "node"), vector(moved, "node"))
                    assertEquals(true, moved["panning"])
                    val saved = assertIs<Map<*, *>>(active.body["saveControl"])
                    assertNotEquals(assertIs<String>(saved["beforeHash"]), assertIs<String>(saved["afterHash"]),
                        "An ordinary native wheel must prove the real delayed hash save works while open")
                    assertNotEquals(assertIs<List<*>>(saved["beforeView"])[2], assertIs<List<*>>(saved["afterView"])[2])
                    val elapsed = (closing.body["closeAt"] as Number).toDouble() - (closing.body["beforeWheelAt"] as Number).toDouble()
                    assertTrue(elapsed >= 0 && elapsed < 250, "Close must cancel before the actual camera-save deadline")
                }
                assertEquals(true, closing.body["closePending"])
                val atClose = snapshot(closing, "atClose")
                assertEquals(false, atClose["panning"], "Close removes the active pan class before native pointer release")
                assertEquals(0.0, (atClose["facets"] as Number).toDouble())
                assertEquals(moved["hash"], atClose["hash"])
                if (phase == "camera") assertNotEquals(vector(moved, "view")[2], vector(atClose, "view")[2],
                    "The final real wheel must execute before close cancels its save")
                else assertEquals(vector(moved, "node"), vector(atClose, "node"))
                assertEquals(true, stable.body["closePending"])
                assertEquals(atClose, snapshot(stable, "after"), "Native move/release and elapsed frames cannot alter the closed mount")
                assertTrue(stable.nanoTime - closing.nanoTime >= java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(750),
                    "Observe beyond the real 250 ms save deadline while the admitted read still drains")
                assertEquals(true, closed.body["closed"])
                assertEquals(1, closed.workspace[phase])
                assertEquals(0, closed.held[phase])
                assertEquals(atClose, snapshot(closed, "after"))
                assertEquals(0, closed.posts)
            }
        } finally { withContext(NonCancellable) { controls.releaseAll(); binding.reactor.drain() } }
    }

    @Test
    @org.junit.jupiter.api.Timeout(value = 12, unit = java.util.concurrent.TimeUnit.MINUTES)
    fun aHeldStoredLoadCannotReplaceTheGraphAfterPageClose() = runBlocking<Unit> {
        org.junit.jupiter.api.Assumptions.assumeTrue(
            SystemOperations.default.getenv("TRIKESHED_PANELS_STORED_CLOSE_UI") == "1")
        val controls = Controls()
        val armed = AtomicBoolean(false)
        val selected = AtomicBoolean(false)
        val held = AtomicInteger()
        val produced = AtomicInteger()
        val released = AtomicInteger()
        val posts = AtomicInteger()
        val effects = AtomicInteger()
        val afterArmReads = AtomicInteger()
        val saves = CopyOnWriteArrayList<Map<String, Any?>>()
        val reads = CopyOnWriteArrayList<Map<String, Any?>>()
        val counters = ConcurrentHashMap<String, Map<String, Int>>()
        val binding = ccekReactorBinding(coroutineContext)
        val context = vmRuntimeTestContext(this)
        val pure = PureNodes.registry { 1L }
        context.lcncRunners.putAll(pure)
        val actual = pure.getValue("json.value")
        context.lcncRunners["json.value"] = LcncPureRunner { node, inputs ->
            actual.run(node, inputs).also { effects.incrementAndGet() }
        }
        val publisher = LcncPublisher(context.blackboard, { context.lcncRunners }, context.attachments, context.rete)
        val memory = MemoryIndexLayer(MemoryStore(context.casStore, context.couch.store))
        val scopes = ProjectScopes(JvmFileOperations(), context.attachments,
            CouchIndexBridge(context.attachments, memory), context.casStore, null)
        val keys = KeyMux {}
        val realPatchWire = PatchWire(
            BrainClient(apiKey = "fixture", keyMux = keys, modelMux = ModelMux(keys) {}),
            scopes, attachments = context.attachments, publisher = publisher)
        fun counts(): Map<String, Int> = mapOf("held" to held.get(), "produced" to produced.get(),
            "released" to released.get(), "afterArmReads" to afterArmReads.get(),
            "posts" to posts.get(), "effects" to effects.get())
        try {
            binding.reactor.open()
            retainedSimulationBrowser(context, null, binding,
                LcncProgram("stored-load-canvas", s_[
                    LcncNode("literal", "json.value", mapOf("value" to "7"), 40.0, 40.0),
                ], emptySeriesOf()), executorThreads = 8,
                programLoader = publisher::load,
                testRoute = { method, path, body ->
                    when {
                        method == "POST" && path == "/_test/arm/stored-load" -> {
                            check(armed.compareAndSet(false, true)) { "Stored load is already armed" }
                            HttpResponse(200, "{}")
                        }
                        path == "/api/panels" || path.startsWith("/api/panels/") && path != "/api/panels/retained-check" -> {
                            val response = realPatchWire.route(method, path,
                                "$method $path HTTP/1.1\r\nContent-Type: application/json\r\n\r\n$body", null)
                            if (method == "POST" && response != null) saves += mapOf("path" to path,
                                "document" to reifyMap(body), "status" to response.status, "response" to reifyMap(response.body))
                            if (method == "GET" && response != null && path in setOf("/api/panels/stored-control", "/api/panels/stored-late")) {
                                reads += mapOf("path" to path, "status" to response.status,
                                    "cid" to ContentId.of(response.body.encodeToByteArray()).value)
                                if (armed.get()) {
                                    afterArmReads.incrementAndGet()
                                    if (path == "/api/panels/stored-late" && selected.compareAndSet(false, true)) {
                                        produced.incrementAndGet()
                                        held.incrementAndGet()
                                        try { controls.await("stored-response") }
                                        finally { held.decrementAndGet() }
                                        released.incrementAndGet()
                                    }
                                }
                            }
                            response
                        }
                        else -> {
                            if (method == "POST" && path == "/api/lcnc/run") posts.incrementAndGet()
                            if (method == "POST" && path.startsWith("/_test/report/"))
                                check(counters.putIfAbsent(path.substringAfterLast('/'), counts()) == null)
                            controls.route(method, path, body, posts.get())
                        }
                    }
                }, cleanup = controls::releaseAll,
                observations = { mapOf("counts" to counts(), "saves" to saves.toList(), "reads" to reads.toList()) })

            // Prove the ordinary open-page load before checking the closing response.
            assertEquals(2, saves.size, "Native toolbar saves supply both actual stored documents")
            assertEquals(listOf("/api/panels/stored-control", "/api/panels/stored-late"), saves.map { it["path"] })
            for ((saved, value) in saves.zip(listOf("7", "9"))) {
                assertEquals(200, saved["status"], jsonOf(saved))
                assertEquals("ok", assertIs<Map<*, *>>(saved["response"])["verdict"])
                val program = LcncProgramConfix.fromJson("stored", jsonOf(saved["document"]))
                assertEquals(value, program.nodes[0].params["value"])
                assertEquals(1, program.nodes.size)
            }
            val control = controls.proof("stored-control")
            assertEquals(true, control.body["loaded"])
            val loaded = assertIs<Map<*, *>>(control.body["snapshot"])
            assertIs<String>(loaded["serialized"])
            assertEquals("7", loaded["value"])
            assertEquals(true, loaded["nodeConnected"])
            assertTrue(reads.any { it["path"] == "/api/panels/stored-control" && it["status"] == 200 })
            val heldResponse = reads.last { it["path"] == "/api/panels/stored-late" }
            assertEquals(200, heldResponse["status"])
            assertEquals(assertIs<Map<*, *>>(saves[1]["response"])["cid"], heldResponse["cid"],
                "The held body must be the real saved value9 document, not a synthetic response")
            val closing = controls.proof("stored-closing")
            assertEquals(true, closing.body["closePending"])
            assertEquals(1, counters.getValue("stored-closing")["held"])
            assertEquals(1, counters.getValue("stored-closing")["produced"])
            assertEquals(0, counters.getValue("stored-closing")["released"])
            val before = assertIs<Map<*, *>>(closing.body["snapshot"])
            assertEquals(loaded["serialized"], before["serialized"])
            assertEquals("7", before["value"])
            assertEquals(true, before["sameInput"])
            assertEquals(1, (assertIs<Number>(before["nodes"])).toInt())
            for (key in arrayOf("undoTitle", "redoTitle")) assertIs<String>(before[key])
            for (key in arrayOf("undoDisabled", "redoDisabled")) assertIs<Boolean>(before[key])
            assertTrue(before.containsKey("scratch"))
            val pending = controls.proof("stored-pending")
            assertTrue(pending.nanoTime - closing.nanoTime >= java.util.concurrent.TimeUnit.SECONDS.toNanos(1))
            assertEquals(true, pending.body["closePending"])
            assertEquals(true, pending.body["loadRefused"], "A native Load click after close must not admit another request")
            assertEquals(1, counters.getValue("stored-pending")["held"])
            assertEquals(1, counters.getValue("stored-pending")["afterArmReads"])
            assertEquals(before, pending.body["snapshot"])
            controls.assertBrowser()
            val closed = controls.proof("stored-closed")
            assertEquals(true, closed.body["closed"])
            assertEquals(0, counters.getValue("stored-closed")["held"])
            assertEquals(1, counters.getValue("stored-closed")["released"])
            assertEquals(before, closed.body["snapshot"], "The real late value9 load cannot replace graph, DOM or history")
            val after = assertIs<Map<*, *>>(closed.body["snapshot"])
            assertEquals(true, after["sameGraph"])
            assertEquals(true, after["sameNode"])
            assertEquals(true, after["nodeConnected"])
            assertEquals(before["scratch"], after["scratch"], "The link view observes scratch; autosave=false does not exercise a write")
            val quiet = controls.proof("stored-quiet")
            assertTrue(quiet.nanoTime - closed.nanoTime >= java.util.concurrent.TimeUnit.SECONDS.toNanos(1))
            assertEquals(before, quiet.body["snapshot"])
            assertEquals(1, afterArmReads.get(), "A closed load cannot start a follow-up board sync")
            assertEquals(0, posts.get())
            assertEquals(0, effects.get(), "Saving and loading cannot execute the literal")
            assertTrue(context.blackboard.keys().none { it.startsWith("lcnc/run/") })
            println("PANELS_STORED_LOAD_PROOF=" + jsonOf(mapOf("saves" to saves.toList(), "reads" to reads.toList(),
                "counts" to counts(), "before" to before, "after" to after)))
        } finally {
            withContext(NonCancellable) {
                controls.releaseAll()
                try { scopes.drain() } finally { try { memory.close() } finally { binding.reactor.drain() } }
            }
        }
    }

    private fun pulseProgram() = LcncProgram("panels-owner-pulse", s_[
        LcncNode("slider", "slider", mapOf("min" to "0", "max" to "100", "step" to "1", "value" to "16"), 0.0, 0.0),
        LcncNode("value", "display", x = 400.0, y = 0.0),
    ], s_[LcncWire("slider", "value", "value", "x")])

    private data class Proof(val body: Map<*, *>, val posts: Int, val workspace: Map<String, Int>, val held: Map<String, Int>,
                             val nanoTime: Long = java.lang.System.nanoTime())
    private class Workspace(val name: String) {
        val started = AtomicInteger()
        val held = AtomicInteger()
        val release = CompletableDeferred<Unit>()
    }

    /** Gates real routes and records explicit DOM/Promise assertions from the browser driver. */
    private class Controls {
        private val gates = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
        private val reads = ConcurrentHashMap<String, Workspace>()
        private val selected = AtomicReference<Workspace?>()
        private val proofs = ConcurrentHashMap<String, Proof>()
        private val failures = CopyOnWriteArrayList<Map<String, Any?>>()
        private val closed = AtomicBoolean(false)

        private fun gate(name: String): CompletableDeferred<Unit> = gates.computeIfAbsent(name) { CompletableDeferred() }.also {
            if (closed.get()) it.complete(Unit)
        }
        suspend fun await(name: String) { gate(name).await() }
        fun releaseAll() {
            closed.set(true)
            gates.values.forEach { it.complete(Unit) }
            reads.values.forEach { it.release.complete(Unit) }
        }
        fun proof(name: String): Proof = assertNotNull(proofs[name], "Browser assertions were not reported: $name")
        fun assertBrowser() { assertTrue(failures.isEmpty(), "Actual browser assertion failed: $failures") }
        fun workspaceCount(name: String): Int = assertNotNull(reads[name]).started.get()
        fun workspace(): Map<String, Int> = reads.mapValues { it.value.started.get() }
        fun held(): Map<String, Int> = reads.mapValues { it.value.held.get() }

        suspend fun route(method: String, path: String, body: String, posts: Int): HttpResponse? {
            fun response() = HttpResponse(200, "{}")
            if (method == "POST" && path.startsWith("/_test/release/")) {
                val name = path.substringAfterLast('/')
                if (name.startsWith("workspace-")) checkNotNull(reads[name.removePrefix("workspace-")]).release.complete(Unit)
                else gate(name).complete(Unit)
                return response()
            }
            if (method == "POST" && path.startsWith("/_test/arm/")) {
                check(selected.get()?.release?.isCompleted != false) { "Previous workspace phase is held" }
                val name = path.substringAfterLast('/')
                val work = Workspace(name)
                check(reads.putIfAbsent(name, work) == null)
                selected.set(work)
                return response()
            }
            if (method == "POST" && path.startsWith("/_test/report/")) {
                val name = path.substringAfterLast('/')
                val proof = reify(body) as Map<*, *>
                check(proof["asserted"] == true) { "Report must follow actual browser assertions" }
                check(proofs.putIfAbsent(name, Proof(proof, posts, workspace(), held())) == null)
                return response()
            }
            if (method == "POST" && path == "/_test/failure") {
                val evidence = reify(body) as Map<*, *>
                check(evidence["detail"] is String) { "Browser failure must describe the observed assertion" }
                failures += mapOf("browser" to evidence, "posts" to posts, "workspace" to workspace(), "held" to held())
                return response()
            }
            if (method == "GET" && path == "/api/lcnc/workspace") selected.get()?.let { work ->
                // PanelsFacets.show invokes this GET before checking its captured revision.
                if (work.started.incrementAndGet() <= 3) {
                    work.held.incrementAndGet()
                    try { work.release.await() }
                    finally { work.held.decrementAndGet() }
                }
            }
            return null // Actual module route supplies every production response.
        }
    }
}
