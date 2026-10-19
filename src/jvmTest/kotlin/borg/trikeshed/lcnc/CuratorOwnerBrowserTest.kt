package borg.trikeshed.lcnc

import borg.trikeshed.lcnc.ccek.*
import borg.trikeshed.lib.*
import borg.trikeshed.module.ModuleContext
import borg.trikeshed.litebike.JvmKanbanServer.HttpResponse
import borg.trikeshed.parse.*
import borg.trikeshed.userspace.nio.platform.spi.SystemOperations
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

/** Real compiled Curator, real trail route, and explicit browser observations over loopback. */
class CuratorOwnerBrowserTest {
    @Test
    @org.junit.jupiter.api.Timeout(value = 12, unit = TimeUnit.MINUTES)
    fun aHeldTrailReadKeepsClosePendingAndStopsPollingAndResize() = runBlocking<Unit> {
        org.junit.jupiter.api.Assumptions.assumeTrue(SystemOperations.default.getenv("TRIKESHED_CURATOR_OWNER_UI") == "1")
        val started = AtomicInteger()
        val passed = AtomicInteger()
        val held = AtomicInteger()
        val release = CompletableDeferred<Unit>()
        val bound = AtomicReference<ModuleContext?>()
        val marker = "curator-close-${java.util.UUID.randomUUID()}"
        val markerProgram = LcncProgram(marker, s_[
            LcncNode("marker", "text.value", mapOf("value" to marker)),
        ], emptySeriesOf())
        val markerResult = AtomicReference<Marker?>()
        val markerMutex = Mutex()
        val reports = ConcurrentHashMap<String, Proof>()
        val failures = CopyOnWriteArrayList<Map<*, *>>()
        val binding = ccekReactorBinding(coroutineContext)
        try {
            binding.reactor.open()
            val context = vmRuntimeTestContext(this)
            context.lcncRunners.putAll(PureNodes.registry { 1L })
            println("LCNC_CURATOR_UI_PATH=/curator")
            retainedSimulationBrowser(context, null, binding, LcncProgram.EMPTY,
                executorThreads = 8,
                opened = { bound.set(it) },
                requestBodies = { method, path, _ ->
                    if (method == "GET" && path == "/api/lcnc/trail") passed.incrementAndGet()
                },
                testRoute = { method, path, body ->
                    when {
                        method == "GET" && path == "/api/lcnc/trail" -> {
                            if (started.incrementAndGet() == 2) {
                                held.incrementAndGet()
                                try { release.await() } finally { held.decrementAndGet() }
                            }
                            null // Continue into the actual module's LcncTrail.live route.
                        }
                        method == "POST" && path == "/_test/release/trail" -> {
                            val result = markerMutex.withLock {
                                markerResult.get() ?: run {
                                    val module = checkNotNull(bound.get())
                                    val response = assertNotNull(checkNotNull(module.routes.match("/api/lcnc/run"))
                                        .route("POST", "/api/lcnc/run", jsonOf(mapOf("name" to marker,
                                            "document" to reify(LcncProgramConfix.toJson(markerProgram)))), null))
                                    val receipt = assertIs<Map<*, *>>(reify(response.body))
                                    assertEquals(200, response.status, response.body)
                                    assertEquals(true, receipt["ok"])
                                    assertEquals("completed", receipt["status"])
                                    assertIs<String>(receipt["receiptCid"])
                                    val outputs = assertIs<Map<*, *>>(receipt["outputs"])
                                    assertEquals(marker, assertIs<Map<*, *>>(outputs["marker"])["value"])
                                    val trail = assertNotNull(checkNotNull(module.routes.match("/api/lcnc/trail"))
                                        .route("GET", "/api/lcnc/trail?since=0&nodes=0", "", null))
                                    assertEquals(200, trail.status, trail.body)
                                    val keys = assertIs<List<*>>(assertIs<Map<*, *>>(reify(trail.body))["nodeKeys"])
                                    assertTrue("$marker/marker" in keys, "Only the real program execution creates this trace node")
                                    Marker(receipt, keys.size).also(markerResult::set)
                                }
                            }
                            release.complete(Unit)
                            HttpResponse(200, jsonOf(mapOf("marker" to marker, "nodes" to result.nodes, "receipt" to result.receipt)))
                        }
                        method == "POST" && path.startsWith("/_test/report/") -> {
                            val report = reify(body) as Map<*, *>
                            check(report["asserted"] == true) { "Report must follow actual browser assertions" }
                            val proof = Proof(report, started.get(), passed.get(), held.get(), java.lang.System.nanoTime())
                            check(reports.putIfAbsent(path.substringAfterLast('/'), proof) == null)
                            HttpResponse(200, "{}")
                        }
                        method == "POST" && path == "/_test/failure" -> {
                            val failure = reify(body) as Map<*, *>
                            check(failure["detail"] is String)
                            failures += failure
                            HttpResponse(200, "{}")
                        }
                        else -> null
                    }
                },
                cleanup = { release.complete(Unit) },
                observations = { mapOf("started" to started.get(), "passed" to passed.get(), "held" to held.get(),
                    "reports" to reports.keys.toList(), "marker" to marker,
                    "markerNodes" to markerResult.get()?.nodes, "markerReceipt" to markerResult.get()?.receipt) })
            assertTrue(failures.isEmpty(), "Actual browser assertion failed: $failures")
            fun proof(name: String): Proof = assertNotNull(reports[name], "Missing actual browser proof: $name")
            val open = proof("open")
            val closing = proof("closing")
            val closed = proof("closed")
            val stable = proof("stable")
            for (proof in arrayOf(open, closing)) {
                assertEquals(2, proof.started)
                assertEquals(1, proof.passed)
                assertEquals(1, proof.held)
            }
            val initialNodes = nodes(open.body["status"])
            val before = dimensions(open.body, "before")
            val after = dimensions(open.body, "after")
            assertTrue(number(before["hostWidth"]) != number(after["hostWidth"]), "Native layout must change while open")
            assertAligned(before)
            assertAligned(after)
            assertTrue(canvas(before) != canvas(after), "The native ResizeObserver must update backing sizes while open")
            assertEquals(true, closing.body["closePending"])
            assertEquals(true, closed.body["closed"])
            val seeded = assertNotNull(markerResult.get(), "The real marker program must complete before the held trail response")
            assertTrue(seeded.nodes > initialNodes, "The held trail response contains new unique execution nodes")
            assertEquals(seeded.nodes, nodes(closed.body["statusAtClose"]), "The actual close completion must observe the new marker's node count")
            for (proof in arrayOf(closed, stable)) {
                assertEquals(2, proof.started, "Close must stop the next actual trail request")
                assertEquals(2, proof.passed, "The held request must continue through the real production route")
                assertEquals(0, proof.held)
            }
            assertTrue(stable.nanoTime - closed.nanoTime >= TimeUnit.MILLISECONDS.toNanos(750), "Observe three complete poll periods after resolved close")
            val frozen = dimensions(closed.body, "dimensions")
            val resized = dimensions(stable.body, "dimensions")
            assertAligned(frozen)
            assertEquals(number(frozen["ratio"]), number(resized["ratio"]))
            assertTrue(number(frozen["hostWidth"]) != number(resized["hostWidth"]), "Host layout must actually change after close")
            assertEquals(canvas(frozen), canvas(resized), "Disconnected ResizeObserver cannot resize the closed canvases")
            assertEquals(2, started.get())
            assertEquals(2, passed.get())
            assertEquals(0, held.get())
        } finally { withContext(NonCancellable) { release.complete(Unit); binding.reactor.drain() } }
    }

