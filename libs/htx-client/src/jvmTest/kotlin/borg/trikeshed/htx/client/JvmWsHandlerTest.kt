package borg.trikeshed.htx.client

import kotlinx.coroutines.runBlocking
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals

class JvmWsHandlerTest {
    @Test
    fun `handler sends request body as first websocket text frame`() = runBlocking {
        val subscribe = "{\"type\":\"subscribe\",\"channel\":\"ticker\",\"product_ids\":[\"BTC-USD\"]}"
        val receivedPayload = AtomicReference<String>()
        val ready = CountDownLatch(1)
        val done = CountDownLatch(1)

        ServerSocket(0).use { server ->
            val worker = thread(start = true) {
                server.accept().use { socket ->
                    socket.soTimeout = 2000
                    val input = BufferedInputStream(socket.getInputStream())
                    val output = socket.getOutputStream()
                    val requestBytes = ByteArrayOutputStream()
                    while (true) {
                        val next = input.read()
                        if (next < 0) break
                        requestBytes.write(next)
                        val bytes = requestBytes.toByteArray()
                        val n = bytes.size
                        if (n >= 4 && bytes[n - 4] == '\r'.code.toByte() && bytes[n - 3] == '\n'.code.toByte() && bytes[n - 2] == '\r'.code.toByte() && bytes[n - 1] == '\n'.code.toByte()) {
                            break
                        }
                    }

                    val key = Regex("Sec-WebSocket-Key: (.+)\\r\\n")
                        .find(requestBytes.toString(Charsets.UTF_8.name()))
                        ?.groupValues?.get(1)
                        ?: error("missing websocket key")
                    val accept = java.util.Base64.getEncoder().encodeToString(
                        java.security.MessageDigest.getInstance("SHA-1")
                            .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray())
                    )
                    output.write((
                        "HTTP/1.1 101 Switching Protocols\r\n" +
                            "Upgrade: websocket\r\n" +
                            "Connection: Upgrade\r\n" +
                            "Sec-WebSocket-Accept: $accept\r\n\r\n"
                        ).toByteArray())
                    output.flush()
                    ready.countDown()

                    try {
                        receivedPayload.set(readClientTextFrame(input))
                    } catch (_: SocketTimeoutException) {
                        receivedPayload.set("<timeout>")
                    }

                    output.write(serverTextFrame("{\"type\":\"ack\"}"))
                    output.flush()
                }
                done.countDown()
            }

            val handler = JvmWsHandler()
            val response = handler(
                HtxClientRequest(
                    path = "ws://127.0.0.1:${server.localPort}/feed",
                    body = subscribe,
                    transport = HtxTransport.WEBSOCKET,
                )
            )

            ready.await(2, TimeUnit.SECONDS)
            done.await(2, TimeUnit.SECONDS)
            worker.join(2000)

            assertEquals(subscribe, receivedPayload.get())
            assertEquals("{\"type\":\"ack\"}", response.body)
        }
    }

    private fun readClientTextFrame(input: BufferedInputStream): String {
        val b0 = input.read()
        require(b0 == 0x81) { "expected text frame, got $b0" }
        val b1 = input.read()
        require(b1 >= 0) { "missing payload byte" }
        val masked = (b1 and 0x80) != 0
        require(masked) { "client frame must be masked" }
        val initialLength = b1 and 0x7F
        val length = when (initialLength) {
            126 -> ((input.read() and 0xFF) shl 8) or (input.read() and 0xFF)
            127 -> error("test frame too large")
            else -> initialLength
        }
        val mask = ByteArray(4) { input.read().toByte() }
        val payload = ByteArray(length) { index ->
            val value = input.read()
            ((value and 0xFF) xor (mask[index % 4].toInt() and 0xFF)).toByte()
        }
        return payload.toString(Charsets.UTF_8)
    }

    private fun serverTextFrame(payload: String): ByteArray {
        val bytes = payload.toByteArray()
        require(bytes.size < 126)
        return byteArrayOf(0x81.toByte(), bytes.size.toByte()) + bytes
    }
}
