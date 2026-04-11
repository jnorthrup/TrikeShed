package borg.literbike.rbcursive

/**
 * Parser combinator framework with continuations for network protocols.
 * Zero-allocation, high-performance parsing with SIMD integration.
 * Ported from literbike/src/rbcursive/combinators.rs.
 */

/**
 * Core parser interface for network protocol parsing.
 */
interface Parser<T> {
    /** Parse input, returning result and consumed bytes */
    fun parse(input: ByteArray): ParseResult<T>
}

/**
 * Parse result with continuation support for streaming.
 */
sealed class ParseResult<out T> {
    data class Complete<T>(val value: T, val consumed: Int) : ParseResult<T>()
    data class Incomplete(val consumed: Int) : ParseResult<Nothing>()
    data class Error(val error: ParseError, val consumed: Int) : ParseResult<Nothing>()

    /** Extract the value if complete */
    fun intoComplete(): Pair<T, Int>? = when (this) {
        is Complete -> value to consumed
        else -> null
    }

    /** Check if result is complete */
    fun isComplete(): Boolean = this is Complete

    /** Get consumed bytes count */
    fun consumed(): Int = when (this) {
        is Complete -> consumed
        is Incomplete -> consumed
        is Error -> consumed
    }

    /** Functor map over the successful value */
    fun <U> map(f: (T) -> U): ParseResult<U> = when (this) {
        is Complete -> Complete(f(value), consumed)
        is Incomplete -> Incomplete(consumed)
        is Error -> Error(error, consumed)
    }

    /** Map the error type */
    fun <E : Any> mapErr(f: (ParseError) -> E): ParseResult<T> = when (this) {
        is Complete -> this
        is Incomplete -> this
        is Error -> Error(f(error), consumed)
    }

    /** Collapse to a coarse signal for parser outcomes */
    fun signal(): Signal = when (this) {
        is Complete -> Signal.Accept
        is Incomplete -> Signal.NeedMore
        is Error -> Signal.Reject
    }
}

/**
 * Coarse-grained signal for parser outcomes.
 */
enum class Signal {
    Accept,
    NeedMore,
    Reject
}

/**
 * Parse error types.
 */
enum class ParseError {
    InvalidInput,
    UnexpectedEnd,
    InvalidProtocol,
    InvalidMethod,
    InvalidHeader,
    InvalidLength
}

/**
 * Basic byte parser.
 */
class ByteParser(private val target: Byte) : Parser<Byte> {
    override fun parse(input: ByteArray): ParseResult<Byte> {
        if (input.isEmpty()) return ParseResult.Incomplete(0)
        return if (input[0] == target) {
            ParseResult.Complete(input[0], 1)
        } else {
            ParseResult.Error(ParseError.InvalidInput, 0)
        }
    }
}

/**
 * Take exact number of bytes.
 */
class TakeParser(private val count: Int) : Parser<ByteArray> {
    override fun parse(input: ByteArray): ParseResult<ByteArray> {
        if (input.size < count) return ParseResult.Incomplete(input.size)
        return ParseResult.Complete(input.copyOf(count), count)
    }
}

/**
 * Take until delimiter.
 */
class TakeUntilParser(
    private val delimiter: Byte,
    private val scanner: SimdScanner
) : Parser<ByteArray> {
    override fun parse(input: ByteArray): ParseResult<ByteArray> {
        if (input.isEmpty()) return ParseResult.Incomplete(0)
        val positions = scanner.scanBytes(input, byteArrayOf(delimiter))
        return if (positions.isNotEmpty()) {
            val pos = positions[0]
            ParseResult.Complete(input.copyOf(pos), pos)
        } else {
            ParseResult.Incomplete(input.size)
        }
    }
}

/**
 * Take while predicate is true.
 */
class TakeWhileParser(
    private val predicate: (Byte) -> Boolean
) : Parser<ByteArray> {
    override fun parse(input: ByteArray): ParseResult<ByteArray> {
        var count = 0
        for (byte in input) {
            if (predicate(byte)) count++ else break
        }
        return ParseResult.Complete(input.copyOf(count), count)
    }
}

/**
 * Tag parser - match exact byte sequence.
 */
class TagParser(private val tag: ByteArray) : Parser<ByteArray> {
    override fun parse(input: ByteArray): ParseResult<ByteArray> {
        if (input.size < tag.size) return ParseResult.Incomplete(input.size)
        return if (input.startsWith(tag)) {
            ParseResult.Complete(input.copyOf(tag.size), tag.size)
        } else {
            ParseResult.Error(ParseError.InvalidInput, 0)
        }
    }
}

/**
 * Sequence combinator - parse A then B.
 */
class SequenceParser<A, B>(
    private val first: Parser<A>,
    private val second: Parser<B>
) : Parser<Pair<A, B>> {
    override fun parse(input: ByteArray): ParseResult<Pair<A, B>> {
        return when (val firstResult = first.parse(input)) {
            is ParseResult.Complete -> {
                val remaining = input.sliceArray(firstResult.consumed until input.size)
                when (val secondResult = second.parse(remaining)) {
                    is ParseResult.Complete -> ParseResult.Complete(
                        firstResult.value to secondResult.value,
                        firstResult.consumed + secondResult.consumed
                    )
                    is ParseResult.Incomplete -> ParseResult.Incomplete(firstResult.consumed + secondResult.consumed)
                    is ParseResult.Error -> ParseResult.Error(secondResult.error, firstResult.consumed + secondResult.consumed)
                }
            }
            is ParseResult.Incomplete -> firstResult
            is ParseResult.Error -> firstResult
        }
    }
}

