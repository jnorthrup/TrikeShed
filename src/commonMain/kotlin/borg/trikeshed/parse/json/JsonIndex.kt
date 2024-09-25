package borg.trikeshed.parse.json

class JsonIndex {

    enum class OffsetEncoding(val encode: (UByte) -> UByte, val decode: (UByte) -> UByte) {
    ShortOffset({ it and 0x3FU }, { it and 0x3FU }),
    UShortExtension({ (it and 0x3FU) or 0x40U }, { ((it and 0x3FU).toUInt() shl 8).toUByte() }),
    ULong64Extension({ (it and 0x3FU) or 0x80U }, { ((it and 0x3FU).toUInt() shl 16).toUByte() }),
    Reserved({ (it and 0x3FU) or 0xC0U }, { throw IllegalStateException("Reserved encoding") })
    ;

    companion object {
        fun encode(offset: UByte, ordinal: Int): UByte {
            return values()[ordinal].encode(offset)
        }

        fun decode(byte: UByte): Pair<UByte, Int> {
            val ordinal = (byte.toUInt() shr 6).toInt()
            return Pair(values()[ordinal].decode(byte), ordinal)
        }
    }
}

}