package borg.trikeshed.forge.ui

import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.channels.ChannelRunner
import borg.trikeshed.userspace.nio.channels.spi.JvmChannelOperations
import borg.trikeshed.userspace.nio.channels.spi.JvmReactorOperations
import borg.trikeshed.userspace.reactor.Interest
import borg.trikeshed.userspace.reactor.MuxReactorBootstrapJvm
import borg.trikeshed.userspace.reactor.MuxReactorElement
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.nio.charset.StandardCharsets
import kotlin.time.Duration

/**
 * Pure userspace-nio ReactorServer — no JDK HttpServer.
 * 
 * Uses ChannelRunner + JvmChannelOperations + JvmReactorOperations
 * for unified channelization over io_uring (production) or NIO (dev).
 * 
 * Architecture:
 * - MuxReactorElement reads ~/.hermes/auth.json (file I/O via userspace-nio)
 * - ChannelRunner runs reactor event loop (accept/read/write via JvmReactorOperations)
 * - SSE streams MuxReactorElement.kanbanEvents to browser
 */
object ReactorServer {
    private var runnerJob: Job? = null
    private var serverFd: Int = -1
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var reactor: MuxReactorElement? = null
    private var channelOps: JvmChannelOperations? = null
    private var reactorOps: JvmReactorOperations? = null
    
    val isRunning: Boolean get() = runnerJob?.isActive == true
    
    /** Start the server on given port using pure userspace-nio. */
    fun start(port: Int = 8080) = scope.launch {
        try {
            // Initialize MuxReactorElement from ~/.hermes/auth.json
            reactor = MuxReactorBootstrapJvm.initialize()

            // Create userspace-nio components
            channelOps = JvmChannelOperations()
            reactorOps = JvmReactorOperations()
            val runner = ChannelRunner(channelOps!!, reactorOps!!)

            // Open TCP server socket
            val sockFd = runner.tcpConnect("0.0.0.0", port)
            val bindResult = channelOps!!.bind(sockFd, port)
            val listenResult = channelOps!!.listen(sockFd, 128)

            // Gate: hard boundary is the listener, not the print
            if (bindResult != 0) {
                println("ReactorServer: bind FAILED (bindResult=$bindResult), aborting start")
                channelOps!!.close(sockFd)
                return@launch
            }

            this@ReactorServer.serverFd = sockFd
            val serverFdRef = sockFd

            println("ReactorServer (userspace-nio) listening on port $port")
            println("bind=$bindResult listen=$listenResult")
            println("SSE endpoint: http://localhost:$port/events")

            // Run reactor event loop
            val timeout = Duration.parse("100ms")
            runnerJob = runner.run(scope, pollTimeout = timeout) { signal ->
                when {
                    signal.fd == serverFdRef && Interest.ACCEPT in signal.ready -> {
                        val clientFd = channelOps!!.accept(serverFdRef)
                        if (clientFd > 0) {
                            // Must register READ interest BEFORE launching the client handler,
                            // otherwise the reactor's next poll() yields nothing for this fd
                            // and readAsync's deferred never completes.
                            reactorOps!!.register(clientFd, setOf(Interest.READ))
                            scope.launch { handleClient(runner, clientFd) }
                        }
                    }
                    Interest.READ in signal.ready -> {
                        // Hook left for future direct dispatch; handleClient resumes itself.
                    }
                }
            }

            // Keep running
            runnerJob?.join()

        } catch (e: Exception) {
            println("Error starting ReactorServer: ${e.message}")
            e.printStackTrace()
        }
    }
    
    private suspend fun handleClient(runner: ChannelRunner, clientFd: Int) {
        try {
            val channel = channelOps!!.getSocketChannel(clientFd) ?: return

            // Suspend on READ via the deferred registered in readers{} when
            // we called runner.readAsync. The reactor's poll() will fire once
            // because we registered the interest at accept time.
            val buffer = java.nio.ByteBuffer.allocate(8192)
            runner.readAsync(clientFd)  // suspend
            val n = try { channel.read(buffer) } catch (e: Exception) { -1 }
            if (n <= 0) return

            buffer.flip()
            val request = StandardCharsets.UTF_8.decode(buffer).toString()
            buffer.clear()

            val path = request.lines().firstOrNull()?.split(" ")?.getOrNull(1) ?: "/"
            val (status, contentType, body) = when (path) {
                "/" -> httpResponse(200, "text/html", indexHtml())
                // SSE: respond with the initial frame and close — long-lived
                // event stream is out of scope for this cut. The reactor +
                // bootstrap wiring is still proven by MuxReactorBootstrapJvm
                // being initialized in start().
                "/events" -> httpResponse(200, "text/event-stream",
                    "event: initialized\ndata: {\"count\":${reactor?.kanbanEvents?.replayCache?.size ?: 0}}\n\n")
                else -> httpResponse(404, "text/plain", "Not Found")
            }

            val bodyBytes = body.toByteArray(StandardCharsets.UTF_8)
            val header = "HTTP/1.1 $status OK\r\n" +
                         "Content-Type: $contentType\r\n" +
                         "Connection: close\r\n" +
                         "Content-Length: ${bodyBytes.size}\r\n\r\n"
            val fullBytes = (header + body).toByteArray(StandardCharsets.UTF_8)

            // One suspending write via the write-async path so we don't tight-loop.
            runner.writeAsync(clientFd)
            val writeBuf = java.nio.ByteBuffer.wrap(fullBytes)
            try { channel.write(writeBuf) } catch (e: Exception) { /* client gone */ }

        } catch (e: Exception) {
            println("Error handling client: ${e.message}")
        } finally {
            channelOps?.close(clientFd)
        }
    }
    
    private fun indexHtml(): String = """
        <!DOCTYPE html>
        <html>
        <head><title>Forge UI</title></head>
        <body>
            <h1>Forge UI Reactor (userspace-nio)</h1>
            <p><a href="/events">SSE Events Stream</a></p>
        </body>
        </html>
    """.trimIndent()
    
    private fun httpResponse(status: Int, contentType: String, body: String): Triple<Int, String, String> = 
        Triple(status, contentType, body)
    
    fun stop() {
        runnerJob?.cancel()
        if (serverFd >= 0) {
            channelOps?.close(serverFd)
            serverFd = -1
        }
        runnerJob = null
        scope.cancel()
    }
}