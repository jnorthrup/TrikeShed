package borg.trikeshed.parse.confix

class BufferReader(private val bytes: ByteArray) {
    var position = 0

    fun readByte(): Int {
        if (position >= bytes.size) throw IllegalArgumentException("Unexpected end of CBOR input")
        return bytes[position++].toInt() and 0xFF
    }

    fun readBytes(count: Int): ByteArray {
        if (position + count > bytes.size) throw IllegalArgumentException("Unexpected end of CBOR input")
        val result = bytes.copyOfRange(position, position + count)
        position += count
        return result
    }
}

object ConfixCborDecoder {
    fun decode(bytes: ByteArray): ConfixElement {
        val reader = BufferReader(bytes)
        return decodeElement(reader)
    }

    private fun decodeElement(reader: BufferReader): ConfixElement {
        val initialByte = reader.readByte()
        val majorType = initialByte ushr 5
        val additionalInfo = initialByte and 0x1F

        return when (majorType) {
            0 -> ConfixPrimitive(readArgument(reader, additionalInfo).toString(), false)
            1 -> {
                val value = -1L - readArgument(reader, additionalInfo).toLong()
                ConfixPrimitive(value.toString(), false)
            }
            2 -> {
                val length = readArgument(reader, additionalInfo).toInt()
                val bytes = reader.readBytes(length)
                ConfixPrimitive(bytes.decodeToString(), false)
            }
            3 -> {
                val length = readArgument(reader, additionalInfo).toInt()
                val bytes = reader.readBytes(length)
                ConfixPrimitive(bytes.decodeToString(), true)
            }
            4 -> {
                val length = readArgument(reader, additionalInfo).toInt()
                val elements = mutableListOf<ConfixElement>()
                for (i in 0 until length) {
                    elements.add(decodeElement(reader))
                }
                ConfixArray(elements)
            }
            5 -> {
                val length = readArgument(reader, additionalInfo).toInt()
                val map = mutableMapOf<String, ConfixElement>()
                for (i in 0 until length) {
                    val keyElement = decodeElement(reader)
                    val keyString = (keyElement as ConfixPrimitive).content
                    val valueElement = decodeElement(reader)
                    map[keyString] = valueElement
                }
                ConfixObject(map)
            }
            7 -> {
                when (additionalInfo) {
                    20 -> ConfixPrimitive(false)
                    21 -> ConfixPrimitive(true)
                    22 -> ConfixNull
                    27 -> { // float64
                        var l = 0L
                        for (i in 0..7) {
                            l = (l shl 8) or reader.readByte().toLong()
                        }
                        val dbl = Double.fromBits(l)
                        ConfixPrimitive(dbl.toString(), false)
                    }
                    else -> throw IllegalArgumentException("Unsupported CBOR simple value: $additionalInfo")
                }
            }
            else -> throw IllegalArgumentException("Unsupported CBOR major type: $majorType")
        }
    }

    private fun readArgument(reader: BufferReader, additionalInfo: Int): ULong {
        return when (additionalInfo) {
            in 0..23 -> additionalInfo.toULong()
            24 -> reader.readByte().toULong()
            25 -> {
                val b1 = reader.readByte()
                val b2 = reader.readByte()
                ((b1 shl 8) or b2).toULong()
            }
            26 -> {
                val b1 = reader.readByte()
                val b2 = reader.readByte()
                val b3 = reader.readByte()
                val b4 = reader.readByte()
                ((b1.toLong() shl 24) or (b2.toLong() shl 16) or (b3.toLong() shl 8) or b4.toLong()).toULong()
            }
            27 -> {
                var l = 0UL
                for (i in 0..7) {
                    l = (l shl 8) or reader.readByte().toULong()
                }
                l
            }
            else -> throw IllegalArgumentException("Unsupported CBOR additional info: $additionalInfo")
        }
    }
}
