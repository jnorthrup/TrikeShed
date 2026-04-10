package borg.literbike.http_htx

/**
 * Speculative Matcher - Fanout/Fanin for HTTP protocol detection
 *
 * Multiple parsers run in parallel. Longest match wins.
 */

/**
 * Confidence level (0-100)
 */
typealias Confidence = UByte

/**
 * Match result from speculative parsing
 */
data class MatchResult(
    val bytesMatched: Int,
    val confidence: Confidence,
    val complete: Boolean,
    val protocol: String,
    val version: Pair<UByte, UByte>? = null,
    val elapsedMs: Long = 0L
) {
    fun score(): ULong {
        val completeBonus = if (complete) 1_000_000_000uL else 0uL
        val bytesScore = bytesMatched.toULong() * 1000uL
        val confidenceScore = confidence.toULong()
        return completeBonus + bytesScore + confidenceScore
    }
}

/**
 * Speculative matcher - runs multiple parsers in parallel
 */
class SpeculativeMatcher(
    private val timeoutMs: Long = 10L,
    private val minConfidence: UByte = 80u
) {
    companion object {
        fun new() = SpeculativeMatcher()
    }

    fun withTimeout(timeoutMs: Long): SpeculativeMatcher =
        SpeculativeMatcher(timeoutMs = timeoutMs, minConfidence = minConfidence)

    fun matchSpeculative(
        parsers: List<(ByteArray) -> MatchResult>,
        input: ByteArray
    ): MatchResult? {
        val startTime = System.currentTimeMillis()
        val results = mutableListOf<MatchResult>()

        for (parser in parsers) {
            val result = parser(input)
            results.add(result)

            if (result.complete && result.confidence >= 95u) {
                return result
            }

            if (System.currentTimeMillis() - startTime > timeoutMs) {
                break
            }
        }

        return results
            .filter { it.complete || it.confidence >= minConfidence }
            .maxByOrNull { it.score() }
    }
}

/**
 * HTTP/1 parser for speculative matching
 */
fun http1Parser(input: ByteArray): MatchResult {
    val startTime = System.currentTimeMillis()
    val text = input.decodeToString()

    // Check for HTTP/ response
    if (text.startsWith("HTTP/")) {
        val complete = text.contains("\r\n\r\n") || input.size >= 64
        return MatchResult(
            bytesMatched = minOf(input.size, 1024),
            confidence = if (complete) 100u else 60u,
            complete = complete,
            protocol = "HTTP/1.x",
            version = 1u to 1u,
            elapsedMs = System.currentTimeMillis() - startTime
        )
    }

    // Check for HTTP methods
    val methods = listOf("GET ", "POST ", "PUT ", "DELETE ", "HEAD ", "OPTIONS ", "PATCH ", "CONNECT ", "TRACE ")
    for (method in methods) {
        if (text.startsWith(method)) {
            val complete = text.contains("\r\n\r\n") || input.size >= 64
            return MatchResult(
                bytesMatched = text.indexOf(' ').takeIf { it >= 0 } ?: 4,
                confidence = if (complete) 100u else 70u,
                complete = complete,
                protocol = "HTTP/1.x",
                version = 1u to 1u,
                elapsedMs = System.currentTimeMillis() - startTime
            )
        }
    }

    return MatchResult(
        bytesMatched = 0, confidence = 0u, complete = false,
        protocol = "HTTP/1.x", elapsedMs = System.currentTimeMillis() - startTime
    )
}

/**
 * HTTP/2 parser for speculative matching
 */
fun http2Parser(input: ByteArray): MatchResult {
    val startTime = System.currentTimeMillis()

    // HTTP/2 connection preface
    val preface = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".toByteArray()
    if (input.size >= 24 && input.sliceArray(0 until 24).contentEquals(preface)) {
        return MatchResult(
            bytesMatched = 24, confidence = 100u, complete = true,
            protocol = "HTTP/2", version = 2u to 0u,
            elapsedMs = System.currentTimeMillis() - startTime
        )
    }

    // Check for HTTP/2 frame
    if (input.size >= 9) {
        val frameType = input[3]
        if (frameType == 0x04.toByte() || frameType == 0x01.toByte()) {
            return MatchResult(
                bytesMatched = 9, confidence = 90u, complete = false,
                protocol = "HTTP/2", version = 2u to 0u,
                elapsedMs = System.currentTimeMillis() - startTime
            )
        }
    }

    return MatchResult(
        bytesMatched = 0, confidence = 0u, complete = false,
        protocol = "HTTP/2", elapsedMs = System.currentTimeMillis() - startTime
    )
}

/**
 * HTTP/3 parser for speculative matching
 */
fun http3Parser(input: ByteArray): MatchResult {
    val startTime = System.currentTimeMillis()

    if (input.size >= 3) {
        if (input[0] == 0x00.toByte() || input[0] == 0x01.toByte()) {
            return MatchResult(
                bytesMatched = 3, confidence = 50u, complete = false,
                protocol = "HTTP/3", version = 3u to 0u,
                elapsedMs = System.currentTimeMillis() - startTime
            )
        }
    }

    return MatchResult(
        bytesMatched = 0, confidence = 0u, complete = false,
        protocol = "HTTP/3", elapsedMs = System.currentTimeMillis() - startTime
    )
}
