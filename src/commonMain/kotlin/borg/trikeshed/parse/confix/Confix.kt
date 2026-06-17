@file:Suppress("NonAsciiCharacters")
package borg.trikeshed.parse.confix
import borg.trikeshed.charstr.CharStr
import borg.trikeshed.lib.*
import borg.trikeshed.cursor.*
import borg.trikeshed.mutable.*
interface ConfixLifecycle
typealias ConfixIndex = FacetedRow<Any>

enum class Syntax {
    JSON {
        override fun scan(src: Series<Byte>): Cursor = scan0(src).a
        override fun recognize(first: Byte): Boolean = first.toInt().toChar() in setOf('{', '[', '"')
    },
    CBOR {
        override fun scan(src: Series<Byte>): Cursor  {
            fun scanCbor(src: Series<Byte>): Join<Cursor, FlatIndex> {
                val n = src.size
                val sOpen = series()
                val sClose = series()
                val sTag = ChunkedMutableSeries<IOMemento>()
                fun add(o: Int, c: Int, t: IOMemento) {
                    sOpen.add(o); sClose.add(c); sTag.add(t)
                }

                fun readLen(p: Int, ai: Int): Pair<Long, Int> = when (ai) {
                    in 0..23 -> ai.toLong() to p
                    24 -> (src[p].toLong() and 0xFF) to (p + 1)
                    25 -> (((src[p].toInt() and 0xFF) shl 8) or (src[p + 1].toInt() and 0xFF)).toLong() to (p + 2)
                    26 -> (((src[p].toInt() and 0xFF) shl 24) or ((src[p + 1].toInt() and 0xFF) shl 16) or ((src[p + 2].toInt() and 0xFF) shl 8) or (src[p + 3].toInt() and 0xFF)).toLong() to (p + 4)
                    27 -> {
                        var v = 0L;
                        var k = 0; while (k < 8) {
                            v = (v shl 8) or (src[p + k].toLong() and 0xFF); k++
                        }; v to (p + 8)
                    }

                    31 -> -1L to p
                    else -> error("cbor ai $ai")
                }

                fun parseItem(p: Int): Int {
                    val open = p;
                    val ib = src[p].toInt() and 0xFF;
                    val mt = ib ushr 5;
                    val ai = ib and 0x1F;
                    var np = p + 1
                    np = when (mt) {
                        0, 1 -> {
                            val (_, np1) = readLen(np, ai); add(open, np1 - 1, IOMemento.IoDouble); np1
                        }

                        2 -> {
                            val (len, np1) = readLen(np, ai); if (len < 0) np1 else {
                                add(open, np1 + len.toInt() - 1, IOMemento.IoBytes); np1 + len.toInt()
                            }
                        }

                        3 -> {
                            val (len, np1) = readLen(np, ai); if (len < 0) np1 else {
                                add(open, np1 + len.toInt() - 1, IOMemento.IoString); np1 + len.toInt()
                            }
                        }

                        4 -> {
                            val (len, np1) = readLen(np, ai);
                            var kp = np1
                            if (len < 0L) while (kp < n && (src[kp].toInt() and 0xFF) != 0xFF) kp = parseItem(kp)
                            else {
                                var k = 0L; while (k < len) {
                                    kp = parseItem(kp); k++
                                }
                            }
                            if (kp < n && len < 0L) kp++; add(open, kp - 1, IOMemento.IoArray); kp
                        }

                        5 -> {
                            val (len, np1) = readLen(np, ai);
                            var kp = np1
                            if (len < 0L) while (kp < n && (src[kp].toInt() and 0xFF) != 0xFF) {
                                kp = parseItem(kp); kp = parseItem(kp)
                            }
                            else {
                                var k = 0L; while (k < len) {
                                    kp = parseItem(kp); kp = parseItem(kp); k++
                                }
                            }
                            if (kp < n && len < 0L) kp++; add(open, kp - 1, IOMemento.IoObject); kp
                        }

                        6 -> {
                            val (_, np1) = readLen(np, ai); parseItem(np1)
                        }

                        7 -> {
                            val tag = when (ai) {
                                20 -> IOMemento.IoBoolean; 21 -> IOMemento.IoBoolean; 22, 23 -> IOMemento.IoNothing; 25, 26, 27 -> IOMemento.IoDouble; else -> IOMemento.IoNothing
                            }
                            val sz = when (ai) {
                                25 -> 2; 26 -> 4; 27 -> 8; 24 -> 1; else -> 0
                            }
                            add(open, open + sz, tag); np + sz
                        }

                        else -> {
                            add(open, open, IOMemento.IoNothing); np
                        }
                    }
                    return np
                }

                var pos = 0; while (pos < n) pos = parseItem(pos)
                return buildTree(sOpen, sClose, sTag)
            }
            val( c: Cursor)= scanCbor(src)
            return c
        }

        override fun recognize(first: Byte): Boolean = true

    },
    YAML {
        override fun scan(src: Series<Byte>): Cursor = scan0(src).a
        override fun recognize(first: Byte): Boolean = first.toInt().toChar() !in setOf('{', '[')
    };

