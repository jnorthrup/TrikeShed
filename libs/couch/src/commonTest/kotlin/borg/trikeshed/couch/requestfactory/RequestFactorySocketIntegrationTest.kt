package borg.trikeshed.couch.requestfactory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

import java.net.ServerSocket
import java.net.Socket

class RequestFactorySocketIntegrationTest {

    @Test
    fun socketRoundtrip_passesCcek() {
        val serverSocket = ServerSocket(0)
        val port = serverSocket.localPort

        // service to be used by the server thread
        val service = object : RequestFactoryTransportService {
            override fun invoke(call: RequestFactoryCall): RequestFactoryResponse {
                // verify CCEK key was propagated
                assertEquals("sock-key", call.ccekKey)
                val arg0 = (call.arguments.first() as TransportValue.StringValue).value
                return RequestFactoryResponse(success = true, value = TransportValue.StringValue("hello $arg0"))
            }
        }

        val serverThread = Thread {
            try {
                serverSocket.use { ss ->
                    val sock = ss.accept()
                    sock.use { s ->
                        val input = s.getInputStream()
                        val out = s.getOutputStream()

                        // read until headers end \r\n\r\n
                        val readBuf = ByteArray(4096)
                        val data = java.io.ByteArrayOutputStream()
                        var headersStr: String? = null
                        var contentLength = 0
                        while (true) {
                            val n = input.read(readBuf)
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
                                // ensure we have full body
                                val bodyBytesSoFar = rest.toByteArray(Charsets.UTF_8)
                                var needed = contentLength - bodyBytesSoFar.size
                                val bodyOut = java.io.ByteArrayOutputStream()
                                bodyOut.write(bodyBytesSoFar)
                                while (needed > 0) {
                                    val m = input.read(readBuf, 0, Math.min(readBuf.size, needed))
                                    if (m <= 0) break
                                    bodyOut.write(readBuf, 0, m)
                                    needed -= m
                                }
                                val rawRequest = headersStr + "\r\n\r\n" + String(bodyOut.toByteArray(), Charsets.UTF_8)

                                // handle request
                                val server = RequestFactoryHtxServer(service)
                                val response = server.handle(rawRequest)

                                // write response back
                                out.write(response.toByteArray(Charsets.UTF_8))
                                out.flush()
                                break
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }

        serverThread.start()

        // client: build RF call and send over real socket
        val call = RequestFactoryCall(
            context = "test",
            method = "sayHello",
            arguments = listOf(TransportValue.StringValue("world")),
        )

        val clientExchange = RequestFactoryHtxClient().invoke(call)
        val body = clientExchange.body

        Socket("127.0.0.1", port).use { sock ->
            val out = sock.getOutputStream()
            val req = buildString {
                append("POST ${clientExchange.request.path} HTTP/1.1\r\n")
                append("Host: localhost\r\n")
                append("Content-Type: ${clientExchange.contentType}\r\n")
                append("X-CCEK-Key: sock-key\r\n")
                append("Content-Length: ${body.toByteArray().size}\r\n\r\n")
                append(body)
            }
            out.write(req.toByteArray(Charsets.UTF_8))
            out.flush()

            // read response
            val input = sock.getInputStream()
            val respBytes = input.readAllBytes()
            val respStr = String(respBytes, Charsets.UTF_8)
            val respBody = respStr.substringAfter("\r\n\r\n")
            val resp = RequestFactoryJsonCodec.responseFromJson(respBody)
            assertTrue(resp.success)
            val v = resp.value as? TransportValue.StringValue
            assertEquals("hello world", v?.value)
        }

        serverThread.join(2000)
        serverSocket.close()
    }
}
