@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.charset

public abstract class CharsetEncoder protected constructor(
    protected val charset: borg.trikeshed.userspace.nio.charset.Charset,
    protected val averageBytesPerCharValue: Float,
    protected val maxBytesPerCharValue: Float,
    protected val replacementBytes: ByteArray = byteArrayOf(63),
) {
    public open fun charset(): borg.trikeshed.userspace.nio.charset.Charset = charset
    public open fun replacement(): ByteArray = replacementBytes
    public abstract fun replaceWith(p0: ByteArray): borg.trikeshed.userspace.nio.charset.CharsetEncoder
    public abstract fun isLegalReplacement(p0: ByteArray): Boolean
    public abstract fun malformedInputAction(): borg.trikeshed.userspace.nio.charset.CodingErrorAction
    public abstract fun onMalformedInput(p0: borg.trikeshed.userspace.nio.charset.CodingErrorAction): borg.trikeshed.userspace.nio.charset.CharsetEncoder
    public abstract fun unmappableCharacterAction(): borg.trikeshed.userspace.nio.charset.CodingErrorAction
    public abstract fun onUnmappableCharacter(p0: borg.trikeshed.userspace.nio.charset.CodingErrorAction): borg.trikeshed.userspace.nio.charset.CharsetEncoder
    public open fun averageBytesPerChar(): Float = averageBytesPerCharValue
    public open fun maxBytesPerChar(): Float = maxBytesPerCharValue
    public abstract fun encode(p0: CharSequence): borg.trikeshed.userspace.nio.ByteBuffer
    public abstract fun flush(p0: borg.trikeshed.userspace.nio.ByteBuffer): borg.trikeshed.userspace.nio.charset.CoderResult
    public abstract fun reset(): borg.trikeshed.userspace.nio.charset.CharsetEncoder
    public abstract fun canEncode(p0: Char): Boolean
    public abstract fun canEncode(p0: CharSequence): Boolean
}
