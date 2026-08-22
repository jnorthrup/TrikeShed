package borg.trikeshed.litebike

import borg.trikeshed.litebike.taxonomy.Protocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Fragment framing through [JvmLitebikeBindAdapter]: a request that arrives
 * in several TCP writes is reassembled on Content-Length before dispatch, and
 * a frame that never completes is bounded by the reassembly cap (413).
 */
class LitebikeFragmentFramingTest {

    private class Served(
        val port: Int,
        val listener: LitebikeListenerElement,
        val connections: ConnectionRegistry,
        val scope: CoroutineScope,
        val bind: Job,
        val requests: java.util.concurrent.atomic.AtomicInteger,
    ) {
        suspend fun close() {
            bind.cancelAndJoin()
            scope.cancel()
            listener.close()
            connections.closeAll()
        }
    }

    /** Bind on an ephemeral port with an echo worker on the Http slot. */
    private suspend fun serveEcho(): Served {
        val port = ServerSocket(0).use { it.localPort }
        val listener = LitebikeListenerElement().also { it.open() }
        val httpSlot = listener.register(Protocol.Http)
        val connections = ConnectionRegistry()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val requests = java.util.concurrent.atomic.AtomicInteger(0)
        scope.launch {
            while (true) {
                val msg = httpSlot.consume()
                requests.incrementAndGet()
                val text = msg.payload.decodeToString()
                val headEnd = text.indexOf("\r\n\r\n")
                val body = if (headEnd >= 0) text.substring(headEnd + 4) else ""
                val payload = body.encodeToByteArray()
                val wire = ("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: ${payload.size}\r\n" +
                    "Connection: close\r\n\r\n").encodeToByteArray() + payload
                msg.respond?.invoke(wire)
            }
        }
        val bind = scope.launch {
            JvmLitebikeBindAdapter.bindAndServe(listener, port = port, host = "127.0.0.1", connections = connections)
        }
        awaitPort(port)
        return Served(port, listener, connections, scope, bind, requests)
    }

    @Test
    fun threeWriteRequestIsReassembledIntoOneResponse() = runBlocking {
        val served = serveEcho()
        try {
            val body = """{"hello":"fragmented world","n":42}"""
            val raw = Socket("127.0.0.1", served.port).use { s ->
                s.soTimeout = 10_000
                val out = s.getOutputStream()
                out.write("POST /echo HTTP/1.1\r\n".encodeToByteArray()); out.flush()
                Thread.sleep(60)
                out.write("Host: x\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n".encodeToByteArray()); out.flush()
                Thread.sleep(60)
                out.write(body.encodeToByteArray()); out.flush()
                s.getInputStream().readBytes().decodeToString()
            }
            assertTrue(raw.startsWith("HTTP/1.1 200 OK\r\n"), raw)
            // exactly one response on the wire
            assertEquals(1, Regex("HTTP/1\\.1 \\d{3}").findAll(raw).count(), raw)
            assertEquals(1, served.requests.get())
            assertEquals(body, raw.substringAfter("\r\n\r\n"))
        } finally {
            served.close()
        }
    }

    @Test
    fun frameOverReassemblyCapGets413() = runBlocking {
        val served = serveEcho()
        try {
            val cap = JvmLitebikeBindAdapter.pendingCap(served.listener)
            val junk = ByteArray(cap + 4096) { 'a'.code.toByte() }
            val raw = Socket("127.0.0.1", served.port).use { s ->
                s.soTimeout = 10_000
                val out = s.getOutputStream()
                out.write("POST /big HTTP/1.1\r\nX-Junk: ".encodeToByteArray())
                runCatching { out.write(junk); out.flush() } // peer may close mid-write; the status line is what matters
                s.getInputStream().readBytes().decodeToString()
            }
            assertTrue(raw.startsWith("HTTP/1.1 413 Payload Too Large\r\n"), raw)
            assertEquals(0, served.requests.get())
            // the listener is still serving after the 413
            val ok = Socket("127.0.0.1", served.port).use { s ->
                s.soTimeout = 10_000
                s.getOutputStream().write("POST /echo HTTP/1.1\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".encodeToByteArray())
                s.getInputStream().readBytes().decodeToString()
            }
            assertTrue(ok.startsWith("HTTP/1.1 200 OK\r\n"), ok)
            assertEquals("ok", ok.substringAfter("\r\n\r\n"))
        } finally {
            served.close()
        }
    }

    private fun awaitPort(port: Int) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            try { Socket("127.0.0.1", port).close(); return } catch (_: Exception) { Thread.sleep(20) }
        }
        error("litebike never bound :$port")
    }
}
