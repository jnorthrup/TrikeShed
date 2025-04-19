package borg.trikeshed.reactor.http

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.datetime.formatRfc1123
import borg.trikeshed.lib.datetime.getCurrentDateTime
import borg.trikeshed.lib.j
import borg.trikeshed.reactor.*

class HttpStateMachine(private val socket: ClientChannel, private val buffer: ByteBuffer) {
    private val responseHeaders = mutableMapOf<String, String>()

    fun parseRequest(): Join<Interest, UnaryAsyncReaction>? {
        buffer.flip()

        // Parse request line
        val requestLine = extractLineFromBuffer(buffer, 0, buffer.position()) 
            ?: return OP_READ j { parseRequest() }
        
        // Parse headers
        val headers = parseHeaders(buffer)

        // Generate MOTD response
        val response = generateMotdResponse(headers)
        return writeResponse(response)
    }

    private fun writeResponse(response: String): Join<Interest, UnaryAsyncReaction>? {
        val responseBuffer = ByteBufferFactory.wrap(response.toByteArray())
        return OP_WRITE j { key ->
            socket.write(responseBuffer)
            if (!responseBuffer.hasRemaining()) {
                socket.close()
                null
            } else {
                writeResponse(response)
            }
        }
    }

    private fun generateMotdResponse(headers: Map<String, String>): String {
        val currentTime = getCurrentDateTime()
        val iso8601 = "${currentTime.year}-${currentTime.month}-${currentTime.day}T${currentTime.hour}:${currentTime.minute}:${currentTime.second}Z"
        val rfc1123 = formatRfc1123(currentTime)

        val headersEcho = headers.entries.joinToString("\n") { (k, v) -> "$k: $v" }
        val responseBody = """
            <html>
            <body>
                <h1>Message of the Day</h1>
                <p>ISO 8601: $iso8601</p>
                <p>RFC 1123: $rfc1123</p>
                <h2>Received Headers:</h2>
                <pre>$headersEcho</pre>
            </body>
            </html>
        """.trimIndent()

        responseHeaders["Content-Type"] = "text/html"
        responseHeaders["Content-Length"] = responseBody.length.toString()
        responseHeaders["Connection"] = "close"

        val responseHeadersFormatted = responseHeaders.entries.joinToString("\r\n") { (k, v) -> "$k: $v" }
        return "HTTP/1.1 200 OK\r\n$responseHeadersFormatted\r\n\r\n$responseBody"
    }
}
