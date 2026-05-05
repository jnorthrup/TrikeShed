@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.charset

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
expect abstract class CharsetEncoder {
    protected constructor(p0: borg.trikeshed.userspace.nio.charset.Charset, p1: Float, p2: Float, p3: ByteArray)
    protected constructor(p0: borg.trikeshed.userspace.nio.charset.Charset, p1: Float, p2: Float)
    fun charset(): borg.trikeshed.userspace.nio.charset.Charset
    fun replacement(): ByteArray
    fun replaceWith(p0: ByteArray): borg.trikeshed.userspace.nio.charset.CharsetEncoder
    protected fun implReplaceWith(p0: ByteArray): Unit
    fun isLegalReplacement(p0: ByteArray): Boolean
    fun malformedInputAction(): borg.trikeshed.userspace.nio.charset.CodingErrorAction
    fun onMalformedInput(p0: borg.trikeshed.userspace.nio.charset.CodingErrorAction): borg.trikeshed.userspace.nio.charset.CharsetEncoder
    protected fun implOnMalformedInput(p0: borg.trikeshed.userspace.nio.charset.CodingErrorAction): Unit
    fun unmappableCharacterAction(): borg.trikeshed.userspace.nio.charset.CodingErrorAction
    fun onUnmappableCharacter(p0: borg.trikeshed.userspace.nio.charset.CodingErrorAction): borg.trikeshed.userspace.nio.charset.CharsetEncoder
    protected fun implOnUnmappableCharacter(p0: borg.trikeshed.userspace.nio.charset.CodingErrorAction): Unit
    fun averageBytesPerChar(): Float
    fun maxBytesPerChar(): Float
    fun encode(p0: borg.trikeshed.userspace.nio.CharBuffer, p1: borg.trikeshed.userspace.nio.ByteBuffer, p2: Boolean): borg.trikeshed.userspace.nio.charset.CoderResult
    fun flush(p0: borg.trikeshed.userspace.nio.ByteBuffer): borg.trikeshed.userspace.nio.charset.CoderResult
    protected fun implFlush(p0: borg.trikeshed.userspace.nio.ByteBuffer): borg.trikeshed.userspace.nio.charset.CoderResult
    fun reset(): borg.trikeshed.userspace.nio.charset.CharsetEncoder
    protected fun implReset(): Unit
    protected fun encodeLoop(p0: borg.trikeshed.userspace.nio.CharBuffer, p1: borg.trikeshed.userspace.nio.ByteBuffer): borg.trikeshed.userspace.nio.charset.CoderResult
    fun encode(p0: borg.trikeshed.userspace.nio.CharBuffer): borg.trikeshed.userspace.nio.ByteBuffer
    fun canEncode(p0: Char): Boolean
    fun canEncode(p0: CharSequence): Boolean
}
