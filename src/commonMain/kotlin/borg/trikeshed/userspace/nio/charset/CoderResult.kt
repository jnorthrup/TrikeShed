@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.charset

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
public class CoderResult private constructor(private val type: Int, private val _length: Int) {
    override fun toString(): String {
        return when (type) {
            CR_UNDERFLOW -> "UNDERFLOW"
            CR_OVERFLOW -> "OVERFLOW"
            CR_MALFORMED -> "MALFORMED[$_length]"
            CR_UNMAPPABLE -> "UNMAPPABLE[$_length]"
            else -> "UNKNOWN"
        }
    }
    fun isUnderflow(): Boolean = type == CR_UNDERFLOW
    fun isOverflow(): Boolean = type == CR_OVERFLOW
    fun isError(): Boolean = type == CR_MALFORMED || type == CR_UNMAPPABLE
    fun isMalformed(): Boolean = type == CR_MALFORMED
    fun isUnmappable(): Boolean = type == CR_UNMAPPABLE
    fun length(): Int {
        if (!isError()) {
            throw UnsupportedOperationException()
        }
        return _length
    }
    fun throwException(): Unit {
        when (type) {
            CR_UNDERFLOW -> throw borg.trikeshed.userspace.nio.BufferUnderflowException()
            CR_OVERFLOW -> throw borg.trikeshed.userspace.nio.BufferOverflowException()
            CR_MALFORMED -> throw MalformedInputException(_length)
            CR_UNMAPPABLE -> throw UnmappableCharacterException(_length)
        }
    }
    companion object {
        private const val CR_UNDERFLOW = 0
        private const val CR_OVERFLOW = 1
        private const val CR_ERROR_MIN = 2
        private const val CR_MALFORMED = 2
        private const val CR_UNMAPPABLE = 3

        val UNDERFLOW: borg.trikeshed.userspace.nio.charset.CoderResult = CoderResult(CR_UNDERFLOW, 0)
        val OVERFLOW: borg.trikeshed.userspace.nio.charset.CoderResult = CoderResult(CR_OVERFLOW, 0)
        fun malformedForLength(p0: Int): borg.trikeshed.userspace.nio.charset.CoderResult = CoderResult(CR_MALFORMED, p0)
        fun unmappableForLength(p0: Int): borg.trikeshed.userspace.nio.charset.CoderResult = CoderResult(CR_UNMAPPABLE, p0)
    }
}
