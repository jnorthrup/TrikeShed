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
            // Bridge allows reactorOps.register(fd, READ) to lazily discover
            // a client socket that JvmChannelOperations.accept() already created.
            // This is the missing link that made readAsync hang on the previous
            // attempts.
            reactorOps = JvmReactorOperations(
                channelOpsBridge = { fd -> channelOps!!.getSelectableChannel(fd) }
            )
            val runner = ChannelRunner(channelOps!!, reactorOps!!)

            // Open TCP server socket — tcpListen registers Interest.ACCEPT
            // so the Selector wakes on incoming connections.
            val sockFd = runner.tcpListen("0.0.0.0", port)
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

            // Critical: tcpConnect() initially registers READ+WRITE — but a ServerSocketChannel
            // needs OP_ACCEPT to wake on incoming connections, not OP_READ. Overwrite interest
            // with ACCEPT (and keep READ in case we ever probe state) so poll() actually fires.
            reactorOps!!.register(serverFdRef, setOf(Interest.ACCEPT))

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
                            println("[ReactorServer] accepted clientFd=$clientFd, READ interest registered")
                            scope.launch { handleClient(runner, clientFd) }
                        } else {
                            println("[ReactorServer] accept returned $clientFd")
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
        val channel = channelOps!!.getSelectableChannel(clientFd) as? java.nio.channels.SocketChannel
        if (channel == null) {
            channelOps?.close(clientFd)
            return
        }

        try {
            // Suspend on READ via the deferred registered in readers{} when we
            // called runner.readAsync. The reactor's poll() will fire because
            // we registered the interest at accept time.
            val buffer = java.nio.ByteBuffer.allocate(8192)
            runner.readAsync(clientFd)
            val n = try { channel.read(buffer) } catch (e: Exception) { -1 }
            if (n <= 0) return

            buffer.flip()
            val request = StandardCharsets.UTF_8.decode(buffer).toString()
            buffer.clear()

            val path = request.lines().firstOrNull()?.split(" ")?.getOrNull(1) ?: "/"

            when (path) {
                "/events" -> handleSse(runner, clientFd, channel)
                "/big"    -> writeResponse(channel, 200, "text/plain", "x".repeat(200_000), runner, clientFd)
                "/burst"  -> writeBurst(channel, runner, clientFd)
                "/"       -> writeResponse(channel, 200, "text/html", indexHtml(), runner, clientFd)
                else      -> writeResponse(channel, 404, "text/plain", "Not Found", runner, clientFd)
            }
        } catch (e: Exception) {
            println("Error handling client: ${e.message}")
        } finally {
            channelOps?.close(clientFd)
        }
    }

    private suspend fun writeResponse(
        channel: java.nio.channels.SocketChannel,
        status: Int,
        contentType: String,
        body: String,
        runner: ChannelRunner? = null,
        clientFd: Int = -1,
    ) {
        val bodyBytes = body.toByteArray(StandardCharsets.UTF_8)
        val header = "HTTP/1.1 $status OK\r\n" +
                     "Content-Type: $contentType\r\n" +
                     "Connection: close\r\n" +
                     "Content-Length: ${bodyBytes.size}\r\n\r\n"
        val fullBytes = (header + body).toByteArray(StandardCharsets.UTF_8)
        writeFully(channel, java.nio.ByteBuffer.wrap(fullBytes), runner, clientFd)
    }

    /** Stress a non-blocking channel with 100 small frames. */
    private suspend fun writeBurst(
        channel: java.nio.channels.SocketChannel,
        runner: ChannelRunner,
        clientFd: Int,
    ) {
        val header = "HTTP/1.1 200 OK\r\n" +
                     "Content-Type: text/event-stream\r\n" +
                     "Cache-Control: no-cache\r\n" +
                     "Connection: close\r\n\r\n"
        writeFully(channel, java.nio.ByteBuffer.wrap(header.toByteArray(StandardCharsets.UTF_8)), runner, clientFd)
        for (i in 1..100) {
            val frame = "event: tick\ndata: {\"i\":$i,\"pad\":\"${"y".repeat(9000)}\"}\n\n"
            val buf = java.nio.ByteBuffer.wrap(frame.toByteArray(StandardCharsets.UTF_8))
            writeFully(channel, buf, runner, clientFd)
        }
    }

    /** Write a buffer to completion against a non-blocking channel.
     *
     *  When channel.write returns 0 (kernel send buffer full), we back-pressure
     *  through the reactor: runner.writeAsync puts a deferred in writers[fd]
     *  FIRST and registers OP_WRITE SECOND. Poll fires OP_WRITE → completes
     *  the deferred → coroutine resumes. Subsequent writes may need more
     *  wake cycles (single-slot writers{} means we replace the prior
     *  completed deferred as we re-suspend). */
    private suspend fun writeFully(
        channel: java.nio.channels.SocketChannel,
        buf: java.nio.ByteBuffer,
        runner: ChannelRunner? = null,
        clientFd: Int = -1,
    ) {
        var attempts = 0
        while (buf.hasRemaining()) {
            val n = try { channel.write(buf) } catch (e: Exception) { return }
            if (n > 0) {
                attempts = 0
                continue
            }
            if (runner != null && clientFd >= 0) {
                runner.writeAsync(clientFd)
                continue
            }
            if (++attempts > 1000) return
            Thread.`yield`()
        }
    }

    /**
     * Long-lived SSE. Headers + initial frame first, then collect reactor
     * events and pipe each into the open stream until client closes or the
     * stream cancels.
     */
    private suspend fun handleSse(
        runner: ChannelRunner,
        clientFd: Int,
        channel: java.nio.channels.SocketChannel,
    ) {
        val events = reactor?.kanbanEvents
        if (events == null) {
            writeResponse(channel, 503, "text/plain", "reactor not initialized", runner, clientFd)
            return
        }

        // Initial frame — no Content-Length and no Transfer-Encoding; raw SSE
        // is its own framing. We commit to keeping the connection open until
        // the client disconnects.
        val init = "event: initialized\ndata: {\"count\":${events.replayCache.size}}\n\n"
        val header = "HTTP/1.1 200 OK\r\n" +
                     "Content-Type: text/event-stream\r\n" +
                     "Cache-Control: no-cache\r\n" +
                     "Connection: close\r\n\r\n"
        try {
            writeFully(channel,
                java.nio.ByteBuffer.wrap((header + init).toByteArray(StandardCharsets.UTF_8)),
                runner, clientFd)
        } catch (e: Exception) {
            return  // client gone before we could send the preamble
        }

        // Stream events until the client disconnects. Use a ChannelRunner write
        // guard so we back-pressure through the reactor.
        val inv = scope.launch {
            events.collect { event ->
                val frame = "event: tick\ndata: ${eventToJsonCompact(event)}\n\n"
                try {
                    val bytes = frame.toByteArray(StandardCharsets.UTF_8)
                    writeFully(channel, java.nio.ByteBuffer.wrap(bytes), runner, clientFd)
                } catch (e: Exception) {
                    this.cancel()
                }
            }
        }
        try {
            inv.join()
        } catch (_: CancellationException) { /* normal on disconnect */ }
    }

    private fun eventToJsonCompact(event: Any): String {
        // Trim the fully-qualified class name to last segment so the wire
        // payload stays compact.
        val raw = event::class.qualifiedName ?: event.javaClass.simpleName
        val simple = raw.substringAfterLast('.')
        val suffix = when (event) {
            else -> ""
        }
        return """{"type":"$simple"$suffix}"""
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