/**
 * Alternative combinator - try A, if fails try B.
 */
class AlternativeParser<T>(
    private val first: Parser<T>,
    private val second: Parser<T>
) : Parser<T> {
    override fun parse(input: ByteArray): ParseResult<T> {
        return when (val firstResult = first.parse(input)) {
            is ParseResult.Complete -> firstResult
            is ParseResult.Incomplete -> firstResult
            is ParseResult.Error -> second.parse(input)
        }
    }
}

/**
 * Map combinator - transform parser result.
 */
class MapParser<T, U>(
    private val parser: Parser<T>,
    private val mapper: (T) -> U
) : Parser<U> {
    override fun parse(input: ByteArray): ParseResult<U> {
        return when (val result = parser.parse(input)) {
            is ParseResult.Complete -> ParseResult.Complete(mapper(result.value), result.consumed)
            is ParseResult.Incomplete -> ParseResult.Incomplete(result.consumed)
            is ParseResult.Error -> ParseResult.Error(result.error, result.consumed)
        }
    }
}

/**
 * ByteRangeWhileParser - Parse a run of bytes within [start..=end].
 */
class ByteRangeWhileParser(
    private val start: Byte,
    private val end: Byte,
    private val min: Int,
    private val max: Int? = null
) : Parser<ByteArray> {
    override fun parse(input: ByteArray): ParseResult<ByteArray> {
        if (input.isEmpty()) return ParseResult.Incomplete(0)
        var len = 0
        val bound = max?.coerceAtMost(input.size) ?: input.size
        while (len < bound) {
            val b = input[len]
            if (b < start || b > end) break
            len++
        }
        return if (len < min) {
            if (input.size < min) ParseResult.Incomplete(input.size)
            else ParseResult.Error(ParseError.InvalidInput, len)
        } else {
            ParseResult.Complete(input.copyOf(len), len)
        }
    }
}

/**
 * ConfixParser - Parse content enclosed by open/close bytes.
 */
class ConfixParser(
    private val open: Byte,
    private val close: Byte,
    private val allowNested: Boolean = false
) : Parser<ByteArray> {
    override fun parse(input: ByteArray): ParseResult<ByteArray> {
        if (input.firstOrNull() != open) {
            return if (input.isEmpty()) ParseResult.Incomplete(0)
            else ParseResult.Error(ParseError.InvalidInput, 0)
        }
        if (!allowNested) {
            for (i in 1 until input.size) {
                if (input[i] == close) {
                    return ParseResult.Complete(input.copyOf(i + 1), i + 1)
                }
            }
            return ParseResult.Incomplete(input.size)
        }
        // Nested matching
        var depth = 1
        for (i in 1 until input.size) {
            when (input[i]) {
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) {
                        return ParseResult.Complete(input.copyOf(i + 1), i + 1)
                    }
                }
            }
        }
        return ParseResult.Incomplete(input.size)
    }
}

// ============================================================================
// Convenience functions
// ============================================================================

fun byte(target: Byte): ByteParser = ByteParser(target)
fun chlit(target: Byte): ByteParser = byte(target)
fun take(count: Int): TakeParser = TakeParser(count)
fun takeUntil(delimiter: Byte, scanner: SimdScanner): TakeUntilParser = TakeUntilParser(delimiter, scanner)
fun takeWhile(predicate: (Byte) -> Boolean): TakeWhileParser = TakeWhileParser(predicate)
fun tag(tag: ByteArray): TagParser = TagParser(tag)
fun <A, B> sequence(first: Parser<A>, second: Parser<B>): SequenceParser<A, B> = SequenceParser(first, second)
fun <T> alternative(first: Parser<T>, second: Parser<T>): AlternativeParser<T> = AlternativeParser(first, second)
fun <T, U> map(parser: Parser<T>, mapper: (T) -> U): MapParser<T, U> = MapParser(parser, mapper)
fun rangeWhile(start: Byte, end: Byte, min: Int, max: Int? = null): ByteRangeWhileParser =
    ByteRangeWhileParser(start, end, min, max)
fun confix(open: Byte, close: Byte, allowNested: Boolean = false): ConfixParser = ConfixParser(open, close, allowNested)

/**
 * Common character classes for network protocols.
 */
fun isSpace(byte: Byte): Boolean = byte == ' '.code.toByte() || byte == '\t'.code.toByte()
fun isCrlf(byte: Byte): Boolean = byte == '\r'.code.toByte() || byte == '\n'.code.toByte()
fun isAlpha(byte: Byte): Boolean = byte in 'A'.code.toByte()..'Z'.code.toByte() || byte in 'a'.code.toByte()..'z'.code.toByte()
fun isDigit(byte: Byte): Boolean = byte in '0'.code.toByte()..'9'.code.toByte()

fun isTokenChar(byte: Byte): Boolean = when (byte) {
    '!'.code.toByte(), '#'.code.toByte(), '$'.code.toByte(), '%'.code.toByte(),
    '&'.code.toByte(), '\''.code.toByte(), '*'.code.toByte(), '+'.code.toByte(),
    '-'.code.toByte(), '.'.code.toByte(), '^'.code.toByte(), '_'.code.toByte(),
    '`'.code.toByte(), '|'.code.toByte(), '~'.code.toByte() -> true
    in '0'.code.toByte()..'9'.code.toByte() -> true
    in 'A'.code.toByte()..'Z'.code.toByte() -> true
    in 'a'.code.toByte()..'z'.code.toByte() -> true
    else -> false
}