    abstract fun scan(src: Series<Byte>): Cursor
    abstract fun recognize(first: Byte): Boolean
    fun dispatch(bytes: ByteArray): Cursor {
        val n = bytes.size
        val src: Series<Byte> = n j { i: Int -> bytes[i] }
        val first = src[0]
        return entries.first { it.recognize(first) }.scan(src)
    }

    fun decodeText(src: Series<Char>, open: Int, close: Int): CharStr {
        val first = src[open]; val last = src[close]
        if (first == '"' && last == '"' && close > open + 1) return CharStr(src, open + 1, close - 1)
        return CharStr(src, open, close)
    }

    fun decodeValue(src: Series<Char>, open: Int, close: Int, tag: IOMemento): Any? = when (tag) {
        IOMemento.IoString  -> decodeText(src, open, close)
        IOMemento.IoBoolean -> src[open] == 't'
        IOMemento.IoNothing -> null
        IOMemento.IoDouble  -> decodeText(src, open, close)
        else -> null
    }

    val COL_META: Series<`ColumnMeta↻`> = 4 j { c: Int ->
        when (c) { 0->ColumnMeta("open",IOMemento.IoInt);1->ColumnMeta("close",IOMemento.IoInt);2->ColumnMeta("tag",IOMemento.IoObject);3->ColumnMeta("children",IOMemento.IoObject);else->error("4") }.let { { it } }
    }

    data class FlatIndex(
        val spans: Series<Twin<Int>>,
        val tags: Series<IOMemento>,
        val depths: Series<Int>,
        val childOf: (Int) -> Series<Int>,
    )

    fun scan0(src: Series<Byte>): Join<Cursor, FlatIndex> {
        val n = src.size
        val chars: Series<Char> = n j { i: Int -> src[i].toInt().toChar() }
        val sOpen = series()
        val sClose = series()
        val sTag = ChunkedMutableSeries<IOMemento>()
        fun add(o: Int, c: Int, t: IOMemento) { sOpen.add(o); sClose.add(c); sTag.add(t) }
        data class P(val open: Int, val tag: IOMemento)
        val stack = ChunkedMutableSeries<P>()
        var inQ = false; var esc = false
        fun push(o: Int, t: IOMemento) { stack.add(P(o, t)) }
        fun pop(c: Int) { if(stack.size==0)return;val p=stack.removeAt(stack.size-1);add(p.open,c,p.tag) }
        var i = 0
        while (i < n) {
            val c = chars[i]
            when {
                inQ -> when {
                    esc -> { esc = false; if (c == '"') { inQ = false; pop(i) } }
                    c == '\\' -> esc = true
                    c == '"' -> { inQ = false; pop(i) }
                    else -> {}
                }
                else -> when (c) {
                    '{' -> push(i, IOMemento.IoObject)
                    '[' -> push(i, IOMemento.IoArray)
                    '}' -> pop(i)
                    ']' -> pop(i)
                    '"' -> { push(i, IOMemento.IoString); inQ = true }
                    't' -> { if (i+3<n && chars[i+1]=='r' && chars[i+2]=='u' && chars[i+3]=='e') { add(i, i+3, IOMemento.IoBoolean); i += 3 } }
                    'f' -> { if (i+4<n && chars[i+1]=='a' && chars[i+2]=='l' && chars[i+3]=='s' && chars[i+4]=='e') { add(i, i+4, IOMemento.IoBoolean); i += 4 } }
                    'n' -> { if (i+3<n && chars[i+1]=='u' && chars[i+2]=='l' && chars[i+3]=='l') { add(i, i+3, IOMemento.IoNothing); i += 3 } }
                    '-', '+', in '0'..'9' -> {
                        val s = i
                        while (i < n) { val ch = chars[i]; if (ch !in '0'..'9' && ch != '.' && ch != 'e' && ch != 'E' && ch != '+' && ch != '-') break; i++ }
                        add(s, i-1, IOMemento.IoDouble); continue
                    }
                }
            }
            i++
        }
        while (stack.size > 0) { val p = stack.removeAt(stack.size - 1); add(p.open, n-1, p.tag) }
        return buildTree(sOpen, sClose, sTag)
    }


