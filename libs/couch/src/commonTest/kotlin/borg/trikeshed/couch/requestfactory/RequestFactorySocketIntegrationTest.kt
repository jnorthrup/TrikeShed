package borg.trikeshed.couch.requestfactory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

import borg.trikeshed.userspace.nio.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
import kotlin.math.min

/** Multiplatform byte accumulator — replaces java.io.ByteArrayOutputStream */
private class ByteAccumulator(initialCapacity: Int = 256) {
    private var buf = ByteArray(initialCapacity)
    private var pos = 0

    fun write(src: ByteArray, offset: Int, len: Int) {
        ensureCapacity(pos + len)
        src.copyInto(buf, pos, offset, offset + len)
        pos += len
    }

    fun toByteArray(): ByteArray = buf.copyOf(pos)

    fun toUtf8String(): String = buf.decodeToString(0, pos)

    private fun ensureCapacity(needed: Int) {
        if (needed > buf.size) {
            val newBuf = ByteArray(maxOf(buf.size * 2, needed))
            buf.copyInto(newBuf, 0, 0, pos)
            buf = newBuf
        }
    }
}

class RequestFactorySocketIntegrationTest {

    @Test
    fun socketRoundtrip_passesCcek() = runTest {
        // create listening socket (platform-specific actual implementation)
        val server = createListeningSocket("127.0.0.1", 0)
        // get bound port
        val port = (server.bindAddress as SocketAddress.Inet).port

        // service to be used by the server coroutine
        val service = object : RequestFactoryTransportService {
            override fun invoke(call: RequestFactoryCall): RequestFactoryResponse {
                // verify CCEK key was propagated
                assertEquals("sock-key", call.ccekKey)
                val arg0 = (call.arguments.first() as TransportValue.StringValue).value
                return RequestFactoryResponse(success = true, value = TransportValue.StringValue("hello $arg0"))
            }
        }

        val serverJob = launch {
            try {
                val conn = server.accept() ?: return@launch
                val readBuf = ByteArray(4096)
                val data = ByteAccumulator()
                var headersStr: String? = null
                var contentLength = 0
                while (true) {
                    val n = conn.read(readBuf, 0, readBuf.size)
                    if (n <= 0) break
                    data.write(readBuf, 0, n)
                    val soFar = data.toUtf8String()
                    val idx = soFar.indexOf("\r\n\r\n")
                    if (idx != -1) {
                        headersStr = soFar.substring(0, idx)
                        val rest = soFar.substring(idx + 4)
                        // parse content-length
                        val lines = headersStr.split("\r\n")
                        for (i in 1 until lines.size) {
                            val line = lines[i]
                            val p = line.indexOf(':')
                            if (p > 0) {
                                val name = line.substring(0, p).trim().lowercase()
                                val value = line.substring(p + 1).trim()
                                if (name == "content-length") contentLength = value.toInt()
                            }
                        }
                        val bodyBytesSoFar = rest.encodeToByteArray()
                        var needed = contentLength - bodyBytesSoFar.size
                        val bodyOut = ByteAccumulator()
                        bodyOut.write(bodyBytesSoFar, 0, bodyBytesSoFar.size)
                        while (needed > 0) {
                            val m = conn.read(readBuf, 0, min(readBuf.size, needed))
                            if (m <= 0) break
                            bodyOut.write(readBuf, 0, m)
                            needed -= m
                        }
                        val rawRequest = headersStr + "\r\n\r\n" + bodyOut.toUtf8String()

                        // handle request
                        val serverImpl = RequestFactoryHtxServer(service)
                        val response = serverImpl.handle(rawRequest)

                        // write response back
                        val respBytes = response.encodeToByteArray()
                        conn.write(respBytes, 0, respBytes.size)

                        conn.close()
                        break
                    }
                }
            } catch (e: Throwable) {
                e.printStackTrace()
            } finally {
                server.close()
            }
        }

        // client: build RF call and send over real socket via userspace ConnectedSocket
        val call = RequestFactoryCall(
            context = "test",
            method = "sayHello",
            arguments = listOf(TransportValue.StringValue("world")),
        )

        val clientExchange = RequestFactoryHtxClient().invoke(call)
        val body = clientExchange.body

        val client = createConnectedSocket("127.0.0.1", port)
        val reqBytes = buildString {
            append("POST ${clientExchange.request.path} HTTP/1.1\r\n")
            append("Host: localhost\r\n")
            append("Content-Type: ${clientExchange.contentType}\r\n")
            append("X-CCEK-Key: sock-key\r\n")
            append("Content-Length: ${body.encodeToByteArray().size}\r\n\r\n")
            append(body)
        }.encodeToByteArray()
        client.write(reqBytes, 0, reqBytes.size)

        // read response
        val respBuf = ByteAccumulator()
        val tmp = ByteArray(4096)
        while (true) {
            val n = client.read(tmp, 0, tmp.size)
            if (n <= 0) break
            respBuf.write(tmp, 0, n)
        }
        val respStr = respBuf.toUtf8String()
        val respBody = respStr.substringAfter("\r\n\r\n")
        val resp = RequestFactoryJsonCodec.responseFromJson(respBody)
        assertTrue(resp.success)
        val v = resp.value as? TransportValue.StringValue
        assertEquals("hello world", v?.value)

        client.close()
        serverJob.join()
    }
}
