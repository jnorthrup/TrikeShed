package borg.trikeshed.userspace.nio.charset

internal class PlatformCharset private constructor(
    val name: String,
    val aliases: Set<String>,
    private val codec: Codec,
) {
    fun canEncode(): Boolean = true

    fun decode(bytes: ByteArray, offset: Int, length: Int): String = codec.decode(bytes, offset, length)

    fun encode(value: String): ByteArray = codec.encode(value)

    fun contains(other: PlatformCharset): Boolean = this === other

    private interface Codec {
        fun decode(bytes: ByteArray, offset: Int, length: Int): String = TODO("NIO common stub")
        fun encode(value: String): ByteArray = TODO("NIO common stub")
    }

    companion object {
        val UTF_8: PlatformCharset = PlatformCharset(
            name = "UTF-8",
            aliases = setOf("UTF8", "unicode-1-1-utf-8"),
            codec = object : Codec {
                override fun decode(bytes: ByteArray, offset: Int, length: Int): String =
                    bytes.decodeToString(offset, offset + length)

                override fun encode(value: String): ByteArray = value.encodeToByteArray()
            },
        )

        val ISO_8859_1: PlatformCharset = PlatformCharset(
            name = "ISO-8859-1",
            aliases = setOf("ISO8859-1", "ISO_8859-1", "latin1", "latin-1"),
            codec = object : Codec {
                override fun decode(bytes: ByteArray, offset: Int, length: Int): String = buildString(length) {
                    for (i in offset until offset + length) {
                        append(bytes[i].toInt().and(0xff).toChar())
                    }
                }

                override fun encode(value: String): ByteArray {
                    val bytes = mutableListOf<Byte>()
                    var i = 0
                    while (i < value.length) {
                        val ch = value[i]
                        if (ch.isHighSurrogate() && i + 1 < value.length && value[i + 1].isLowSurrogate()) {
                            bytes.add('?'.code.toByte())
                            i += 2
                        } else {
                            val code = ch.code
                            bytes.add(if (code <= 0xff) code.toByte() else '?'.code.toByte())
                            i++
                        }
                    }
                    return bytes.toByteArray()
                }
            },
        )

        private val registry: Map<String, PlatformCharset> = listOf(UTF_8, ISO_8859_1)
            .flatMap { charset -> (listOf(charset.name) + charset.aliases).map { it.normalizedCharsetName() to charset } }
            .toMap()

        fun forName(name: String): PlatformCharset =
            registry[name.normalizedCharsetName()] ?: throw UnsupportedCharsetException(name)

        fun isSupported(name: String): Boolean = registry.containsKey(name.normalizedCharsetName())

        fun availableCharsets(): Map<String, PlatformCharset> =
            linkedMapOf(ISO_8859_1.name to ISO_8859_1, UTF_8.name to UTF_8)

        fun defaultCharset(): PlatformCharset = UTF_8
    }
}

private fun String.normalizedCharsetName(): String = trim().uppercase()
