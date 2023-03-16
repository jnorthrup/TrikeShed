package borg.trikeshed.lib

fun Series<Byte>.decodeUtf8(charArray:CharArray= CharArray(size)): Series<Char> {
    //does not use StringBuilder, but is faster than String(bytes, Charsets.UTF_8)
    var y = 0
    var w = 0
    while (y < this.size && w < charArray.size) {
        val c = this[y++].toInt()
        /* 0xxxxxxx */
        when (c shr 4 ) {
            in 0..7 -> charArray[w++] = c.toChar() // 0xxxxxxx

            /*12, 13*/ 0x0C, 0x0D -> {
                // 110x xxxx   10xx xxxx
                val c2 = this[y++].toInt()
                charArray[w++] = ((c and 0x1F) shl 6 or (c2 and 0x3F)).toChar()
            }

            /*14*/ 0x0E -> {
                // 1110 xxxx  10xx xxxx  10xx xxxx
                val c2 = this[y++].toInt()
                val c3 = this[y++].toInt()
                charArray[w++] = ((c and 0x0F) shl 12 or (c2 and 0x3F) shl 6 or (c3 and 0x3F)).toChar()
            }
        }
    }
    return w j charArray::get
}

fun Series<Byte>.asString(): String = toArray().decodeToString()