    fun buildTree(sOpen: ChunkedMutableSeries<Int>, sClose: ChunkedMutableSeries<Int>, sTag: ChunkedMutableSeries<IOMemento>): Join<Cursor, FlatIndex> {
        val total = sOpen.size
        val spans = total j { k: Int -> sOpen[total-1-k] j sClose[total-1-k] }
        val tags  = total j { k: Int -> sTag[total-1-k] }
        val depths = total j { idx: Int -> val si=spans[idx];(0 until total).count{k:Int->k!=idx&&spans[k].a<si.a&&spans[k].b>si.b} }
        val childOf = { idx: Int -> val si=spans[idx];val o=si.a;val cl=si.b;val td=depths[idx]+1;val b=IntArray(total);var ct=0;for(k in 0 until total){if(k==idx)continue;val sk=spans[k];if(sk.a>o&&sk.b<cl&&depths[k]==td)b[ct++]=k};val c=ct;val a=b;c j { out:Int->a[out] } }
        val rvC = arrayOfNulls<RowVec>(total)
        fun row(i: Int): RowVec { rvC[i]?.let{return it};val sp=spans[i];val tg=tags[i];val dc=childOf(i);val cc:Cursor=dc.size j { k:Int->row(dc[k]) };val rv=(4 j { c:Int->when(c){0->(sp.a as Any?)j COL_META[0];1->(sp.b as Any?)j COL_META[1];2->(tg as Any?)j COL_META[2];3->(cc as Any?)j COL_META[3];else->error("4")}})as RowVec;rvC[i]=rv;return rv }
        val rc = (0 until total).count { k: Int -> depths[k] == 0 }; val ri = IntArray(rc); var rx = 0
        for (i in 0 until total) if (depths[i] == 0) ri[rx++] = i
        return (rc j { k: Int -> row(ri[k]) }) j FlatIndex(spans, tags, depths, childOf)
    }

    fun series(): ChunkedMutableSeries<Int> {
        val sOpen = ChunkedMutableSeries<Int>()
        return sOpen
    }

    // ── scanIndex — wrap scan0 into a ConfixIndex FacetedRow ────────

    fun scanIndex(src: Series<Byte>): ConfixIndex {
        val (_, flat) = scan0(src)
        val n = flat.spans.size

        // KeyToChild: scan IoString tokens, map text → token index
        val keyCache = LinkedHashMap<String, Int>()
        for (i in 0 until n) {
            if (flat.tags[i] == IOMemento.IoString) {
                val s = flat.spans[i]
                // build key from src bytes (strip quotes for JSON, raw for CBOR)
                val kOpen = s.a + 1
                val kClose = s.b - 1
                if (kClose >= kOpen) {
                    val key = CharArray(kClose - kOpen + 1) { j ->
                        src[kOpen + j].toInt().toChar()
                    }.concatToString()
                    if (key !in keyCache) keyCache[key] = i
                }
            }
        }

        return n j { op: Any? ->
            @Suppress("UNCHECKED_CAST")
            when (op) {
                ConfixIndexK.Spans          -> flat.spans
                ConfixIndexK.Tags           -> flat.tags
                ConfixIndexK.Depths         -> flat.depths
                ConfixIndexK.DirectChildren -> flat.childOf
                ConfixIndexK.TreeCursor     -> scan(src)
                ConfixIndexK.KeyToChild     -> ({ key: CharSequence ->
                    keyCache[key.toString()]
                })
                else                        -> null
            }
        }
    }

}
