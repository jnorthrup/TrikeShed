package borg.trikeshed.hermes

import borg.trikeshed.forge.server.HermesConsoleWire
import borg.trikeshed.lib.*
import borg.trikeshed.parse.*
import borg.trikeshed.userspace.nio.platform.spi.SystemOperations
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlin.test.*

/** Native input and Reset use the production page, wire, line editor and diagnostic GraalPy guest. */
class HermesTerminalBrowserTest {
    @Test
    @org.junit.jupiter.api.Timeout(value = 8, unit = TimeUnit.MINUTES)
    fun resetCannotOvertakeAnEnteredLineWhileItsTextReplyIsHeld() = browser("order")

    @Test
    @org.junit.jupiter.api.Timeout(value = 8, unit = TimeUnit.MINUTES)
    fun fullInputLaneRefusesPartialCommandsUntilReset() = browser("capacity")

    @Test
    @org.junit.jupiter.api.Timeout(value = 8, unit = TimeUnit.MINUTES)
    fun failedTextAcknowledgementRefusesPartialCommandsUntilReset() = browser("rejection")

    @Test
    @org.junit.jupiter.api.Timeout(value = 22, unit = TimeUnit.MINUTES)
    fun heldSnapshotAndLateEventsCannotRepaintAfterPageDrain() = browser("owner")

