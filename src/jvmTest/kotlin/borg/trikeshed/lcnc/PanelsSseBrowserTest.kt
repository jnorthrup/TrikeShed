package borg.trikeshed.lcnc

import borg.trikeshed.graal.*
import borg.trikeshed.lcnc.ccek.*
import borg.trikeshed.lib.*
import borg.trikeshed.litebike.JvmKanbanServer.HttpResponse
import borg.trikeshed.module.ModuleContext
import borg.trikeshed.parse.*
import borg.trikeshed.userspace.nio.platform.spi.SystemOperations
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlin.test.*

/** Native blackboard SSE, actual module reads, and the closed mount's palette identity. */
class PanelsSseBrowserTest {
    @Test
    @org.junit.jupiter.api.Timeout(value = 12, unit = java.util.concurrent.TimeUnit.MINUTES)
    fun anAdmittedVocabularyReadCannotReplaceTheClosedPaletteOrAttachNewListeners() = runBlocking<Unit> {
        org.junit.jupiter.api.Assumptions.assumeTrue(SystemOperations.default.getenv("TRIKESHED_PANELS_SSE_CLOSE_UI") == "1")
        val controls = Controls()
        val binding = ccekReactorBinding(coroutineContext)
        try {
            binding.reactor.open()
            val context = vmRuntimeTestContext(this)
            context.lcncRunners.putAll(PureNodes.registry { 1L })
            val program = LcncProgram("panels-sse-close", s_[
                LcncNode("text", "text.value", mapOf("value" to "SSE close control")),
            ], emptySeriesOf())
            retainedSimulationBrowser(context, null, binding, program,
                executorThreads = 8, blackboardEvents = true,
                opened = controls::open,
                requests = controls::request,
                testRoute = controls::route,
                cleanup = controls::release,
                observations = controls::observations)

            val control = controls.proof("control")
            assertEquals(1, control.connections)
            assertEquals(mapOf("contracts" to 1, "presets" to 1, "panels" to 1), control.counts.getValue("control"),
                "A genuine native SSE vocabulary update must consume the actual contracts and palette responses")
            assertEquals(true, control.body["replaced"])
            assertEquals(true, control.body["connected"])
            assertTrue((control.body["items"] as Number).toInt() > 0)
            val closing = controls.proof("closing")
            assertEquals(1, closing.held)
            assertEquals(1, closing.counts.getValue("held").getValue("contracts"))
            assertEquals(0, closing.counts.getValue("held").getValue("presets"))
            assertEquals(0, closing.counts.getValue("held").getValue("panels"))
            assertEquals(true, closing.body["closePending"])
            controls.assertBrowser()
            val closed = controls.proof("closed")
            assertEquals(true, closed.body["closed"])
            assertEquals(0, closed.held)
            assertEquals(closing.body["atClose"], closed.body["after"],
                "The admitted vocabulary response replaced actual palette identities after close")
            assertEquals(0.0, (closed.body["newListenerTargets"] as Number).toDouble())
            assertEquals(mapOf("contracts" to 1, "presets" to 0, "panels" to 0), closed.counts.getValue("held"),
                "The accepted contracts read must finish without starting a closed palette rebuild")
            val quiet = controls.proof("quiet")
            assertTrue(quiet.nanoTime - controls.afterPublication() >= java.util.concurrent.TimeUnit.SECONDS.toNanos(5),
                "Observe a real bounded five seconds after publication; no fake clock or timeless absence claim")
            assertEquals(closed.body["after"], quiet.body["after"])
            assertEquals(mapOf("contracts" to 0, "presets" to 0, "panels" to 0), quiet.counts.getValue("after"))
            assertEquals(1, quiet.connections, "Closed native EventSource must not reconnect")
            assertEquals(0, controls.posts.get(), "Vocabulary observations must never execute the authored program")
        } finally { withContext(NonCancellable) { controls.release(); binding.reactor.drain() } }
    }

    private class Reads {
        val contracts = AtomicInteger()
        val presets = AtomicInteger()
        val panels = AtomicInteger()
        fun snapshot(): Map<String, Int> = mapOf("contracts" to contracts.get(), "presets" to presets.get(), "panels" to panels.get())
    }

