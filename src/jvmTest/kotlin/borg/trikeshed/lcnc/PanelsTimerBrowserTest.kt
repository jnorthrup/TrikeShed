package borg.trikeshed.lcnc

import borg.trikeshed.lcnc.ccek.*
import borg.trikeshed.lib.*
import borg.trikeshed.litebike.JvmKanbanServer.HttpResponse
import borg.trikeshed.module.ModuleContext
import borg.trikeshed.parse.*
import borg.trikeshed.userspace.nio.platform.spi.SystemOperations
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlin.test.*

/** Native interval, real retained pulses, and an already committed response draining over close. */
class PanelsTimerBrowserTest {
    @Test
    @org.junit.jupiter.api.Timeout(value = 12, unit = TimeUnit.MINUTES)
    fun nativeTimerStopsWhileItsRealPulseReceiptDrains() = runBlocking<Unit> {
        org.junit.jupiter.api.Assumptions.assumeTrue(SystemOperations.default.getenv("TRIKESHED_PANELS_TIMER_CLOSE_UI") == "1")
        val controls = Controls()
        val binding = ccekReactorBinding(coroutineContext)
        try {
            binding.reactor.open()
            val context = vmRuntimeTestContext(this)
            context.lcncRunners.putAll(PureNodes.registry { java.lang.System.currentTimeMillis() })
            val program = LcncProgram("panels-timer-close", s_[
                LcncNode("clock", "timer", mapOf("seconds" to "1"), 0.0, 0.0),
                LcncNode("value", "display", x = 400.0, y = 0.0),
            ], s_[LcncWire("clock", "tick", "value", "x")],
                controls = LcncConfixControls(humanOversight = true))
            retainedSimulationBrowser(context, null, binding, program, executorThreads = 8,
                opened = controls::open, testRoute = controls::route,
                cleanup = controls::release, observations = controls::observations)

            controls.assertBrowser()
            val exchanges = controls.exchanges.toList()
            assertEquals(3, controls.posts.get(), "Live and two native interval ticks must be the only program POSTs")
            assertEquals(3, exchanges.size)
            assertTrue("pulse" !in exchanges[0].request)
            for ((index, exchange) in exchanges.withIndex()) {
                assertEquals(200, exchange.status)
                assertEquals("completed", exchange.response["status"])
                assertEquals(true, exchange.response["ok"])
                assertIs<String>(exchange.response["receiptCid"])
                assertIs<String>(exchange.response["rootStateCid"])
                assertEquals(emptyMap<String, Any?>(), exchange.request["inputs"])
                assertTrue((exchange.response["claimId"] as Number).toLong() > 0L)
                if (index > 0) {
                    val pulse = assertIs<Map<*, *>>(exchange.request["pulse"])
                    assertEquals("clock", pulse["nodeId"])
                    assertEquals(emptyMap<String, Any?>(), pulse["outputs"], "The daemon clock supplies the actual tick")
                    assertEquals(exchanges[index - 1].response["receiptCid"], exchange.request["baseReceiptCid"])
                    assertEquals(exchanges[0].response["programCid"], exchange.response["programCid"])
                }
            }
            assertTrue(exchanges[1].receivedAt - exchanges[0].respondedAt >= TimeUnit.MILLISECONDS.toNanos(900),
                "A genuine one-second native interval must follow the completed Live response")
            val shownTick = tick(exchanges[1])
            assertNotEquals(tick(exchanges[0]), shownTick)
            assertNotEquals(shownTick, tick(exchanges[2]))
            val control = controls.proof("control")
            assertEquals(3, control.posts)
            assertEquals(3, control.responses)
            assertEquals(1, control.held)
            assertTrue((control.body["timerHandle"] as Number).toInt() > 0)
            assertEquals(shownTick, control.body["displayText"], "The first real pulse must reach the actual display")
            assertEquals(true, control.body["displayConnected"])
            val closing = controls.proof("closing")
            assertEquals(true, closing.body["closePending"])
            assertEquals(1, closing.held)
            val atClose = assertIs<Map<*, *>>(closing.body["snapshot"])
            assertClosedSnapshot(atClose, shownTick)
            val pending = controls.proof("pending-quiet")
            assertTrue(pending.time - closing.time >= TimeUnit.SECONDS.toNanos(5))
            assertEquals(true, pending.body["closePending"])
            assertEquals(1, pending.held)
            assertEquals(3, pending.posts)
            assertEquals(atClose, pending.body["snapshot"])
            val closed = controls.proof("closed")
            assertEquals(true, closed.body["closed"])
            assertEquals(0, closed.held)
            assertEquals(atClose, closed.body["snapshot"], "The late real receipt cannot repaint the closed output or reopen facets")
            assertTrue(assertIs<String>(closed.body["statusText"]).contains(assertIs<String>(exchanges[2].response["runId"])),
                "The admitted action must actually consume its held completed response before close resolves")
            val quiet = controls.proof("quiet")
            assertTrue(quiet.time - closed.time >= TimeUnit.SECONDS.toNanos(3))
            assertEquals(3, quiet.posts)
            assertEquals(0, quiet.held)
            assertEquals(atClose, quiet.body["snapshot"])
        } finally { withContext(NonCancellable) { controls.release(); binding.reactor.drain() } }
    }

