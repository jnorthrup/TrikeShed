@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.charset

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
expect class CoderResult {
    override fun toString(): String
    fun isUnderflow(): Boolean
    fun isOverflow(): Boolean
    fun isError(): Boolean
    fun isMalformed(): Boolean
    fun isUnmappable(): Boolean
    fun length(): Int
    fun throwException(): Unit
    companion object {
        val UNDERFLOW: borg.trikeshed.userspace.nio.charset.CoderResult
        val OVERFLOW: borg.trikeshed.userspace.nio.charset.CoderResult
        fun malformedForLength(p0: Int): borg.trikeshed.userspace.nio.charset.CoderResult
        fun unmappableForLength(p0: Int): borg.trikeshed.userspace.nio.charset.CoderResult
    }
}
