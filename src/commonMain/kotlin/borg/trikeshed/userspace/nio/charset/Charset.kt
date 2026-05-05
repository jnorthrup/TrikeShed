@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.charset

import borg.trikeshed.userspace.nio.ByteBuffer

public open class Charset private constructor(
    internal val delegate: PlatformCharset,
) : Comparable<Charset> {

    public fun name(): String = delegate.name

    public fun aliases(): java.util.Set<String> = java.util.LinkedHashSet<String>(delegate.aliases)

    public fun displayName(): String = name()

    public fun isRegistered(): Boolean = true

    public fun displayName(p0: java.util.Locale): String = name()

    public fun contains(p0: Charset): Boolean = delegate.contains(p0.delegate)

    public fun newDecoder(): CharsetDecoder = throw UnsupportedOperationException("CharsetDecoder is not supported in commonMain")

    public fun newEncoder(): CharsetEncoder = throw UnsupportedOperationException("CharsetEncoder is not supported in commonMain")

    public fun canEncode(): Boolean = delegate.canEncode()

    public fun decode(p0: ByteBuffer): String {
        val bytes = p0.array()
        val offset = p0.arrayOffset() + p0.position()
        val length = p0.remaining()
        p0.position(p0.limit())
        return delegate.decode(bytes, offset, length)
    }

    public fun encode(p0: String): ByteBuffer = ByteBuffer.wrap(delegate.encode(p0))

    public override fun compareTo(p0: Charset): Int = name().compareTo(p0.name())

    public override fun hashCode(): Int = name().hashCode()

    public override fun equals(p0: Any?): Boolean = p0 is Charset && delegate.contains(p0.delegate)

    public override fun toString(): String = name()

    public fun compareTo(p0: Any): Int = if (p0 is Charset) compareTo(p0) else throw ClassCastException("Cannot compare Charset with ${p0?.javaClass}")

    public companion object {
        public fun isSupported(p0: String): Boolean = PlatformCharset.isSupported(p0)

        public fun forName(p0: String): Charset = Charset(PlatformCharset.forName(p0))

        public fun forName(p0: String, p1: Charset): Charset = if (PlatformCharset.isSupported(p0)) Charset(PlatformCharset.forName(p0)) else p1

        public fun availableCharsets(): java.util.SortedMap<String, Charset> = java.util.TreeMap<String, Charset>().apply {
            for ((name, platformCharset) in PlatformCharset.availableCharsets()) {
                put(name, Charset(platformCharset))
            }
        }

        public fun defaultCharset(): Charset = Charset(PlatformCharset.defaultCharset())
    }
}