    private data class Proof(val body: Map<*, *>, val counts: Map<String, Map<String, Int>>, val held: Int, val connections: Int,
                             val nanoTime: Long = java.lang.System.nanoTime())

    private class Controls {
        private val context = AtomicReference<ModuleContext>()
        private val phase = AtomicReference("boot")
        private val reads = ConcurrentHashMap<String, Reads>()
        private val reports = ConcurrentHashMap<String, Proof>()
        private val failures = CopyOnWriteArrayList<Map<String, Any?>>()
        private val gate = CompletableDeferred<Unit>()
        private val released = AtomicBoolean(false)
        private val held = AtomicInteger()
        private val connections = AtomicInteger()
        private val afterPublishedAt = AtomicReference<Long?>()
        val posts = AtomicInteger()

        fun open(actual: ModuleContext) { context.set(actual) }
        fun release() { released.set(true); gate.complete(Unit) }
        fun proof(name: String): Proof = assertNotNull(reports[name], "Actual browser checkpoint missing: $name")
        fun assertBrowser() = assertTrue(failures.isEmpty(), "Actual browser assertion failed: $failures")
        fun afterPublication(): Long = assertNotNull(afterPublishedAt.get(), "The real after-close publication did not occur")
        private fun counts(): Map<String, Map<String, Int>> = reads.mapValues { it.value.snapshot() }
        fun observations(): Map<String, Any?> = mapOf("reads" to counts(), "held" to held.get(),
            "connections" to connections.get(), "posts" to posts.get(), "afterPublishedAtNanos" to afterPublishedAt.get()?.toString())

        fun request(method: String, path: String) {
            if (method == "POST" && path == "/api/lcnc/run") posts.incrementAndGet()
            if (method != "GET") return
            if (path == "/blackboard/facts") connections.incrementAndGet()
            val current = reads.computeIfAbsent(phase.get()) { Reads() }
            when (path) {
                "/api/lcnc/contracts" -> current.contracts.incrementAndGet()
                "/api/panels/presets" -> current.presets.incrementAndGet()
                "/api/panels" -> current.panels.incrementAndGet()
            }
        }

        suspend fun route(method: String, path: String, body: String): HttpResponse? {
            if (method == "POST" && path.startsWith("/_test/publish/")) {
                val name = path.substringAfterLast('/')
                require(name in setOf("control", "held", "after"))
                check(reads.putIfAbsent(name, Reads()) == null) { "Publication phase already consumed" }
                phase.set(name)
                val actual = checkNotNull(context.get())
                // Republish the real current catalog through its existing change producer.
                // BlackboardWire, native EventSource and the compiled callback carry it from here.
                val vocabulary = checkNotNull(actual.blackboard.get(LcncBlackboard.VOCABULARY))
                actual.blackboard.put(LcncBlackboard.VOCABULARY, vocabulary, "panels-sse-browser")
                if (name == "after") afterPublishedAt.set(java.lang.System.nanoTime())
                return HttpResponse(200, jsonOf(mapOf("revision" to actual.blackboard.snapshot().revision,
                    "publishedAtNanos" to afterPublishedAt.get()?.toString())))
            }
            if (method == "POST" && path == "/_test/release/contracts") { release(); return HttpResponse(200, "{}") }
            if (method == "POST" && path.startsWith("/_test/report/")) {
                val name = path.substringAfterLast('/')
                val report = reify(body) as Map<*, *>
                check(report["asserted"] == true)
                check(reports.putIfAbsent(name, Proof(report, counts(), held.get(), connections.get())) == null)
                return HttpResponse(200, "{}")
            }
            if (method == "POST" && path == "/_test/failure") {
                val failure = reify(body) as Map<*, *>
                check(failure["detail"] is String)
                failures += mapOf("browser" to failure, "reads" to counts(), "held" to held.get(), "connections" to connections.get())
                return HttpResponse(200, "{}")
            }
            if (method == "GET" && path == "/api/lcnc/contracts" && phase.get() == "held") {
                held.incrementAndGet()
                try { if (!released.get()) gate.await() }
                finally { held.decrementAndGet() }
            }
            return null // Every contracts response and palette payload remains the actual existing route.
        }
    }
}
