package keymux.transport

import java.net.InetSocketAddress
import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.channels.AsynchronousFileChannel
import java.nio.channels.CompletionHandler
import java.nio.file.*
import javax.net.ssl.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import borg.trikeshed.lib.*
import borg.trikeshed.lib.`▶`
//temporary demo exception to the uring userspace nio without trekking far off the path 

// ── HTTP primitives in the algebra ──

typealias HeaderEntry = Join<String, String>
typealias HttpHeaders  = Series<HeaderEntry>

data class HttpRequest(
    val method:  String,
    val uri:     URI,
    val headers: Series<HeaderEntry> = 0 j { throw IndexOutOfBoundsException() },
    val body:    ByteBuffer? = null
)

data class HttpResponse(
    val status:  Int,
    val reason:  String,
    val headers: HttpHeaders,
    val body:    ByteBuffer
)

data class SseEvent(
    val id:    String?,
    val event: String?,
    val data:  String
)

// ── SSL engine wrapper ──

private class TlsChannel(
    private val ch: SocketChannel,
    engine: SSLEngine
) {
    private val engine = engine.apply { beginHandshake() }
    private val netBuf   = ByteBuffer.allocate(engine.session.packetBufferSize)
    private val appBuf   = ByteBuffer.allocate(engine.session.applicationBufferSize)
    private val emptyBuf = ByteBuffer.allocate(0)

    private fun runDelegated() {
        while (true) {
            val task = engine.delegatedTask ?: break
            task.run()
        }
    }

    suspend fun handshake() {
        var hs = engine.handshakeStatus
        netBuf.clear(); netBuf.flip()
        while (hs != SSLEngineResult.HandshakeStatus.FINISHED &&
               hs != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {
            when (hs) {
                SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                    val out = ByteBuffer.allocate(engine.session.packetBufferSize)
                    val r = engine.wrap(emptyBuf, out); runDelegated()
                    out.flip(); ch.write(out); hs = engine.handshakeStatus
                }
                SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
                    if (!netBuf.hasRemaining()) { netBuf.clear(); ch.read(netBuf); netBuf.flip() }
                    val app = ByteBuffer.allocate(engine.session.applicationBufferSize)
                    engine.unwrap(netBuf, app); runDelegated(); hs = engine.handshakeStatus
                }
                else -> { runDelegated(); hs = engine.handshakeStatus }
            }
        }
    }

    fun write(src: ByteBuffer) {
        val out = ByteBuffer.allocate(engine.session.packetBufferSize)
        while (src.hasRemaining()) {
            out.clear()
            engine.wrap(src, out); runDelegated()
            out.flip(); ch.write(out)
        }
    }

    fun readInto(dst: ByteBuffer): Int {
        val tmp = ByteBuffer.allocate(engine.session.packetBufferSize)
        val read = ch.read(tmp)
        if (read <= 0) return read
        tmp.flip()
        var total = 0
        while (tmp.hasRemaining()) {
            val before = dst.position()
            engine.unwrap(tmp, dst); runDelegated()
            total += dst.position() - before
        }
        return total
    }

    fun close() { engine.closeOutbound(); ch.close() }
}

// ── Core NIO HTTP ──

object NioHttp {

    private val sslCtx: SSLContext = SSLContext.getDefault()

    /** Single-shot HTTP request over NIO + TLS */
    suspend fun request(req: HttpRequest): HttpResponse = withContext(Dispatchers.IO) {
        val host = req.uri.host ?: error("no host")
        val port = req.uri.port.takeIf { it > 0 } ?: if (req.uri.scheme == "https") 443 else 80
        val ch = SocketChannel.open(InetSocketAddress(host, port))
        ch.configureBlocking(true)

        val tls = if (req.uri.scheme == "https") {
            val engine = sslCtx.createSSLEngine(host, port).apply {
                useClientMode = true
            }
            TlsChannel(ch, engine).also { it.handshake() }
        } else null

        try {
            // ── write request ──
            val bodyBytes = req.body?.let { bb ->
                val arr = ByteArray(bb.remaining()); bb.duplicate().get(arr); arr
            }
            val reqText = buildString {
                append("${req.method} ${req.uri.rawPath}${req.uri.rawQuery?.let { "?$it" } ?: ""} HTTP/1.1\r\n")
                append("Host: $host\r\n")
                for ((k, v) in req.headers.`▶`) {
                    append("$k: $v\r\n")
                }
                bodyBytes?.let { append("Content-Length: ${it.size}\r\n") }
                append("\r\n")
            }
            val reqBuf = ByteBuffer.wrap(reqText.toByteArray(Charsets.UTF_8))
            if (tls != null) tls.write(reqBuf) else ch.write(reqBuf)
            bodyBytes?.let {
                val bb = ByteBuffer.wrap(it)
                if (tls != null) tls.write(bb) else ch.write(bb)
            }

            // ── read response ──
            val acc = ByteBuffer.allocate(1 shl 16)   // 64K initial
            fun fill() {
                while (acc.position() == 0 || acc.position() == acc.limit()) {
                    acc.limit(acc.capacity())
                    val n = if (tls != null) tls.readInto(acc) else ch.read(acc)
                    if (n < 0) break
                }
            }
            fill(); acc.flip()

            // parse status line
            val line0 = readLine(acc)
            val parts = line0.split(" ", limit = 3)
            val status = parts.getOrElse(1) { "0" }.toInt()
            val reason = parts.getOrElse(2) { "" }

            // parse headers
            val hdrs = mutableListOf<HeaderEntry>()
            while (true) {
                val ln = readLine(acc)
                if (ln.isEmpty()) break
                val ci = ln.indexOf(':')
                if (ci >= 0) hdrs.add(ln.substring(0, ci).trim() j ln.substring(ci + 1).trim())
            }
            val headers: HttpHeaders = hdrs.toSeries()

            // remaining body
            val body = ByteBuffer.allocate(acc.remaining()).also { it.put(acc); it.flip() }
            HttpResponse(status, reason, headers, body)
        } finally {
            tls?.close() ?: ch.close()
        }
    }

