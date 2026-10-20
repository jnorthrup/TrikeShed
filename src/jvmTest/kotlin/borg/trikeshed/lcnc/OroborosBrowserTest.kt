package borg.trikeshed.lcnc

import borg.trikeshed.lcnc.ccek.*
import borg.trikeshed.lib.*
import borg.trikeshed.litebike.JvmKanbanServer.HttpResponse
import borg.trikeshed.module.ModuleContext
import borg.trikeshed.parse.*
import borg.trikeshed.userspace.nio.platform.spi.SystemOperations
import kotlinx.coroutines.*
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

/** Native browser gestures operate the production viewer and its one actual held execution. */
class OroborosBrowserTest {
    @org.junit.jupiter.api.io.TempDir lateinit var directory: Path

    @Test
    @org.junit.jupiter.api.Timeout(value = 12, unit = java.util.concurrent.TimeUnit.MINUTES)
    fun viewerClosePreservesTheHoldAndInputPatchReachesTheRetainedReceipt() = runBlocking<Unit> {
        org.junit.jupiter.api.Assumptions.assumeTrue(SystemOperations.default.getenv("TRIKESHED_OROBOROS_UI") == "1")
        val token = java.util.UUID.randomUUID().toString()
        val calls = AtomicInteger()
        val lexical = AtomicReference<Any?>()
        val reads = AtomicInteger()
        val posts = CopyOnWriteArrayList<String>()
        val commands = CopyOnWriteArrayList<Map<String, Any?>>()
        val failures = CopyOnWriteArrayList<String>()
        val binding = ccekReactorBinding(coroutineContext)
        val context = vmRuntimeTestContext(this)
        val actual = PureNodes.registry { 123L }.getValue("pick")
        context.lcncRunners["pick"] = LcncNodeRunner { node, inputs ->
            calls.incrementAndGet()
            lexical.set(currentCoroutineContext()[LcncScopeFrame]?.binding("operator"))
            actual.run(node, inputs)
        }
        val program = LcncProgram("oroboros-browser", s_[LcncNode("effect", "pick")], emptySeriesOf(),
            controls = LcncConfixControls(humanOversight = true))
        lateinit var bound: ModuleContext
        lateinit var execution: Deferred<HttpResponse>
        lateinit var pause: Map<String, Any?>
        var heldVerified = false
        var receiptVerified = false
        suspend fun route(method: String, path: String, body: String = "") =
            requireNotNull(bound.routes.match(path.substringBefore('?'))?.route(method, path, body, null))
        suspend fun snapshot(): Map<String, Any?> =
            reifyMap(route("GET", "/api/lcnc/oroboros?runId=${pause["runId"]}").body)
        suspend fun held() {
            assertEquals("paused", assertIs<Map<*, *>>(snapshot()["debug"])["state"])
            assertFalse(execution.isCompleted, "Closing observation must not resume or cancel the execution")
            assertEquals(0, calls.get())
            assertTrue(posts.isEmpty(), "Observation must not submit execution or control commands: $posts")
            // Let any already admitted read drain, then prove the closed viewer stopped polling.
            delay(1000)
            val count = reads.get()
            delay(1000)
            assertEquals(count, reads.get(), "Closed viewer must release its polling timer")
            heldVerified = true
        }
        suspend fun receipt(): Map<String, Any?> {
            assertTrue(heldVerified, "Close the first observation while paused before reopening")
            val response = withTimeout(5000) { execution.await() }
            assertEquals(200, response.status, response.body)
            val result = reifyMap(response.body)
            assertEquals("completed", result["status"])
            assertEquals(pause["runId"], result["runId"])
            assertEquals(1, calls.get(), "The edited original invocation executes exactly once")
            assertEquals("browser", lexical.get(), "The actual invocation reads the edited lexical scope")
            assertEquals(42, (assertIs<Map<*, *>>(assertIs<Map<*, *>>(result["outputs"])["effect"])["y"] as Number).toInt())
            assertEquals(listOf("/api/lcnc/oroboros", "/api/lcnc/oroboros", "/api/lcnc/oroboros"), posts.toList())
            assertEquals(listOf("monitor-attach", "intervene", "continue"), commands.map { it["action"] })
            assertTrue(commands.all { it["runId"] == pause["runId"] && it["ordinal"] == pause["ordinal"] })
            assertEquals(true, commands[0]["spiking"])
            assertEquals(42, (assertIs<Map<*, *>>(commands[1]["inputs"])["x"] as Number).toInt())
            assertEquals("browser", assertIs<Map<*, *>>(commands[1]["bindings"])["operator"])
            val retained = snapshot()
            assertEquals("retained", retained["observation"])
            val debug = assertIs<Map<*, *>>(retained["debug"])
            assertEquals("completed", debug["state"])
            assertTrue(assertIs<List<*>>(debug["actions"]).isEmpty())
            val intervention = assertIs<Map<*, *>>(assertIs<List<*>>(debug["interventions"]).single())
            assertEquals("applied", intervention["status"])
            assertEquals("browser", assertIs<Map<*, *>>(intervention["bindings"])["operator"])
            val monitor = assertIs<Map<*, *>>(debug["monitor"])
            assertEquals("spiking", monitor["requestedMode"])
            assertTrue((monitor["observed"] as Number).toLong() > 0)
            assertEquals(false, monitor["durableLog"])
            assertNotNull(result["oroborosCid"])
            receiptVerified = true
            return mapOf("ok" to true, "runId" to result["runId"], "receiptCid" to result["receiptCid"],
                "calls" to calls.get(), "output" to 42, "intervention" to intervention["status"],
                "aggregated" to monitor["aggregated"])
        }
        try {
            binding.reactor.open()
            retainedSimulationBrowser(context, null, binding, program,
                stateDirectory = directory.toFile(), executorThreads = 4,
                opened = { active ->
                    bound = active
                    execution = active.scope.async {
                        route("POST", "/api/lcnc/run", jsonOf(mapOf("debugToken" to token,
                            "timeoutMs" to 600000, "document" to reify(LcncProgramConfix.toJson(program)))))
                    }
                    pause = withTimeout(10000) {
                        var value = reifyMap(route("GET", "/api/lcnc/run/debug?token=$token").body)
                        while (value["state"] != "paused") {
                            delay(10)
                            value = reifyMap(route("GET", "/api/lcnc/run/debug?token=$token").body)
                        }
                        value
                    }
                    assertEquals("effect", assertIs<Map<*, *>>(pause["site"])["nodeId"])
                    println("OROBOROS_UI_RUN=${pause["runId"]}")
                },
                requests = { method, path ->
                    if (method == "GET" && path == "/api/lcnc/oroboros") reads.incrementAndGet()
                    if (method == "POST" && path.startsWith("/api/")) posts += path
                },
                requestBodies = { method, path, body ->
                    if (method == "POST" && path == "/api/lcnc/oroboros") commands += reifyMap(body)
                },
                testRoute = { method, path, _ ->
                    if (method == "GET" && path in setOf("/_test/held", "/_test/verify")) try {
                        val proof = if (path == "/_test/held") {
                            held(); mapOf("ok" to true, "state" to "paused", "calls" to calls.get())
                        } else receipt()
                        HttpResponse(200, jsonOf(proof))
                    } catch (failure: Throwable) {
                        failures += failure.message ?: "Browser execution verification failed"
                        if (failure is CancellationException) throw failure
                        HttpResponse(409, jsonOf(mapOf("ok" to false, "error" to failure.message)))
                    } else null
                },
                observations = { mapOf("runId" to pause["runId"], "ordinal" to pause["ordinal"],
                    "calls" to calls.get(), "reads" to reads.get(), "posts" to posts.toList(),
                    "commands" to commands.toList(), "heldVerified" to heldVerified,
                    "receiptVerified" to receiptVerified, "failures" to failures.toList()) })
            assertTrue(heldVerified, "Missing native-browser close verification")
            assertTrue(receiptVerified, "Missing native-browser intervention and retained receipt verification")
            assertTrue(failures.isEmpty(), failures.joinToString("\n"))
            assertEquals(1, calls.get())
            assertEquals(listOf("monitor-attach", "intervene", "continue"), commands.map { it["action"] })
            assertEquals(3, posts.size)
        } finally {
            withContext(NonCancellable) { binding.reactor.drain() }
        }
    }
}