    private fun browser(scenario: String) = runBlocking<Unit> {
        org.junit.jupiter.api.Assumptions.assumeTrue(
            SystemOperations.default.getenv("TRIKESHED_HERMES_INPUT_UI") == "1")
        val owner = SupervisorJob(coroutineContext[Job])
        val scope = CoroutineScope(coroutineContext.minusKey(Job) + owner + Dispatchers.IO)
        val console = HermesVmConsole(Path.of("unused"), Path.of("unused"), columns = 100, rows = 24)
        console.inventoryLoader = { port ->
            port.inventorySources(mapOf(
                "hermes_cli" to ("hermes_cli/__init__.py" to ""),
                "hermes_cli.main" to ("hermes_cli/main.py" to ""),
            ))
        }
        val wire = HermesConsoleWire(console, scope)
        val controls = Controls(console, scenario)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 16)
        val executor = Executors.newFixedThreadPool(6)
        val streams = ConcurrentHashMap.newKeySet<Job>()
        server.executor = executor
        try {
            server.createContext("/") { exchange ->
                var streaming = false
                try {
                    val path = exchange.requestURI.path
                    if (path == HermesConsoleWire.EVENTS_PATH) {
                        streaming = true
                        controls.streamOpened.incrementAndGet()
                        controls.streamLive.incrementAndGet()
                        runBlocking {
                            val stream = scope.launch(start = CoroutineStart.LAZY) { stream(exchange, wire) }
                            streams.add(stream)
                            try { stream.start(); stream.join() }
                            finally { streams.remove(stream); controls.streamLive.decrementAndGet() }
                        }
                    } else {
                        val body = exchange.requestBody.readBytes().decodeToString()
                        val response = when {
                            path.startsWith("/_test/") -> controls.route(path, body)
                            path.endsWith(".js") || path.endsWith(".css") -> {
                                val bytes = checkNotNull(javaClass.classLoader.getResourceAsStream("web$path")) { path }
                                    .use { it.readBytes() }
                                borg.trikeshed.litebike.JvmKanbanServer.HttpResponse(200, "",
                                    if (path.endsWith(".js")) "application/javascript" else "text/css", bytes)
                            }
                            else -> runBlocking {
                                controls.before(exchange.requestMethod, path, body)
                                wire.route(exchange.requestMethod, exchange.requestURI.toString(), body, null)
                                    .also { controls.after(exchange.requestMethod, path, body) }
                            }
                        }
                        val content = response?.bytes ?: (response?.body ?: "{}").encodeToByteArray()
                        val bytes = if (scenario == "owner" && path == "/hermes")
                            content.decodeToString().replace("</body>", OWNER_CONTROL + "</body>").encodeToByteArray()
                        else content
                        exchange.responseHeaders.set("Content-Type", response?.contentType ?: "application/json")
                        exchange.sendResponseHeaders(response?.status ?: 404, bytes.size.toLong())
                        exchange.responseBody.write(bytes)
                    }
                } catch (failure: Exception) {
                    if (!streaming) {
                        val bytes = jsonOf(mapOf("error" to failure.toString())).encodeToByteArray()
                        exchange.sendResponseHeaders(500, bytes.size.toLong())
                        exchange.responseBody.write(bytes)
                    }
                } finally { exchange.close() }
            }
            server.start()
            assertTrue(wire.open())
            println("TRIKESHED_HERMES_INPUT_UI=$scenario http://127.0.0.1:${server.address.port}/hermes")
            assertTrue(withContext(Dispatchers.IO) { controls.finished.await(if (scenario == "owner") 20 else 6, TimeUnit.MINUTES) },
                "Native browser check did not finish")
            if (scenario == "owner") {
                controls.assertOwner()
                return@runBlocking
            }
            assertEquals(1, controls.held.get(), "The test must hold an actual text acknowledgement")
            assertTrue(controls.released.isCompleted, "The actual acknowledgement must be released")
            assertEquals(0, controls.resetBeforeRelease.get(),
                "Reset overtook the queued native Enter while an earlier text acknowledgement was held: ${controls.requests}")
            val requests = controls.requests.toList()
            val reset = requests.indexOfFirst { it.path.endsWith("/reset") }
            assertTrue(reset > 0, "The browser must request one actual reset")
            assertEquals(1, requests.count { it.path.endsWith("/reset") })
            if (scenario == "order") {
                assertEquals("ENTER", requests[reset - 1].body["key"],
                    "All typed input, including Enter, must precede Reset")
            } else {
                val controlEnter = requests.indexOfFirst { it.body["key"] == "ENTER" }
                assertEquals(controlEnter + 2, reset,
                    "After the held colon, no truncated input or Enter may reach the server before Reset")
                assertEquals(":", requests[reset - 1].body["text"])
                assertTrue(assertIs<String>(controls.proofs.getValue("refused")["status"]).contains("Input stopped"))
            }
            assertEquals(setOf("control", "held", "reset-clicked", "done") +
                if (scenario == "order") emptySet() else setOf("refused"), controls.proofs.keys)
            assertTrue(assertIs<String>(controls.proofs.getValue("control")["screen"]).contains("hermes-control"))
            assertEquals("ready", controls.proofs.getValue("done")["state"])
            val signals = console.panel.signals().view.toList()
            assertEquals(listOf(CONTROL, BEFORE_RESET), signals.filter { it.kind == "command" }.map { it.payload },
                "No truncated or duplicated command may be admitted")
            for (command in listOf(CONTROL, BEFORE_RESET)) {
                val admitted = signals.single { it.kind == "command" && it.payload == command }
                val outcomes = signals.filter { it.causeSignalId == admitted.id && it.kind in setOf("completed", "failed") }
                assertEquals(listOf("completed"), outcomes.map { it.kind }, "$command must execute exactly once")
                val output = signals.filter { it.causeSignalId == admitted.id && it.kind !in setOf("completed", "failed") }
                    .joinToString("") { it.payload }.replace("\r\n", "\n")
                val expected = (if (command == CONTROL) "hermes-control" else "hermes-before-reset") + "\n"
                assertTrue(output == expected,
                    "The actual guest output must occur once without a namespace dump: ${output.length} characters, ${output.take(200)}")
            }
            assertEquals(HermesVmConsole.State.READY, console.state)
        } finally {
            withContext(NonCancellable) {
                controls.released.complete(Unit)
                server.stop(0)
                streams.toList().forEach { it.cancelAndJoin() }
                executor.shutdown()
                try {
                    assertTrue(withContext(Dispatchers.IO) { executor.awaitTermination(30, TimeUnit.SECONDS) })
                } finally {
                    try { wire.drain() } finally { owner.complete(); owner.join() }
                }
            }
        }
    }

    companion object {
        const val CONTROL = ":eval print('hermes-control')"
        const val BEFORE_RESET = ":eval print('hermes-before-reset')"
        // Fixture-only control for the production page's public drain; no terminal or transport substitute.
        private val OWNER_CONTROL = """
            <button id="ownerDrain" style="position:fixed;right:8px;bottom:8px;z-index:10000"
             onclick="const button=this; const panel=document.getElementById('terminalPanel');
              const post=async(path,body={})=>{
               const response=await fetch('/_test/'+path,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});
               if(!response.ok) throw new Error(await response.text()); return response.json();
              };
              const checkpoint=name=>post('checkpoint',{name,
               state:document.getElementById('state').textContent,
               screen:document.getElementById('screen').innerText,
               drain:button.dataset.state||'open',samePromise:button.dataset.same==='true',
               unchanged:panel.innerHTML===button.dataset.before});
              const pause=ms=>new Promise(resolve=>setTimeout(resolve,ms));
              (async()=>{
               await checkpoint('open');
               button.dataset.before=panel.innerHTML; button.dataset.state='pending';
               const first=window.closeHermes(); button.dataset.same=String(first===window.closeHermes());
               first.then(()=>{button.dataset.state='closed';button.textContent='Page drained';},
                error=>{button.dataset.state='failed';button.dataset.error=String(error);});
               await pause(100); await checkpoint('closing');
               await post('release'); await first; await checkpoint('closed');
               await pause(11000); await checkpoint('stable'); await post('finish');
              })().catch(async error=>{button.dataset.state='failed';button.dataset.error=String(error);await post('finish');});">
             Drain fixture page</button>
        """.trimIndent()
    }

    private data class Request(val path: String, val body: Map<String, Any?>)

    private class Controls(val console: HermesVmConsole, val scenario: String) {
        val finished = CountDownLatch(1)
        val armed = AtomicBoolean(false)
        val held = AtomicInteger()
        val released = CompletableDeferred<Unit>()
        val resetBeforeRelease = AtomicInteger()
        val requests = CopyOnWriteArrayList<Request>()
        val proofs = ConcurrentHashMap<String, Map<String, Any?>>()
        val streamOpened = AtomicInteger()
        val streamLive = AtomicInteger()
        val snapshotReads = AtomicInteger()
        val panelReads = AtomicInteger()
        val resizeRequests = AtomicInteger()
        val consumed = AtomicInteger()
        val proofCounts = ConcurrentHashMap<String, List<Int>>()
        val proofTimes = ConcurrentHashMap<String, Long>()
        val proofStreams = ConcurrentHashMap<String, Int>()

        suspend fun before(method: String, path: String, body: String) {
            if (scenario == "owner") {
                if (method == "GET" && path == "/api/hermes/terminal") {
                    snapshotReads.incrementAndGet()
                    if (armed.compareAndSet(true, false)) {
                        held.incrementAndGet()
                        released.await()
                        consumed.incrementAndGet()
                    }
                }
                if (method == "GET" && path == "/api/hermes/terminal/panels") panelReads.incrementAndGet()
                if (method == "POST" && path.endsWith("/resize")) resizeRequests.incrementAndGet()
            }
            if (method != "POST" || path !in setOf("/api/hermes/terminal/input", "/api/hermes/terminal/reset")) return
            requests.add(Request(path, reifyMap(body)))
            if (path.endsWith("/reset") && !released.isCompleted) resetBeforeRelease.incrementAndGet()
        }

        suspend fun after(method: String, path: String, body: String) {
            if (scenario != "owner" && method == "POST" && path.endsWith("/input") && reifyMap(body)["text"] is String &&
                armed.compareAndSet(true, false)) {
                held.incrementAndGet()
                released.await()
                if (scenario == "rejection") error("The text was accepted but its acknowledgement failed")
            }
        }

        fun route(path: String, body: String): borg.trikeshed.litebike.JvmKanbanServer.HttpResponse {
            val response = when (path) {
                "/_test/control" -> {
                    check(scenario == "owner" && streamLive.get() > 0)
                    val command = console.submit(":eval print('owner-' + 'live-marker')")
                    check(console.panel.signals().view.any { it.causeSignalId == command.id && it.kind == "completed" })
                    emptyMap<String, Any?>()
                }
                "/_test/arm" -> { check(!released.isCompleted && held.get() == 0); armed.set(true); emptyMap<String, Any?>() }
                "/_test/release" -> {
                    if (scenario == "owner" && !released.isCompleted) {
                        val command = console.submit(":eval print('owner-' + 'late-marker')")
                        check(console.panel.signals().view.any { it.causeSignalId == command.id && it.kind == "completed" })
                    }
                    released.complete(Unit); emptyMap<String, Any?>()
                }
                "/_test/checkpoint" -> {
                    val proof = reifyMap(body)
                    val name = proof["name"] as? String ?: error("Missing checkpoint name")
                    check(proofs.putIfAbsent(name, proof) == null)
                    proofCounts[name] = listOf(snapshotReads.get(), panelReads.get(), resizeRequests.get(), streamOpened.get())
                    proofTimes[name] = java.lang.System.nanoTime()
                    proofStreams[name] = streamLive.get()
                    emptyMap<String, Any?>()
                }
                "/_test/observations" -> mapOf("held" to held.get(), "released" to released.isCompleted,
                    "resetBeforeRelease" to resetBeforeRelease.get(),
                    "requests" to requests.map { mapOf("path" to it.path, "body" to it.body) },
                    "snapshotReads" to snapshotReads.get(), "panelReads" to panelReads.get(),
                    "resizeRequests" to resizeRequests.get(), "consumed" to consumed.get(),
                    "streamOpened" to streamOpened.get(), "streamLive" to streamLive.get(),
                    "console" to console.snapshotMap())
                "/_test/finish" -> { finished.countDown(); emptyMap<String, Any?>() }
                else -> error("Unknown fixture route")
            }
            return borg.trikeshed.litebike.JvmKanbanServer.HttpResponse(200, jsonOf(response))
        }

        fun assertOwner() {
            assertEquals(1, held.get(), "Hold one actual periodic snapshot read")
            assertTrue(released.isCompleted)
            assertEquals(1, consumed.get(), "The admitted request must reach the real snapshot route")
            assertEquals(setOf("open", "closing", "closed", "stable"), proofs.keys)
            assertTrue(proofStreams.getValue("open") > 0, "A live SSE connection must exist before close")
            assertTrue(proofCounts.getValue("open")[3] > 0, "At least one actual SSE connection must have opened")
            assertTrue(assertIs<String>(proofs.getValue("open")["screen"]).contains("owner-live-marker"),
                "Observe actual guest output before closing the page")
            val closing = proofs.getValue("closing")
            assertEquals("pending", closing["drain"], "Input-only completion does not join the outstanding snapshot")
            assertEquals(true, closing["samePromise"])
            val screen = assertIs<String>(closing["screen"])
            assertFalse(screen.contains("owner-late-marker"))
            for (name in arrayOf("closed", "stable")) {
                val proof = proofs.getValue(name)
                assertEquals("closed", proof["drain"])
                assertEquals(screen, proof["screen"], "Late snapshot/SSE output must not repaint a closed mount")
                assertEquals(true, proof["unchanged"], "Compare the actual DOM with the full pre-close HTML, not a hash")
                assertEquals(proofCounts.getValue("closing"), proofCounts.getValue(name),
                    "No further polls, resize requests, or SSE reconnects after close")
            }
            assertTrue(proofTimes.getValue("stable") - proofTimes.getValue("closed") >=
                TimeUnit.SECONDS.toNanos(11), "Observe beyond the real ten-second polling period")
            assertEquals(0, streamLive.get(), "The page must close its actual SSE connection")
            assertTrue(console.panel.terminal.plainText().contains("owner-late-marker"),
                "The refused late repaint must come from actual guest output, not an empty response")
        }
    }

    private suspend fun stream(exchange: HttpExchange, wire: HermesConsoleWire) {
        var opened = false
        wire.route(exchange.requestMethod, exchange.requestURI.toString(), "") { bytes ->
            if (!opened) {
                val head = bytes.decodeToString().split("\r\n\r\n", limit = 2)
                check(head.size == 2)
                val lines = head[0].split("\r\n")
                for (line in lines.drop(1)) {
                    val field = line.split(':', limit = 2)
                    check(field.size == 2)
                    exchange.responseHeaders.set(field[0], field[1].trim())
                }
                exchange.sendResponseHeaders(lines.first().split(' ')[1].toInt(), 0L)
                opened = true
                if (head[1].isNotEmpty()) exchange.responseBody.write(head[1].encodeToByteArray())
            } else exchange.responseBody.write(bytes)
            exchange.responseBody.flush()
        }
    }
}