    /** SSE stream as Flow<SseEvent> — stays open until cancelled or server closes */
    fun sse(req: HttpRequest): Flow<SseEvent> = flow {
        val host = req.uri.host ?: error("no host")
        val port = req.uri.port.takeIf { it > 0 } ?: 443
        val ch = SocketChannel.open(InetSocketAddress(host, port))
        ch.configureBlocking(true)
        val engine = sslCtx.createSSLEngine(host, port).apply { useClientMode = true }
        val tls = TlsChannel(ch, engine); tls.handshake()

        try {
            // write request
            val reqText = buildString {
                append("GET ${req.uri.rawPath}?${req.uri.rawQuery} HTTP/1.1\r\n")
                append("Host: $host\r\n")
                append("Accept: text/event-stream\r\n")
                for ((k, v) in req.headers.`▶`) {
                    append("$k: $v\r\n")
                }
                append("\r\n")
            }
            tls.write(ByteBuffer.wrap(reqText.toByteArray()))

            // skip HTTP headers
            val buf = ByteBuffer.allocate(1 shl 14)
            tls.readInto(buf); buf.flip()
            while (readLine(buf).isNotEmpty()) { /* skip header lines */ }

            // parse events
            var id: String? = null; var event: String? = null; var data = StringBuilder()
            while (true) {
                if (!buf.hasRemaining()) { buf.clear(); tls.readInto(buf); buf.flip() }
                if (!buf.hasRemaining()) break
                val ln = readLine(buf)
                when {
                    ln.startsWith("id:")      -> id = ln.removePrefix("id:").trim()
                    ln.startsWith("event:")   -> event = ln.removePrefix("event:").trim()
                    ln.startsWith("data:")    -> { if (data.isNotEmpty()) data.append("\n"); data.append(ln.removePrefix("data:").trim()) }
                    ln.isEmpty() && data.isNotEmpty() -> {
                        emit(SseEvent(id, event, data.toString()))
                        id = null; event = null; data = StringBuilder()
                    }
                }
            }
        } finally { tls.close() }
    }

    private fun readLine(bb: ByteBuffer): String {
        val sb = StringBuilder()
        while (bb.hasRemaining()) {
            val b = bb.get()
            if (b == '\n'.code.toByte()) return sb.toString().trimEnd('\r')
            sb.append(b.toInt().toChar())
        }
        return sb.toString()
    }
}

// ── NIO file store ──

object NioStore {

    suspend fun read(path: Path): ByteBuffer? = withContext(Dispatchers.IO) {
        if (!java.nio.file.Files.exists(path)) return@withContext null
        val ch = AsynchronousFileChannel.open(path, StandardOpenOption.READ)
        try {
            val size = ch.size()
            if (size > Int.MAX_VALUE) error("file too large")
            val buf = ByteBuffer.allocate(size.toInt())
            suspendCancellableCoroutine { cont ->
                ch.read(buf, 0, Unit, object : CompletionHandler<Int, Unit> {
                    override fun completed(result: Int, a: Unit) { buf.flip(); cont.resume(buf) }
                    override fun failed(ex: Throwable, a: Unit) { cont.resumeWithException(ex) }
                })
            }
        } finally { ch.close() }
    }

    suspend fun write(path: Path, data: ByteBuffer): Unit = withContext(Dispatchers.IO) {
        java.nio.file.Files.createDirectories(path.parent)
        val ch = AsynchronousFileChannel.open(path,
            StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
        try {
            suspendCancellableCoroutine { cont ->
                ch.write(data, 0, Unit, object : CompletionHandler<Int, Unit> {
                    override fun completed(result: Int, a: Unit) { cont.resume(Unit) }
                    override fun failed(ex: Throwable, a: Unit) { cont.resumeWithException(ex) }
                })
            }
        } finally { ch.close() }
    }

    /** NIO WatchService flow of change events for a directory tree */
    fun watch(root: Path): Flow<Join<Path, WatchEvent.Kind<*>>> = flow {
        val ws = FileSystems.getDefault().newWatchService()
        java.nio.file.Files.walk(root).use { paths ->
            paths.filter { java.nio.file.Files.isDirectory(it) }.forEach { dir ->
            dir.register(ws, StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE)
            }
        }
        while (true) {
            val key = ws.take()
            for (ev in key.pollEvents()) {
                val rel = key.watchable() as Path
                val full = rel.resolve(ev.context() as Path)
                emit(full j ev.kind())
            }
            if (!key.reset()) break
        }
    }
}
