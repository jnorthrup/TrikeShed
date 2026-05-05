@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.charset

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
expect abstract class Charset {
    protected constructor(p0: String, p1: Array<String>)
    fun name(): String
    fun aliases(): java.util.Set<String>
    fun displayName(): String
    fun isRegistered(): Boolean
    fun displayName(p0: java.util.Locale): String
    fun contains(p0: borg.trikeshed.userspace.nio.charset.Charset): Boolean
    fun newDecoder(): borg.trikeshed.userspace.nio.charset.CharsetDecoder
    fun newEncoder(): borg.trikeshed.userspace.nio.charset.CharsetEncoder
    fun canEncode(): Boolean
    fun decode(p0: borg.trikeshed.userspace.nio.ByteBuffer): borg.trikeshed.userspace.nio.CharBuffer
    fun encode(p0: borg.trikeshed.userspace.nio.CharBuffer): borg.trikeshed.userspace.nio.ByteBuffer
    fun encode(p0: String): borg.trikeshed.userspace.nio.ByteBuffer
    fun compareTo(p0: borg.trikeshed.userspace.nio.charset.Charset): Int
    override fun hashCode(): Int
    override fun equals(p0: Any?): Boolean
    override fun toString(): String
    fun compareTo(p0: Any): Int
    companion object {
        fun isSupported(p0: String): Boolean
        fun forName(p0: String): borg.trikeshed.userspace.nio.charset.Charset
        fun forName(p0: String, p1: borg.trikeshed.userspace.nio.charset.Charset): borg.trikeshed.userspace.nio.charset.Charset
        fun availableCharsets(): java.util.SortedMap<String, borg.trikeshed.userspace.nio.charset.Charset>
        fun defaultCharset(): borg.trikeshed.userspace.nio.charset.Charset
    }
}
