package borg.trikeshed.parse.confix

internal object ConfixCborDecoder {
    fun decode(bytes: ByteArray): ConfixElement {
        return decode(ByteArrayReader(bytes))
    }

    private class ByteArrayReader(val bytes: ByteArray) {
        var pos = 0
        fun read(): Int {
            if (pos >= bytes.size) throw IllegalArgumentException("Unexpected end of CBOR bytes")
            return bytes[pos++].toInt() and 0xFF
        }

        fun readBytes(count: Int): ByteArray {
            if (pos + count > bytes.size) throw IllegalArgumentException("Unexpected end of CBOR bytes")
            val res = bytes.copyOfRange(pos, pos + count)
            pos += count
            return res
        }
    }

    private fun decode(reader: ByteArrayReader): ConfixElement {
        val initialByte = reader.read()
        val majorType = initialByte ushr 5
        val additionalInformation = initialByte and 0x1F

        return when (majorType) {
            0 -> { // unsigned integer
                val value = readArgument(reader, additionalInformation)
                ConfixPrimitive(value.toLong().toString(), false)
            }
            1 -> { // negative integer
                val value = readArgument(reader, additionalInformation)
                val longVal = -1L - value.toLong()
                ConfixPrimitive(longVal.toString(), false)
            }
            2 -> { // byte string (encoded as base64 or just string for primitive... let's check how it's emitted)
                // In ConfixCborEncoder.kt, fallback string/bytes are emitted as type 3 (text string)
                // but byte strings are type 2. If it's a byte array encoded, we decode to primitive string?
                // Wait, ConfixCborEncoder encodes string using: `v.encodeToByteArray()`, major type 3.
                // Let's handle byte strings as strings just in case
                val len = readArgument(reader, additionalInformation).toInt()
                val bytes = reader.readBytes(len)
                ConfixPrimitive(bytes.decodeToString(), true)
            }
            3 -> { // text string
                val len = readArgument(reader, additionalInformation).toInt()
                val bytes = reader.readBytes(len)
                ConfixPrimitive(bytes.decodeToString(), true)
            }
            4 -> { // array
                val len = readArgument(reader, additionalInformation).toInt()
                val list = ArrayList<ConfixElement>(len)
                for (i in 0 until len) {
                    list.add(decode(reader))
                }
                ConfixArray(list)
            }
            5 -> { // map
                val len = readArgument(reader, additionalInformation).toInt()
                val map = LinkedHashMap<String, ConfixElement>(len)
                for (i in 0 until len) {
                    val keyElement = decode(reader)
                    val keyStr = (keyElement as ConfixPrimitive).content
                    val valueElement = decode(reader)
                    map[keyStr] = valueElement
                }
                ConfixObject(map)
            }
            7 -> { // simple values / floats
                when (additionalInformation) {
                    20 -> ConfixPrimitive("false", false)
                    21 -> ConfixPrimitive("true", false)
                    22 -> ConfixNull
                    27 -> { // 64-bit float
                        val bits = reader.readBytes(8)
                        var longBits = 0L
                        for (i in 0..7) {
                            longBits = (longBits shl 8) or (bits[i].toLong() and 0xFF)
                        }
                        ConfixPrimitive(Double.fromBits(longBits).toString(), false)
                    }
                    else -> throw IllegalArgumentException("Unsupported CBOR simple value / float: $additionalInformation")
                }
            }
            else -> throw IllegalArgumentException("Unsupported CBOR major type: $majorType")
        }
    }

    private fun readArgument(reader: ByteArrayReader, additionalInformation: Int): ULong {
        return when (additionalInformation) {
            in 0..23 -> additionalInformation.toULong()
            24 -> reader.read().toULong()
            25 -> {
                val b1 = reader.read()
                val b2 = reader.read()
                ((b1 shl 8) or b2).toULong()
            }
            26 -> {
                val b1 = reader.read().toULong()
                val b2 = reader.read().toULong()
                val b3 = reader.read().toULong()
                val b4 = reader.read().toULong()
                (b1 shl 24) or (b2 shl 16) or (b3 shl 8) or b4
            }
            27 -> {
                var res = 0UL
                for (i in 0..7) {
                    res = (res shl 8) or reader.read().toULong()
                }
                res
            }
            else -> throw IllegalArgumentException("Unsupported CBOR length/value: $additionalInformation")
        }
    }
}
