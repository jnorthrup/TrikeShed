package borg.literbike.modelmux

/**
 * Hardened SSE (Server-Sent Events) streaming.
 * Ported from literbike/src/modelmux/streaming.rs.
 */

/**
 * An SSE frame representing a single event.
 */
data class SseFrame(
    val event: String? = null,
    val data: String,
    val id: String? = null,
    val retry: Long? = null
) {
    companion object {
        /** Parse an SSE frame from a raw string */
        fun parse(raw: String): SseFrame? {
            var event: String? = null
            val data = StringBuilder()
            var id: String? = null
            var retry: Long? = null

            for (line in raw.lineSequence()) {
                when {
                    line.startsWith("event:") -> event = line.substring(6).trimStart()
                    line.startsWith("data:") -> {
                        if (data.isNotEmpty()) data.append('\n')
                        data.append(line.substring(5).trimStart())
                    }
                    line.startsWith("id:") -> id = line.substring(3).trimStart()
                    line.startsWith("retry:") -> retry = line.substring(6).trimStart().toLongOrNull()
                    // Lines starting with ":" are comments, ignored
                }
            }

            if (data.isEmpty() && event == null && id == null) return null

            return SseFrame(
                event = event,
                data = data.toString(),
                id = id,
                retry = retry
            )
        }
    }

    /** Serialize frame to SSE format */
    fun toSseString(): String {
        val output = StringBuilder()
        event?.let { output.append("event: $it\n") }
        id?.let { output.append("id: $it\n") }
        retry?.let { output.append("retry: $it\n") }

        for (line in data.lineSequence()) {
            output.append("data: $line\n")
        }

        output.append('\n')
        return output.toString()
    }

    /** Check if this is a "[DONE]" marker frame */
    fun isDoneMarker(): Boolean = data.trim() == "[DONE]"

    /** Extract token usage from JSON data if present */
    fun extractTokenUsage(): Long? {
        return null // Requires JSON parsing - simplified for KMP
    }
}

/**
 * Robust SSE frame parser handling partial frames across chunk boundaries.
 */
class SseFrameParser(private val maxBufferSize: Int = 1024 * 1024) {
    private val buffer = StringBuilder()

    /** Parse new chunk data, returning complete frames */
    fun parseChunk(chunk: String): List<SseFrame> {
        buffer.append(chunk)

        if (buffer.length > maxBufferSize) {
            println("SSE buffer exceeded max size, discarding partial data")
            buffer.clear()
            return emptyList()
        }

        val frames = mutableListOf<SseFrame>()
        var lastEnd = 0

        while (true) {
            val pos = buffer.toString().indexOf("\n\n", lastEnd)
            if (pos < 0) break

            val frameData = buffer.substring(lastEnd, pos)
            SseFrame.parse(frameData)?.let { frames.add(it) }
            lastEnd = pos + 2
        }

        if (lastEnd > 0) {
            buffer.delete(0, lastEnd)
        }

        return frames
    }

    /** Get any remaining data in buffer (incomplete frame) */
    fun flushBuffer(): SseFrame? {
        if (buffer.isEmpty()) return null
        val frame = SseFrame.parse(buffer.toString())
        buffer.clear()
        return frame
    }

    /** Clear the buffer */
    fun clear() {
        buffer.clear()
    }
}

/**
 * Streaming metrics for tracking.
 */
data class StreamingMetrics(
    var bytesStreamed: Long = 0,
    var framesParsed: Long = 0,
    var tokensTracked: Long = 0,
    val startTime: Long = Clocks.System.now(),
    var lastActivity: Long = Clocks.System.now(),
    var errorCount: Long = 0
) {
    fun recordBytes(bytes: Long) {
        bytesStreamed += bytes
        lastActivity = Clocks.System.now()
    }

    fun recordFrame() {
        framesParsed++
        lastActivity = Clocks.System.now()
    }

    fun recordTokens(tokens: Long) {
        tokensTracked += tokens
    }

    fun recordError() {
        errorCount++
    }

    fun durationMs(): Long = Clocks.System.now() - startTime

    fun tokensPerSecond(): Double {
        val secs = durationMs() / 1000.0
        return if (secs > 0) tokensTracked.toDouble() / secs else 0.0
    }

    fun idleTimeMs(): Long = Clocks.System.now() - lastActivity
}

/**
 * Pooled connection for streaming endpoints.
 */
data class StreamingConnection(
    val provider: String,
    val baseUrl: String,
    val createdAt: Long,
    var lastUsed: Long
) {
    fun isStale(): Boolean = (Clocks.System.now() - lastUsed) > 300_000 // 5 minutes
}

/**
 * Connection pool for streaming endpoints.
 */
class StreamingConnectionPool(private val maxConnectionsPerProvider: Int = 10) {
    private val connections = mutableMapOf<String, MutableList<StreamingConnection>>()

    suspend fun getConnection(provider: String): StreamingConnection? {
        val pool = connections[provider] ?: return null
        pool.removeAll { it.isStale() }
        return if (pool.isNotEmpty()) {
            val conn = pool.removeAt(pool.lastIndex)
            conn.lastUsed = Clocks.System.now()
            conn
        } else null
    }

    suspend fun returnConnection(conn: StreamingConnection) {
        val pool = connections.getOrPut(conn.provider) { mutableListOf() }
        if (pool.size < maxConnectionsPerProvider) {
            pool.add(conn)
        }
    }

    companion object {
        fun createConnection(provider: String, baseUrl: String): StreamingConnection {
            val now = Clocks.System.now()
            return StreamingConnection(provider, baseUrl, now, now)
        }
    }

    suspend fun clear() {
        connections.clear()
    }

    fun stats(): Map<String, Int> = connections.mapValues { it.value.size }
}
