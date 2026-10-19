package borg.trikeshed.terminal

import borg.trikeshed.forge.server.VmWire
import borg.trikeshed.litebike.JvmKanbanServer.HttpResponse
import borg.trikeshed.parse.*
import borg.trikeshed.pointcut.VmFacet
import borg.trikeshed.userspace.nio.platform.spi.SystemOperations
import borg.trikeshed.vm.*
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlin.test.*

/** Real VM terminal snapshots and SSE, with one request held across the production page drain. */
class VmTerminalBrowserTest {
    @Test
    @org.junit.jupiter.api.Timeout(value = 8, unit = TimeUnit.MINUTES)
    fun heldSnapshotCannotRepaintOrReconnectAfterPageDrain() = runBlocking<Unit> {
        org.junit.jupiter.api.Assumptions.assumeTrue(SystemOperations.default.getenv("TRIKESHED_VM_TERMINAL_UI") == "1")
        val host = HypervisorVmHost()
        val owner = SupervisorJob(coroutineContext[Job])
        val scope = CoroutineScope(coroutineContext.minusKey(Job) + owner + Dispatchers.IO)
        val wire = VmWire(host, scope)
        val controls = Controls(wire)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 16)
        val executor = Executors.newFixedThreadPool(6)
        val streams = ConcurrentHashMap.newKeySet<Job>()
        server.executor = executor
        try {
            host.spawn(VmSpec("page-owner", VmFacet.GRAAL_JS, VmTrust.OWN))
            assertEquals(200, wire.route("POST", "/api/vm/page-owner/eval",
                jsonOf(mapOf("source" to "print('vm-' + 'live-marker')")), null)!!.status)
            server.createContext("/") { exchange ->
                var streaming = false
                try {
                    val path = exchange.requestURI.path
                    if (path == VmWire.TERMINAL_EVENTS_PATH) {
                        streaming = true
                        controls.connections.incrementAndGet()
                        controls.liveConnections.incrementAndGet()
                        runBlocking {
                            val job = scope.launch(start = CoroutineStart.LAZY) { stream(exchange, wire) }
                            streams.add(job)
                            try { job.start(); job.join() }
                            finally { streams.remove(job); controls.liveConnections.decrementAndGet() }
                        }
                    } else {
                        val body = exchange.requestBody.readBytes().decodeToString()
                        val response = runBlocking {
                            when {
                                path.startsWith("/_test/") -> controls.route(path, body)
                                path.endsWith(".js") || path.endsWith(".css") -> HttpResponse(200, "",
                                    if (path.endsWith(".js")) "application/javascript" else "text/css",
                                    checkNotNull(javaClass.classLoader.getResourceAsStream("web$path")) { path }.use { it.readBytes() })
                                else -> {
                                    controls.before(exchange.requestMethod, path)
                                    wire.route(exchange.requestMethod, exchange.requestURI.toString(), body, null)
                                }
                            }
                        }
                        val content = response?.bytes ?: (response?.body ?: "{}").encodeToByteArray()
                        val bytes = if (path == "/vm-terminal") content.decodeToString()
                            .replace("</body>", CONTROL + "</body>").encodeToByteArray() else content
                        exchange.responseHeaders.set("Content-Type", response?.contentType ?: "application/json")
                        exchange.sendResponseHeaders(response?.status ?: 404, bytes.size.toLong())
                        exchange.responseBody.write(bytes)
                    }
                } finally { exchange.close() }
            }
            server.start()
            println("TRIKESHED_VM_TERMINAL_UI=http://127.0.0.1:${server.address.port}/vm-terminal?id=page-owner")
            assertTrue(withContext(Dispatchers.IO) { controls.finished.await(6, TimeUnit.MINUTES) }, "Native check did not finish")
            controls.verify()
            assertTrue(host.get("page-owner")!!.isAlive, "Closing a browser owner does not revoke its VM")
            assertTrue(host.terminals.get("page-owner")!!.panel.terminal.plainText().contains("vm-late-marker"))
        } finally {
            withContext(NonCancellable) {
                controls.released.complete(Unit)
                server.stop(0)
                streams.toList().forEach { it.cancelAndJoin() }
                executor.shutdown()
                try { assertTrue(withContext(Dispatchers.IO) { executor.awaitTermination(30, TimeUnit.SECONDS) }) }
                finally { host.close(); owner.complete(); owner.join() }
            }
        }
    }

    private class Controls(val wire: VmWire) {
        val finished = CountDownLatch(1)
        val armed = AtomicBoolean(false)
        val held = AtomicInteger()
        val consumed = AtomicInteger()
        val released = CompletableDeferred<Unit>()
        val snapshots = AtomicInteger()
        val lists = AtomicInteger()
        val resizes = AtomicInteger()
        val inputs = AtomicInteger()
        val connections = AtomicInteger()
        val liveConnections = AtomicInteger()
        val proofs = ConcurrentHashMap<String, Map<String, Any?>>()
        val counts = ConcurrentHashMap<String, List<Int>>()
        val times = ConcurrentHashMap<String, Long>()

        suspend fun before(method: String, path: String) {
            if (method == "GET" && path == "/api/vm/terminals") lists.incrementAndGet()
            if (method == "POST" && path.endsWith("/resize")) resizes.incrementAndGet()
            if (method == "POST" && path.endsWith("/input")) inputs.incrementAndGet()
            if (method == "GET" && path == "/api/vm/page-owner/terminal") {
                snapshots.incrementAndGet()
                if (armed.compareAndSet(true, false)) {
                    held.incrementAndGet()
                    released.await()
                    consumed.incrementAndGet()
                }
            }
        }

        suspend fun route(path: String, body: String): HttpResponse {
            val result = when (path) {
                "/_test/arm" -> { check(held.get() == 0 && liveConnections.get() > 0); armed.set(true); emptyMap<String, Any?>() }
                "/_test/release" -> {
                    assertEquals(200, wire.route("POST", "/api/vm/page-owner/eval",
                        jsonOf(mapOf("source" to "print('vm-' + 'late-marker')")), null)!!.status)
                    released.complete(Unit); emptyMap<String, Any?>()
                }
                "/_test/checkpoint" -> {
                    val proof = reifyMap(body)
                    val name = assertIs<String>(proof["name"])
                    check(proofs.putIfAbsent(name, proof) == null)
                    counts[name] = listOf(snapshots.get(), lists.get(), resizes.get(), inputs.get(), connections.get())
                    times[name] = System.nanoTime()
                    emptyMap<String, Any?>()
                }
                "/_test/observations" -> mapOf("held" to held.get(), "consumed" to consumed.get(), "connections" to connections.get(), "proofs" to proofs, "counts" to counts)
                "/_test/finish" -> { finished.countDown(); emptyMap<String, Any?>() }
                else -> error("Unknown test route")
            }
            return HttpResponse(200, jsonOf(result))
        }

        fun verify() {
            assertEquals(1, held.get())
            assertEquals(1, consumed.get())
            assertEquals(setOf("open", "closing", "closed", "stable"), proofs.keys)
            assertTrue(assertIs<String>(proofs.getValue("open")["screen"]).contains("vm-live-marker"))
            val before = proofs.getValue("closing")
            for (name in arrayOf("closed", "stable")) {
                val proof = proofs.getValue(name)
                assertEquals(before["screen"], proof["screen"], "Late real VM output repainted a closed page")
                assertEquals(true, proof["unchanged"], "Closing must preserve the whole page, not only its terminal text")
                assertEquals(counts.getValue("closing"), counts.getValue(name), "Polling, input, resize or reconnect escaped the page owner")
                assertEquals("closed", proof["drain"])
            }
            assertEquals("pending", before["drain"], "Drain must await the admitted snapshot")
            assertEquals(true, before["samePromise"])
            assertEquals(true, before["hasClose"])
            assertTrue(times.getValue("stable") - times.getValue("closed") >= TimeUnit.SECONDS.toNanos(11))
            assertEquals(0, liveConnections.get(), "The actual terminal SSE connection must close")
            println("VM_PAGE_DRAIN=" + jsonOf(mapOf("counts" to counts, "liveConnections" to liveConnections.get(),
                "proofs" to proofs.mapValues { (_, proof) -> proof - "screen" })))
        }
    }

    private suspend fun stream(exchange: HttpExchange, wire: VmWire) {
        var opened = false
        wire.route("GET", VmWire.TERMINAL_EVENTS_PATH, "") { bytes ->
            if (!opened) {
                val pieces = bytes.decodeToString().split("\r\n\r\n", limit = 2)
                exchange.responseHeaders.set("Content-Type", "text/event-stream")
                exchange.responseHeaders.set("Cache-Control", "no-cache")
                exchange.sendResponseHeaders(200, 0)
                opened = true
                if (pieces[1].isNotEmpty()) exchange.responseBody.write(pieces[1].encodeToByteArray())
            } else exchange.responseBody.write(bytes)
            exchange.responseBody.flush()
        }
    }

    companion object {
        // Fixture-only native button: observe DOM and call the real public close, never replace transports.
        private val CONTROL = """
            <button id="ownerDrain" style="position:fixed;right:8px;bottom:45px;z-index:10000"
             onclick="const button=this; button.disabled=true;
              const post=async(path,body={})=>{const r=await fetch('/_test/'+path,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});if(!r.ok)throw Error(await r.text());return r.json();};
              const pause=ms=>new Promise(resolve=>setTimeout(resolve,ms));
              const html=()=>Array.from(document.body.children).filter(el=>el!==button).map(el=>el.outerHTML).join('');
              const checkpoint=name=>post('checkpoint',{name,screen:document.getElementById('screen').innerText,
               drain:button.dataset.state||'open',samePromise:button.dataset.same==='true',hasClose:typeof window.closeVmTerminal==='function',unchanged:html()===button.dataset.before});
              (async()=>{
               document.getElementById('mode').value='stdin';
               await post('arm');button.textContent='Waiting for real periodic snapshot';
               for(let n=0;n<240;n++){if((await post('observations')).held===1)break;if(n===239)throw Error('No held snapshot');await pause(100);}
               await checkpoint('open');button.dataset.before=html();button.dataset.state='pending';
               let first;
               if(typeof window.closeVmTerminal==='function'){first=window.closeVmTerminal();button.dataset.same=String(first===window.closeVmTerminal());}
               else{window.dispatchEvent(new Event('pagehide'));first=Promise.resolve();}
               first.then(()=>{button.dataset.state='closed';button.textContent='Page drained';},e=>{button.dataset.state='failed';button.textContent=String(e);});
               await pause(100);await checkpoint('closing');await post('release');await first;await pause(300);await checkpoint('closed');
               document.getElementById('command').value='must-not-submit';document.getElementById('send').click();
               window.dispatchEvent(new Event('resize'));
               await pause(11000);await checkpoint('stable');await post('finish');
              })().catch(async error=>{button.dataset.state='failed';button.textContent=String(error);await post('finish');});">
             Check VM page drain</button>
        """.trimIndent()
    }
}
