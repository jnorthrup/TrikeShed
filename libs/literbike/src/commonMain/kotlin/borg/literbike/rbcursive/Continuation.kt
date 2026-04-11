package borg.literbike.rbcursive

/**
 * Continuation-based streaming parser for network protocols.
 * Handles partial data reception and stateful parsing.
 * Ported from literbike/src/rbcursive/continuation.rs.
 */

import borg.literbike.rbcursive.ParseError
import borg.literbike.rbcursive.Parser
import borg.literbike.rbcursive.ParseResult

/**
 * Streaming parser with continuation support.
 */
class StreamParser<T>(
    private val maxBufferSize: Int = 1024
) {
    private val buffer = mutableListOf<Byte>()
    private var state: StreamState<T> = StreamState.Ready

    /** Parser state for continuation handling */
    private sealed class StreamState<out T> {
        object Ready : StreamState<Nothing>()
        data class Complete<T>(val result: T) : StreamState<T>()
        data class Error(val error: ParseError) : StreamState<Nothing>()
    }

    /** Result from stream parsing attempt */
    sealed class StreamParseResult<out T> {
        data class Complete(val consumed: Int) : StreamParseResult<Nothing>()
        data class NeedMoreData(val bufferSize: Int) : StreamParseResult<Nothing>()
        object AlreadyComplete : StreamParseResult<Nothing>()
        data class ErrorState(val error: ParseError) : StreamParseResult<Nothing>()
    }

    /** Result from feeding data to the stream */
    sealed class StreamFeedResult {
        object Ok : StreamFeedResult()
        data class DataAdded(val size: Int) : StreamFeedResult()
        object AlreadyComplete : StreamFeedResult()
        data class ErrorState(val error: ParseError) : StreamFeedResult()
    }

    /** Feed data to the parser */
    fun feed(data: ByteArray): StreamFeedResult {
        // Check buffer size limit
        if (buffer.size + data.size > maxBufferSize) {
            state = StreamState.Error(ParseError.InvalidLength)
            return StreamFeedResult.ErrorState(ParseError.InvalidLength)
        }

        // Add data to buffer
        buffer.addAll(data.toList())

        return when (state) {
            is StreamState.Complete<*> -> StreamFeedResult.AlreadyComplete
            is StreamState.Error -> StreamFeedResult.ErrorState((state as StreamState.Error).error)
            StreamState.Ready -> StreamFeedResult.Ok
        }
    }

    /** Attempt to parse with given parser */
    fun tryParse(parser: Parser<T>): StreamParseResult<T> {
        return when (state) {
            is StreamState.Complete<*> -> StreamParseResult.AlreadyComplete
            is StreamState.Error -> StreamParseResult.ErrorState((state as StreamState.Error).error)
            StreamState.Ready -> {
                val data = buffer.toByteArray()
                when (val result = parser.parse(data)) {
                    is ParseResult.Complete -> {
                        // Remove consumed bytes from buffer
                        repeat(result.consumed) { buffer.removeAt(0) }
                        state = StreamState.Complete(result.value)
                        StreamParseResult.Complete(result.consumed)
                    }
                    is ParseResult.Incomplete -> {
                        StreamParseResult.NeedMoreData(buffer.size)
                    }
                    is ParseResult.Error -> {
                        // Remove consumed bytes even on error
                        repeat(result.consumed) { buffer.removeAt(0) }
                        state = StreamState.Error(result.error)
                        StreamParseResult.ErrorState(result.error)
                    }
                }
            }
        }
    }

    /** Get the parsed result if complete */
    fun takeResult(): T? {
        return when (val currentState = state) {
            is StreamState.Complete<*> -> {
                state = StreamState.Ready
                @Suppress("UNCHECKED_CAST")
                currentState.result as T?
            }
            else -> null
        }
    }

    /** Check if parsing is complete */
    fun isComplete(): Boolean = state is StreamState.Complete<*>

    /** Check if there's an error */
    fun isError(): Boolean = state is StreamState.Error

    /** Get current buffer size */
    fun bufferSize(): Int = buffer.size

    /** Clear the buffer and reset state */
    fun reset() {
        buffer.clear()
        state = StreamState.Ready
    }

    /** Get remaining buffer data without consuming */
    fun peekBuffer(): ByteArray = buffer.toByteArray()
}

/**
 * Multi-parser stream handler for protocol detection.
 */
class MultiStreamParser(
    private val maxBufferSize: Int = 1024,
    private val maxAttempts: Int = 10
) {
    private val buffer = mutableListOf<Byte>()
    private var attempts = 0

    /** Result of multi-parser attempt */
    sealed class MultiParseResult<out T> {
        data class Success<T>(
            val result: T,
            val parserIndex: Int,
            val consumed: Int,
            val remaining: Int
        ) : MultiParseResult<T>()
        data class NeedMoreData(val bufferSize: Int, val attempts: Int) : MultiParseResult<Nothing>()
        object BufferFull : MultiParseResult<Nothing>()
        object TooManyAttempts : MultiParseResult<Nothing>()
    }

    /** Feed data and try multiple parsers */
    fun feedAndTry(
        data: ByteArray,
        parsers: List<Parser<T>>
    ): MultiParseResult<T> {
        // Add data to buffer
        if (buffer.size + data.size > maxBufferSize) {
            return MultiParseResult.BufferFull
        }

        buffer.addAll(data.toList())
        attempts++

        if (attempts > maxAttempts) {
            return MultiParseResult.TooManyAttempts
        }

        val bufferData = buffer.toByteArray()

        // Try each parser
        for ((index, parser) in parsers.withIndex()) {
            when (val result = parser.parse(bufferData)) {
                is ParseResult.Complete<*> -> {
                    // Remove consumed bytes
                    repeat(result.consumed) { buffer.removeAt(0) }
                    @Suppress("UNCHECKED_CAST")
                    return MultiParseResult.Success(
                        result.value as T,
                        index,
                        result.consumed,
                        buffer.size
                    )
                }
                is ParseResult.Incomplete -> continue
                is ParseResult.Error -> continue
            }
        }

        return MultiParseResult.NeedMoreData(buffer.size, attempts)
    }

    /** Reset the multi-parser state */
    fun reset() {
        buffer.clear()
        attempts = 0
    }
}

/**
 * Continuation for stateful parsing across multiple feed operations.
 */
class ParseContinuation<T>(
    private var parserState: (ByteArray) -> ContinuationResult<T>
) {
    /** Continue parsing with new data */
    fun continueWith(data: ByteArray): ContinuationResult<T> = parserState(data)
}

/**
 * Result of continuation parsing.
 */
sealed class ContinuationResult<out T> {
    data class Complete<T>(val result: T, val consumed: Int) : ContinuationResult<T>()
    data class Continue(val consumed: Int) : ContinuationResult<Nothing>()
    data class ErrorState(val error: ParseError, val consumed: Int) : ContinuationResult<Nothing>()
}
