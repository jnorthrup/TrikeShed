package borg.literbike.ccek.agent8888

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Speculative Matcher - Fanout/Fanin for protocol detection
 *
 * Multiple parsers run in parallel. Longest match wins.
 * Winner determined by: completion OR confidence in partial completion.
 *
 * This module only knows about itself and the core traits.
 */

/**
 * Match confidence score (0-100)
 */
typealias Confidence = UByte

/**
 * Match result from a speculative parser
 */
data class MatchResult(
    /** How many bytes were consumed */
    val bytesMatched: Int,
    /** Confidence this is the correct protocol (0-100) */
    val confidence: Confidence,
    /** Whether parsing completed successfully */
    val complete: Boolean,
    /** Protocol identifier */
    val protocol: String,
    /** Time spent parsing */
    val elapsed: Duration = Duration.ZERO
)

/**
 * Score for ranking matches (higher = better)
 * Complete matches score highest, then by bytes matched, then confidence
 */
fun MatchResult.score(): ULong {
    val completeBonus = if (complete) 1_000_000_000uL else 0uL
    val bytesScore = bytesMatched.toULong() * 1000uL
    val confidenceScore = confidence.toULong()
    return completeBonus + bytesScore + confidenceScore
}

/**
 * Parser function type - takes input bytes, returns a match result
 */
typealias Parser = (ByteArray) -> MatchResult

/**
 * Speculative matcher - runs multiple parsers
 */
class SpeculativeMatcher(
    /** Maximum time to wait for results */
    var timeout: Duration = 10.milliseconds,
    /** Minimum confidence to accept a partial match */
    var minConfidence: Confidence = 80u
) {
    companion object {
        fun create() = SpeculativeMatcher()
    }

    fun withTimeout(timeout: Duration): SpeculativeMatcher {
        this.timeout = timeout
        return this
    }

    fun withMinConfidence(minConfidence: Confidence): SpeculativeMatcher {
        this.minConfidence = minConfidence
        return this
    }

    /**
     * Run parsers speculatively, return best match.
     *
     * Fanout: Run all parsers (in parallel with fibers/threads)
     * Fanin: Pick winner by score (longest match)
     */
    fun matchSpeculative(
        parsers: List<Parser>,
        input: ByteArray
    ): MatchResult? {
        val start = TimeSource.Monotonic.markNow()
        val results = mutableListOf<MatchResult>()

        // Fanout: Run all parsers
        for (parser in parsers) {
            val result = parser(input)
            results.add(result)

            // Early exit if we found a complete match with high confidence
            if (result.complete && result.confidence >= 95u) {
                return result
            }

            // Check timeout
            if (start.elapsedNow() > timeout) {
                break
            }
        }

        // Fanin: Pick winner by score (longest match)
        return results
            .filter { it.complete || it.confidence >= minConfidence }
            .maxByOrNull { MatchResult.score(it) }
    }
}

/**
 * Fiber/coroutine based speculative execution
 */
object Fiber {
    /**
     * Fiber handle for speculative parsing
     */
    class ParseFiber(
        val protocol: String
    ) {
        private val _bytesMatched = java.util.concurrent.atomic.AtomicInteger(0)
        private val _confidence = java.util.concurrent.atomic.AtomicInteger(0)
        private val _complete = java.util.concurrent.atomic.AtomicBoolean(false)

        fun update(bytes: Int, confidence: Confidence) {
            _bytesMatched.set(bytes)
            _confidence.set(confidence.toInt())
        }

        fun finish(bytes: Int, confidence: Confidence) {
            _bytesMatched.set(bytes)
            _confidence.set(confidence.toInt())
            _complete.set(true)
        }

        fun toResult(): MatchResult {
            return MatchResult(
                bytesMatched = _bytesMatched.get(),
                confidence = _confidence.get().toUByte(),
                complete = _complete.get(),
                protocol = protocol,
                elapsed = Duration.ZERO
            )
        }
    }

    fun createParseFiber(protocol: String): ParseFiber = ParseFiber(protocol)
}
