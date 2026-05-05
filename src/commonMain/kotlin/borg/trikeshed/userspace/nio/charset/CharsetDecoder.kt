@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.charset

public abstract class CharsetDecoder protected constructor(
    protected val charset: borg.trikeshed.userspace.nio.charset.Charset,
    protected val averageCharsPerByteValue: Float,
    protected val maxCharsPerByteValue: Float,
) {
    public open fun charset(): borg.trikeshed.userspace.nio.charset.Charset = charset
    public abstract fun replacement(): String
    public abstract fun replaceWith(p0: String): borg.trikeshed.userspace.nio.charset.CharsetDecoder
    public abstract fun malformedInputAction(): borg.trikeshed.userspace.nio.charset.CodingErrorAction
    public abstract fun onMalformedInput(p0: borg.trikeshed.userspace.nio.charset.CodingErrorAction): borg.trikeshed.userspace.nio.charset.CharsetDecoder
    public abstract fun unmappableCharacterAction(): borg.trikeshed.userspace.nio.charset.CodingErrorAction
    public abstract fun onUnmappableCharacter(p0: borg.trikeshed.userspace.nio.charset.CodingErrorAction): borg.trikeshed.userspace.nio.charset.CharsetDecoder
    public open fun averageCharsPerByte(): Float = averageCharsPerByteValue
    public open fun maxCharsPerByte(): Float = maxCharsPerByteValue
    public abstract fun decode(p0: borg.trikeshed.userspace.nio.ByteBuffer): String
    public abstract fun flush(p0: borg.trikeshed.userspace.nio.ByteBuffer): borg.trikeshed.userspace.nio.charset.CoderResult
    public abstract fun reset(): borg.trikeshed.userspace.nio.charset.CharsetDecoder
    public abstract fun isAutoDetecting(): Boolean
    public abstract fun isCharsetDetected(): Boolean
    public abstract fun detectedCharset(): borg.trikeshed.userspace.nio.charset.Charset
}
