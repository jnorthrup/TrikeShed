package borg.trikeshed.couch.requestfactory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

import borg.trikeshed.userspace.nio.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import kotlin.math.min

class RequestFactorySocketIntegrationTest {

    @Test
    fun socketRoundtrip_passesCcek() = runBlocking {
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
                val data = ByteArrayOutputStream()
                var headersStr: String? = null
                var contentLength = 0
                while (true) {
                    val n = conn.read(readBuf, 0, readBuf.size)
                    if (n <= 0) break
                    data.write(readBuf, 0, n)
                    val soFar = data.toString("UTF-8")
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
                        val bodyBytesSoFar = rest.toByteArray(Charsets.UTF_8)
                        var needed = contentLength - bodyBytesSoFar.size
                        val bodyOut = ByteArrayOutputStream()
                        bodyOut.write(bodyBytesSoFar)
                        while (needed > 0) {
                            val m = conn.read(readBuf, 0, min(readBuf.size, needed))
                            if (m <= 0) break
                            bodyOut.write(readBuf, 0, m)
                            needed -= m
                        }
                        val rawRequest = headersStr + "\r\n\r\n" + String(bodyOut.toByteArray(), Charsets.UTF_8)

                        // handle request
                        val serverImpl = RequestFactoryHtxServer(service)
                        val response = serverImpl.handle(rawRequest)

                        // write response back
                        val respBytes = response.toByteArray(Charsets.UTF_8)
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
            append("Content-Length: ${body.toByteArray().size}\r\n\r\n")
            append(body)
        }.toByteArray(Charsets.UTF_8)
        client.write(reqBytes, 0, reqBytes.size)

        // read response
        val respBuf = ByteArrayOutputStream()
        val tmp = ByteArray(4096)
        while (true) {
            val n = client.read(tmp, 0, tmp.size)
            if (n <= 0) break
            respBuf.write(tmp, 0, n)
        }
        val respStr = respBuf.toString("UTF-8")
        val respBody = respStr.substringAfter("\r\n\r\n")
        val resp = RequestFactoryJsonCodec.responseFromJson(respBody)
        assertTrue(resp.success)
        val v = resp.value as? TransportValue.StringValue
        assertEquals("hello world", v?.value)

        client.close()
        serverJob.join()
    }
}
