@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
expect abstract class CharBuffer : borg.trikeshed.userspace.nio.Buffer, Comparable<borg.trikeshed.userspace.nio.CharBuffer>, java.lang.Appendable, CharSequence, java.lang.Readable {
    fun read(p0: borg.trikeshed.userspace.nio.CharBuffer): Int
    fun slice(): borg.trikeshed.userspace.nio.CharBuffer
    fun slice(p0: Int, p1: Int): borg.trikeshed.userspace.nio.CharBuffer
    fun duplicate(): borg.trikeshed.userspace.nio.CharBuffer
    fun asReadOnlyBuffer(): borg.trikeshed.userspace.nio.CharBuffer
    fun `get`(): Char
    fun put(p0: Char): borg.trikeshed.userspace.nio.CharBuffer
    fun `get`(p0: Int): Char
    fun put(p0: Int, p1: Char): borg.trikeshed.userspace.nio.CharBuffer
    fun `get`(p0: CharArray, p1: Int, p2: Int): borg.trikeshed.userspace.nio.CharBuffer
    fun `get`(p0: CharArray): borg.trikeshed.userspace.nio.CharBuffer
    fun `get`(p0: Int, p1: CharArray, p2: Int, p3: Int): borg.trikeshed.userspace.nio.CharBuffer
    fun `get`(p0: Int, p1: CharArray): borg.trikeshed.userspace.nio.CharBuffer
    fun put(p0: borg.trikeshed.userspace.nio.CharBuffer): borg.trikeshed.userspace.nio.CharBuffer
    fun put(p0: Int, p1: borg.trikeshed.userspace.nio.CharBuffer, p2: Int, p3: Int): borg.trikeshed.userspace.nio.CharBuffer
    fun put(p0: CharArray, p1: Int, p2: Int): borg.trikeshed.userspace.nio.CharBuffer
    fun put(p0: CharArray): borg.trikeshed.userspace.nio.CharBuffer
    fun put(p0: Int, p1: CharArray, p2: Int, p3: Int): borg.trikeshed.userspace.nio.CharBuffer
    fun put(p0: Int, p1: CharArray): borg.trikeshed.userspace.nio.CharBuffer
    fun put(p0: String, p1: Int, p2: Int): borg.trikeshed.userspace.nio.CharBuffer
    fun put(p0: String): borg.trikeshed.userspace.nio.CharBuffer
    fun hasArray(): Boolean
    fun array(): CharArray
    fun arrayOffset(): Int
    fun position(p0: Int): borg.trikeshed.userspace.nio.CharBuffer
    fun limit(p0: Int): borg.trikeshed.userspace.nio.CharBuffer
    fun mark(): borg.trikeshed.userspace.nio.CharBuffer
    fun reset(): borg.trikeshed.userspace.nio.CharBuffer
    fun clear(): borg.trikeshed.userspace.nio.CharBuffer
    fun flip(): borg.trikeshed.userspace.nio.CharBuffer
    fun rewind(): borg.trikeshed.userspace.nio.CharBuffer
    fun compact(): borg.trikeshed.userspace.nio.CharBuffer
    fun isDirect(): Boolean
    override fun hashCode(): Int
    override fun equals(p0: Any?): Boolean
    override fun compareTo(p0: borg.trikeshed.userspace.nio.CharBuffer): Int
    fun mismatch(p0: borg.trikeshed.userspace.nio.CharBuffer): Int
    fun getChars(p0: Int, p1: Int, p2: CharArray, p3: Int): Unit
    override fun toString(): String
    fun length(): Int
    fun isEmpty(): Boolean
    fun charAt(p0: Int): Char
    fun subSequence(p0: Int, p1: Int): borg.trikeshed.userspace.nio.CharBuffer
    fun append(p0: CharSequence): borg.trikeshed.userspace.nio.CharBuffer
    fun append(p0: CharSequence, p1: Int, p2: Int): borg.trikeshed.userspace.nio.CharBuffer
    fun append(p0: Char): borg.trikeshed.userspace.nio.CharBuffer
    fun order(): borg.trikeshed.userspace.nio.ByteOrder
    fun chars(): java.util.stream.IntStream
    override fun compareTo(p0: Any): Int
    companion object {
        fun allocate(p0: Int): borg.trikeshed.userspace.nio.CharBuffer
        fun wrap(p0: CharArray, p1: Int, p2: Int): borg.trikeshed.userspace.nio.CharBuffer
        fun wrap(p0: CharArray): borg.trikeshed.userspace.nio.CharBuffer
        fun wrap(p0: CharSequence, p1: Int, p2: Int): borg.trikeshed.userspace.nio.CharBuffer
        fun wrap(p0: CharSequence): borg.trikeshed.userspace.nio.CharBuffer
    }
}
