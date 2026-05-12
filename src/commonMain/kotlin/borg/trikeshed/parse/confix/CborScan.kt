package borg.trikeshed.parse.confix

/* ═══════════════════════════════════════════════════════════════════════════════
 *
 *  CBOR tokenizer → Series<JsElement>
 * ═══════════════════════════════════════════════════════════════════════════ */

 *  CBOR tokenizer → Series<JsElement> over a Series<Byte>
 *
 *  CBOR is binary; the scanner operates directly on bytes via ByteSeries
 *  and uses NetworkOrder for multi-byte big-endian reads. The tokenizer
 *  walks major types and emits (open j close) j commas exactly like JSON.
 *  Close index is inclusive of the last content byte of the item.
 *
 *  Major types (RFC 8949):
 *   0: unsigned int       1: negative int     2: byte string
 *   3: text string        4: array            5: map
 *   6: tag (skipped)      7: simple/float (true/false/null/break/float)
 * ═══════════════════════════════════════════════════════════════════════════ */

object CborScan {

    fun scan(src: Series<Byte>): Series<JsElement> {
        val ba = src.toArray()
        val bs = ByteSeries(ba)
        val out = ElemBuf()
        parseItem(ba, bs, out)
        return out.toSeries()
    }

   fun readLen(ba: ByteArray, bs: ByteSeries, ai: Int): Long {
        return when (ai) {
            in 0..23 -> ai.toLong()
            24 -> (bs.get.toInt() and 0xFF).toLong()
            25 -> {
                val p = bs.pos; bs.pos(p + 2)
                (ba.networkOrderGetShortAt(p).toInt() and 0xFFFF).toLong()
            }
            26 -> {
                val p = bs.pos; bs.pos(p + 4)
                ba.networkOrderGetIntAt(p).toLong() and 0xFFFFFFFFL
            }
            27 -> {
                val p = bs.pos; bs.pos(p + 8)
                ba.networkOrderGetLongAt(p)
            }
            31 -> -1L  // indefinite
            else -> error("bad cbor additional info $ai")
        }
    }

   fun parseItem(ba: ByteArray, bs: ByteSeries, out: ElemBuf): Int {
        val open = bs.pos
        if (!bs.hasRemaining) error("cbor eof")
        val ib = bs.get.toInt() and 0xFF
        val mt = ib ushr 5
        val ai = ib and 0x1F
        return when (mt) {
            0 -> {                                   // unsigned int
                val idx = out.beginTagged(open, Tag.NUMBER)
                readLen(ba, bs, ai)
                out.endOf(idx, bs.pos - 1, Tag.NUMBER); idx
            }
            1 -> {                                   // negative int
                val idx = out.beginTagged(open, Tag.NUMBER)
                readLen(ba, bs, ai)
                out.endOf(idx, bs.pos - 1, Tag.NUMBER); idx
            }
            2 -> {                                   // byte string
                val idx = out.beginTagged(open, Tag.BYTES)
                val len = readLen(ba, bs, ai)
                if (len < 0L) parseIndefinite(ba, bs, out, Tag.BYTES)
                else bs.pos(bs.pos + len.toInt())
                out.endOf(idx, bs.pos - 1, Tag.BYTES); idx
            }
            3 -> {                                   // text string
                val idx = out.beginTagged(open, Tag.STRING)
                val len = readLen(ba, bs, ai)
                if (len < 0L) parseIndefinite(ba, bs, out, Tag.STRING)
                else bs.pos(bs.pos + len.toInt())
                out.endOf(idx, bs.pos - 1, Tag.STRING); idx
            }
            4 -> {                                   // array
                val idx = out.beginTagged(open, Tag.ARRAY)
                val len = readLen(ba, bs, ai)
                if (len < 0L) {
                    while (bs.hasRemaining && (ba[bs.pos].toInt() and 0xFF) != 0xFF) {
                        out.addComma(bs.pos); parseItem(ba, bs, out)
                    }
                    if (bs.hasRemaining) bs.pos(bs.pos + 1)
                } else {
                    var k = 0L
                    while (k < len) { out.addComma(bs.pos); parseItem(ba, bs, out); k++ }
                }
                out.endOf(idx, bs.pos - 1, Tag.ARRAY); idx
            }
            5 -> {                                   // map
                val idx = out.beginTagged(open, Tag.OBJECT)
                val len = readLen(ba, bs, ai)
                if (len < 0L) {
                    while (bs.hasRemaining && (ba[bs.pos].toInt() and 0xFF) != 0xFF) {
                        out.addComma(bs.pos); parseItem(ba, bs, out); parseItem(ba, bs, out)
                    }
                    if (bs.hasRemaining) bs.pos(bs.pos + 1)
                } else {
                    var k = 0L
                    while (k < len) {
                        out.addComma(bs.pos); parseItem(ba, bs, out); parseItem(ba, bs, out); k++
                    }
                }
                out.endOf(idx, bs.pos - 1, Tag.OBJECT); idx
            }
            6 -> {                                   // tag: consume tag value, then inner item
                readLen(ba, bs, ai)
                parseItem(ba, bs, out)
            }
            7 -> {                                   // simple / float
                val tag = when (ai) {
                    20 -> Tag.BOOL_FALSE
                    21 -> Tag.BOOL_TRUE
                    22, 23 -> Tag.NULL
                    25 -> Tag.NUMBER
                    26 -> Tag.NUMBER
                    27 -> Tag.NUMBER
                    else -> Tag.NULL
                }
                val idx = out.beginTagged(open, tag)
                when (ai) {
                    25 -> bs.pos(bs.pos + 2)
                    26 -> bs.pos(bs.pos + 4)
                    27 -> bs.pos(bs.pos + 8)
                    24 -> bs.pos(bs.pos + 1)
                    else -> { /* simple value, no payload */ }
                }
                out.endOf(idx, bs.pos - 1, tag); idx
            }
            else -> error("cbor major type $mt")
        }
    }

   fun parseIndefinite(ba: ByteArray, bs: ByteSeries, out: ElemBuf, tag: Tag) {
        while (bs.hasRemaining && (ba[bs.pos].toInt() and 0xFF) != 0xFF) {
            val ib = bs.get.toInt() and 0xFF
            val ai = ib and 0x1F
            val len = readLen(ba, bs, ai)
            if (len >= 0) bs.pos(bs.pos + len.toInt())
        }
        if (bs.hasRemaining) bs.pos(bs.pos + 1)
        @Suppress("UNUSED_PARAMETER") val t = tag
        @Suppress("UNUSED_PARAMETER") val o = out
    }
}