    private data class Proof(val body: Map<*, *>, val started: Int, val passed: Int, val held: Int, val nanoTime: Long)
    private data class Marker(val receipt: Map<*, *>, val nodes: Int)
    private fun nodes(status: Any?): Int = assertNotNull(Regex("^live · ([0-9]+) nodes$")
        .matchEntire(assertIs<String>(status)), "Actual Curator status must reflect a parsed trail response").groupValues[1].toInt()
    private fun dimensions(body: Map<*, *>, key: String): Map<*, *> = assertIs<Map<*, *>>(body[key])
    private fun canvas(dimensions: Map<*, *>): List<*> = assertIs<List<*>>(dimensions["canvas"])
    private fun number(value: Any?): Double = assertIs<Number>(value).toDouble()
    private fun assertAligned(dimensions: Map<*, *>) {
        val ratio = number(dimensions["ratio"])
        val width = number(dimensions["hostWidth"])
        val height = number(dimensions["hostHeight"])
        assertTrue(ratio > 0 && ratio.isFinite() && width > 0 && height > 0)
        val canvases = canvas(dimensions)
        assertEquals(2, canvases.size, "Compiled Curator creates the actual GL and text canvases")
        for (value in canvases) {
            val size = assertIs<List<*>>(value)
            assertEquals(2, size.size)
            assertEquals((width * ratio).toInt(), number(size[0]).toInt())
            assertEquals((height * ratio).toInt(), number(size[1]).toInt())
        }
    }
}
