@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.charset

import borg.trikeshed.userspace.nio.ByteBuffer

public open class Charset private constructor(
    internal val delegate: PlatformCharset,
) : Comparable<Charset> {

    public fun name(): CharSequence = delegate.name

    public fun aliases(): Set<CharSequence> = delegate.aliases

    public fun displayName(): CharSequence = name()

    public fun isRegistered(): Boolean = true

    public fun displayName(locale: Any?): CharSequence = name()

    public fun contains(p0: Charset): Boolean = delegate.contains(p0.delegate)

    public fun newDecoder(): CharsetDecoder = throw UnsupportedOperationException("CharsetDecoder is not supported in commonMain")

    public fun newEncoder(): CharsetEncoder = throw UnsupportedOperationException("CharsetEncoder is not supported in commonMain")

    public fun canEncode(): Boolean = delegate.canEncode()

    public fun decode(p0: ByteBuffer): CharSequence {
        val bytes = p0.array()
        val offset = p0.arrayOffset() + p0.position()
        val length = p0.remaining()
        p0.position(p0.limit())
        return delegate.decode(bytes, offset, length)
    }

    public fun encode(p0: CharSequence): ByteBuffer = ByteBuffer.wrap(delegate.encode(p0))

    public override fun compareTo(p0: Charset): Int = name().toString().compareTo(p0.name().toString())

    public override fun hashCode(): Int = name().hashCode()

    public override fun equals(p0: Any?): Boolean = p0 is Charset && delegate === p0.delegate

    public override fun toString(): String = name().toString()

    public companion object {
        public fun isSupported(p0: CharSequence): Boolean = PlatformCharset.isSupported(p0)

        public fun forName(p0: CharSequence): Charset = Charset(PlatformCharset.forName(p0))

        public fun forName(p0: CharSequence, p1: Charset): Charset = if (PlatformCharset.isSupported(p0)) Charset(PlatformCharset.forName(p0)) else p1

        public fun availableCharsets(): Map<CharSequence, Charset> =
            PlatformCharset.availableCharsets().mapValues { Charset(it.value) }

        public fun defaultCharset(): Charset = Charset(PlatformCharset.defaultCharset())
    }
}
