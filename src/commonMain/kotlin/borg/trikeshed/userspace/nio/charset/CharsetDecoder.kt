@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.charset

// Generated from Amazon Corretto JDK 25 java.base NIO public/protected API via javap.
// Declarations intentionally mirror JDK taxonomy and contain no implementations.
expect abstract class CharsetDecoder {
    protected constructor(p0: borg.trikeshed.userspace.nio.charset.Charset, p1: Float, p2: Float)
    fun charset(): borg.trikeshed.userspace.nio.charset.Charset
    fun replacement(): String
    fun replaceWith(p0: String): borg.trikeshed.userspace.nio.charset.CharsetDecoder
    protected fun implReplaceWith(p0: String): Unit
    fun malformedInputAction(): borg.trikeshed.userspace.nio.charset.CodingErrorAction
    fun onMalformedInput(p0: borg.trikeshed.userspace.nio.charset.CodingErrorAction): borg.trikeshed.userspace.nio.charset.CharsetDecoder
    protected fun implOnMalformedInput(p0: borg.trikeshed.userspace.nio.charset.CodingErrorAction): Unit
    fun unmappableCharacterAction(): borg.trikeshed.userspace.nio.charset.CodingErrorAction
    fun onUnmappableCharacter(p0: borg.trikeshed.userspace.nio.charset.CodingErrorAction): borg.trikeshed.userspace.nio.charset.CharsetDecoder
    protected fun implOnUnmappableCharacter(p0: borg.trikeshed.userspace.nio.charset.CodingErrorAction): Unit
    fun averageCharsPerByte(): Float
    fun maxCharsPerByte(): Float
    fun decode(p0: borg.trikeshed.userspace.nio.ByteBuffer, p1: borg.trikeshed.userspace.nio.CharBuffer, p2: Boolean): borg.trikeshed.userspace.nio.charset.CoderResult
    fun flush(p0: borg.trikeshed.userspace.nio.CharBuffer): borg.trikeshed.userspace.nio.charset.CoderResult
    protected fun implFlush(p0: borg.trikeshed.userspace.nio.CharBuffer): borg.trikeshed.userspace.nio.charset.CoderResult
    fun reset(): borg.trikeshed.userspace.nio.charset.CharsetDecoder
    protected fun implReset(): Unit
    protected fun decodeLoop(p0: borg.trikeshed.userspace.nio.ByteBuffer, p1: borg.trikeshed.userspace.nio.CharBuffer): borg.trikeshed.userspace.nio.charset.CoderResult
    fun decode(p0: borg.trikeshed.userspace.nio.ByteBuffer): borg.trikeshed.userspace.nio.CharBuffer
    fun isAutoDetecting(): Boolean
    fun isCharsetDetected(): Boolean
    fun detectedCharset(): borg.trikeshed.userspace.nio.charset.Charset
}
