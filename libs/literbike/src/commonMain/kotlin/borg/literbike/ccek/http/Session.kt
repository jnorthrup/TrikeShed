package borg.literbike.ccek.http

/**
 * HTTP Session - relaxfactory Tx pattern
 *
 * Manages per-connection HTTP state: header buffer, read/write phases, response building
 */

/**
 * Session state machine
 */
enum class SessionState {
    ReadingHeaders,
    ReadingBody,
    Processing,
    Writing,
    Done
}

/**
 * Read phase
 */
enum class ReadPhase {
    Initial, Headers, Body
}

/**
 * Write phase
 */
enum class WritePhase {
    Idle, Headers, Body
}

/**
 * HTTP session
 */
class HttpSession {
    val parser: HeaderParser = HeaderParser.withCapacity(512)
    var state: SessionState = SessionState.ReadingHeaders
    var readPhase: ReadPhase = ReadPhase.Initial
    var writePhase: WritePhase = WritePhase.Idle
    var bodyBuffer: ByteArray = ByteArray(0)
    var responseBuffer: ByteArray = ByteArray(0)
    var expectedBodyLen: Int? = null
    var keepAlive: Boolean = true

    companion object {
        fun new(): HttpSession = HttpSession()
    }

    /**
     * Reset session for keep-alive reuse
     */
    fun reset() {
        parser.clear()
        state = SessionState.ReadingHeaders
        readPhase = ReadPhase.Initial
        writePhase = WritePhase.Idle
        bodyBuffer = ByteArray(0)
        responseBuffer = ByteArray(0)
        expectedBodyLen = null
        keepAlive = true
    }

    /**
     * Read data into header/body buffer
     */
    fun readData(data: ByteArray): Int {
        return when (state) {
            SessionState.ReadingHeaders -> {
                parser.append(data)
                readPhase = ReadPhase.Headers
                data.size
            }
            SessionState.ReadingBody -> {
                expectedBodyLen?.let { expected ->
                    val currentLen = bodyBuffer.size
                    if (currentLen < expected) {
                        val remaining = expected - currentLen
                        val toRead = minOf(data.size, remaining)
                        val newBuffer = ByteArray(currentLen + toRead)
                        bodyBuffer.copyInto(newBuffer, 0, 0, currentLen)
                        data.copyInto(newBuffer, currentLen, 0, toRead)
                        bodyBuffer = newBuffer
                        readPhase = ReadPhase.Body
                        return toRead
                    }
                }
                0
            }
            else -> 0
        }
    }

    /**
     * Try to parse headers
     */
    fun tryParseHeaders(): Result<Boolean> {
        if (state != SessionState.ReadingHeaders) return Result.success(false)

        return parser.parse().map { parsed ->
            if (parsed) {
                state = SessionState.ReadingBody

                parser.contentLength()?.let { len ->
                    expectedBodyLen = len
                    if (len == 0) {
                        state = SessionState.Processing
                    }
                } ?: run {
                    state = SessionState.Processing
                }

                // Move any body bytes already in parser to bodyBuffer
                parser.bodyOffset()?.let { offset ->
                    val buf = parser.buffer()
                    if (buf.size > offset) {
                        val bodyBytes = buf.copyOfRange(offset, buf.size)
                        val newBuffer = ByteArray(bodyBuffer.size + bodyBytes.size)
                        bodyBuffer.copyInto(newBuffer, 0, 0, bodyBuffer.size)
                        bodyBytes.copyInto(newBuffer, bodyBuffer.size)
                        bodyBuffer = newBuffer
                    }
                }

                // Check Connection header for keep-alive
                parser.header("Connection")?.let { conn ->
                    keepAlive = conn.equals("keep-alive", ignoreCase = true)
                } ?: run {
                    keepAlive = parser.protocol() == "HTTP/1.1"
                }

                true
            } else false
        }
    }

    /**
     * Check if body reading is complete
     */
    fun bodyComplete(): Boolean {
        return if (state == SessionState.ReadingBody && expectedBodyLen != null) {
            bodyBuffer.size >= expectedBodyLen!!
        } else true
    }

    /**
     * Mark body reading complete
     */
    fun finishReadingBody() {
        if (bodyComplete()) {
            state = SessionState.Processing
        }
    }

    /**
     * Prepare response for writing
     */
    fun prepareResponse(status: HttpStatus, contentType: String, body: ByteArray) {
        responseBuffer = parser.buildSimpleResponse(status, contentType, body)
        state = SessionState.Writing
        writePhase = WritePhase.Headers
    }

    /**
     * Write response data (returns bytes consumed)
     */
    fun writeData(maxBytes: Int): ByteArray {
        if (state != SessionState.Writing) return ByteArray(0)

        val toWrite = minOf(maxBytes, responseBuffer.size)
        val data = responseBuffer.copyOfRange(0, toWrite)
        responseBuffer = if (toWrite >= responseBuffer.size) {
            ByteArray(0)
        } else {
            responseBuffer.copyOfRange(toWrite, responseBuffer.size)
        }

        if (responseBuffer.isEmpty()) {
            writePhase = WritePhase.Idle
            if (keepAlive) {
                reset()
                state = SessionState.ReadingHeaders
            } else {
                state = SessionState.Done
            }
        }

        return data
    }

    // Getters
    fun method(): HttpMethod? = parser.method()
    fun path(): String? = parser.path()
    fun header(name: String): String? = parser.header(name)
    fun body(): ByteArray = bodyBuffer

    fun isDone(): Boolean = state == SessionState.Done
    fun wantsRead(): Boolean = state == SessionState.ReadingHeaders || state == SessionState.ReadingBody
    fun wantsWrite(): Boolean = state == SessionState.Writing
}