    private fun tick(exchange: Exchange): String {
        val outputs = assertIs<Map<*, *>>(exchange.response["outputs"])
        val clock = assertIs<Map<*, *>>(outputs["clock"])
        val display = assertIs<Map<*, *>>(outputs["value"])
        assertEquals(clock["tick"], display["x"])
        return (display["x"] as Number).toLong().toString()
    }

    private fun assertClosedSnapshot(snapshot: Map<*, *>, shownTick: String) {
        assertEquals(true, snapshot["timerNull"])
        assertEquals(true, snapshot["sameDisplay"])
        assertEquals(true, snapshot["displayConnected"])
        assertEquals(shownTick, snapshot["displayText"])
        assertEquals(2.0, (snapshot["nodes"] as Number).toDouble())
        assertEquals(0.0, (snapshot["facets"] as Number).toDouble())
    }

    private data class Exchange(val request: Map<*, *>, val response: Map<*, *>, val status: Int,
                                val receivedAt: Long, val respondedAt: Long)
    private data class Proof(val body: Map<*, *>, val posts: Int, val responses: Int, val held: Int,
                             val time: Long = java.lang.System.nanoTime())

    private class Controls {
        private val context = AtomicReference<ModuleContext>()
        private val reports = ConcurrentHashMap<String, Proof>()
        private val failures = CopyOnWriteArrayList<Map<*, *>>()
        private val gate = CompletableDeferred<Unit>()
        private val held = AtomicInteger()
        val posts = AtomicInteger()
        val exchanges = CopyOnWriteArrayList<Exchange>()

        fun open(actual: ModuleContext) { context.set(actual) }
        fun release() { gate.complete(Unit) }
        fun proof(name: String): Proof = assertNotNull(reports[name], "Missing actual browser checkpoint: $name")
        fun assertBrowser() = assertTrue(failures.isEmpty(), "Actual browser assertion failed: $failures")
        fun observations(): Map<String, Any?> = mapOf("posts" to posts.get(), "held" to held.get(),
            "exchanges" to exchanges.map { mapOf("request" to it.request, "response" to it.response, "status" to it.status,
                "receivedAtNanos" to it.receivedAt.toString(), "respondedAtNanos" to it.respondedAt.toString()) })

        suspend fun route(method: String, path: String, body: String): HttpResponse? {
            if (method == "POST" && path == "/api/lcnc/run") {
                val ordinal = posts.incrementAndGet()
                val receivedAt = java.lang.System.nanoTime()
                val request = reify(body) as Map<*, *>
                val actual = checkNotNull(context.get())
                // This is the helper's ordinary module dispatch, performed exactly once.
                // Only transport of its unchanged actual response is held after commit.
                val response = checkNotNull(actual.routes.match(path)?.route(method, path, body, null))
                exchanges += Exchange(request, reify(response.body) as Map<*, *>, response.status,
                    receivedAt, java.lang.System.nanoTime())
                if (ordinal == 3) {
                    held.incrementAndGet()
                    try { gate.await() } finally { held.decrementAndGet() }
                }
                return response
            }
            if (method == "POST" && path == "/_test/release/pulse") { release(); return HttpResponse(200, "{}") }
            if (method == "POST" && path.startsWith("/_test/report/")) {
                val name = path.substringAfterLast('/')
                val report = reify(body) as Map<*, *>
                check(report["asserted"] == true)
                check(reports.putIfAbsent(name, Proof(report, posts.get(), exchanges.size, held.get())) == null)
                return HttpResponse(200, "{}")
            }
            if (method == "POST" && path == "/_test/failure") {
                failures += reify(body) as Map<*, *>
                return HttpResponse(200, "{}")
            }
            return null
        }
    }
}
