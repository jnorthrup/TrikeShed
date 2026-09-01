package borg.trikeshed.lib

import borg.trikeshed.lib.CZero.nz

fun Series<Byte>.decodeUtf8(charArray: CharArray? = null): Series<Char> =
    charArray?.let { decodeDirtyUtf8(it) } ?: if (isDirtyUTF8()) decodeDirtyUtf8() else (this α {
        it.toInt().toChar()
    })

fun Series<Byte>.decodeDirtyUtf8(charArray: CharArray = CharArray(size)): Series<Char> {
    //does not use StringBuilder, but is faster than String(bytes, Charsets.UTF_8)
    var y = 0
    var w = 0
    while (y < this.size && w < charArray.size) {
        val c = this[y++].toInt()
        /* 0xxxxxxx */
        when (c shr 4) {
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

/**
 * byte based spiritual successor to ByteBuffer for parsing
 */
class ByteSeries(
    buf: Series<Byte>,

    /** the mutable position accessor */
    var pos: Int = 0,

    /** the limit accessor */
    var limit: Int = buf.size, //initialized to size

    /** the mark accessor */
    var mark: Int = -1,
) : Series<Byte> by buf { //delegate to the underlying series

    init {
        require(pos >= 0) { "pos must be non-negative" }
        require(limit >= pos) { "limit must be >= pos" }
        require(limit <= buf.size) { "limit must be <= buf.size" }
    }

    /** get, the verb - the char at the current position and increment position */
    inline val get: Byte
        get() {
            if (!hasRemaining) throw IndexOutOfBoundsException("pos: $pos, limit: $limit")
            val c = get(pos); pos++; return c
        }

    //string ctor
    constructor(s: String) : this(s.encodeToByteArray().toSeries())

    constructor(buf: ByteArray, pos: Int = 0, limit: Int = buf.size) : this(
        limit j buf::get,
        pos
    )

    /**remaining chars*/
    val rem: Int get() = limit - pos

    /** immutable max capacity of this buffer, alias for size*/
    val cap: Int by ::size

    /** boolean indicating if there are remaining chars */
    val hasRemaining: Boolean get() = rem.nz

    /** mark, the verb - marks the current position */
    val mk: ByteSeries
        get() = apply {
            mark = pos
        }

    /** reset pos to mark */
    val res: ByteSeries
        get() = apply {
            pos = if (mark < 0) pos else mark
        }

    /** flip the buffer, limit becomes pos, pos becomes 0 -- made into a function for possible side effects in debugger */
    fun flip(): ByteSeries = apply {
        limit = pos
        pos = 0
        mark = -1
    }

    /**rewind to 0*/
    val rew: ByteSeries
        get() = apply {
            pos = 0
        }

    /** clears the mark,pos, and sets limit to size */
    val clr: ByteSeries
        get() = apply {
            pos = 0
            limit = size
            mark = -1
        }

    /** position, the verb - holds the position that will be returned by the next get */
    fun pos(p: Int): ByteSeries = apply {
        pos = p
    }

    /** slice creates/returns a subrange ByteSeries from pos until limit */
    val slice: ByteSeries
        get() {
            val pos1 = this.pos
            val limit1 = this.limit
            val intRange = pos1 until limit1
            val buf = (this)[intRange]
            return ByteSeries(buf)
        }

    /** limit, the verb - redefines the last position accessable by get and redefines remaining accordingly*/
    fun lim(i: Int): ByteSeries = apply { limit = i }

    /** skip whitespace */
    val skipWs: ByteSeries get() = apply { while (hasRemaining && mk.get.toInt().toChar().isWhitespace()); res }

    val rtrim: ByteSeries get() = apply { while (rem > 0 && b(limit - 1).toInt().toChar().isWhitespace()) limit-- }


    fun clone(): ByteSeries = ByteSeries(a j b).also { it.pos = pos; it.limit = limit; it.mark = mark }


    /** a hash of contents only. not position, limit, mark */
    val cacheCode: Int
        get() {
            var h = 1
            for (i in pos until limit) {
                h = 31 * h + b(i).hashCode()
            }
            return h
        }

    /**
     * CONTENT, over the window. Identity used to fold in pos, limit and mark —
     * so advancing a cursor silently made a value a different value, which is an
     * undeclared effect on a type whose whole point is that effects are
     * declared. It also compared the entire backing buffer while [cacheCode]
     * hashed only pos..limit, so equal-hashing values could compare unequal.
     * Both halves now ask the same question: are these the same characters.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ByteSeries) return false
        if (rem != other.rem) return false
        for (i in 0 until rem) if (b(pos + i) != other.b(other.pos + i)) return false
        return true
    }

    /**
     * The content hash, and only that — so the value is a usable key. It folded
     * pos/limit/mark, all `var`, which made every instance an unstable key: put
     * one in a map, advance it, and the entry is unreachable. That is why the
     * soft-referenced token memo this hash was written for could never hit on a
     * repeated token — the cursor had moved by the time it came round again.
     */
    /**
     * THROWS. Keying is a reification: a map wants a canonical, stable value and
     * this is a CURSOR — pos moves, and the JDK's own CharSequence javadoc says
     * arbitrary CharSequences are inappropriate as keys precisely because the
     * contract is unspecified. A String key and a content-equal non-String key
     * do not match, and a HashMap reports that by returning null and telling
     * nobody.
     *
     * There is no effect system here to forbid it, so the contract is enforced
     * the only way left: loudly, at the point of misuse. Reify at the gate —
     * `asString()` — and key on that.
     *
     * [cacheCode] remains available for anyone building a cache that knows what
     * it is doing.
     */
    override fun hashCode(): Int =
        throw UnsupportedOperationException(
            "ByteSeries is a cursor, not a key — pos/limit move. " +
                "Reify at the gate with asString() and key on that; " +
                "cacheCode is the content hash if you are building the cache yourself."
        )


    fun asString(upto: Int = Int.MAX_VALUE): String = toArray().decodeToChars().asString().take(upto)

    /** Diagnostics, not content — see CharSeries.toString. The peek reads the
     *  accessor directly; `asString().take(4)` decoded the whole buffer to show
     *  four bytes. */
    override fun toString(): String {
        val n = minOf(4, size)
        val peek = CharArray(n) { b(it).toInt().toChar() }.concatToString()
        return "ByteSeries(position=$pos, limit=$limit, mark=$mark, size=$size, take-4=$peek)"
    }

    /** skipws and rtrim */
    val trim: ByteSeries
        get() = apply {
            confixScope { (0xff and it.toInt()).toChar().isWhitespace() }
        }

    /** mutating operation to shrink the buffer  */
    fun confixScope(pred: (Byte) -> Boolean) {
        var p = pos
        var l = limit
        while (p < l && pred(get(p))) p++
        while (l > p && pred(get(l.dec()))) l--
        lim(l)
        pos(p)
    }


    //isEmpty override
    val isEmpty: Boolean get() = pos == limit

    /** success move position to the char after found (exclusive) and returns true.
     *  fail returns false and leaves position unchanged */
    fun seekTo(
        /**target*/
        target: Byte,
    ): Boolean {
        val anchor = pos
        var escaped = false
        while (hasRemaining) {
            val c = get
            if (c == target)
                return true
        }
        pos = anchor
        return false
    }

    /** success move position to the char after found and returns true.
     *  fail returns false and leaves position unchanged */
    fun seekTo(
        /**target*/
        target: Byte,
        /**if present this escapes one char*/
        escape: Byte,
    ): Boolean {
        val anchor = pos
        var escaped = false
        while (hasRemaining) get.let { c ->
            if (escaped) escaped = false
            else when (c) {
                target -> return true
                escape -> escaped = true
            }
        }
        pos = anchor
        return false
    }

    fun seekTo(lit: Series<Byte>): Boolean {
        val anchor = pos
        var i = 0
        while (hasRemaining) {
            if (get == lit[i]) {
                i++
                if (i == lit.size) return true
            } else {
                i = 0
            }
        }
        pos = anchor
        return false
    }

    /**backtrack 1*/
    operator fun dec(): ByteSeries = apply { require(pos > 0) { "Underflow" }; pos-- }

    /** advance 1*/
    operator fun inc(): ByteSeries = apply { require(hasRemaining) { "Overflow" };pos++ }

    /**
     * this rewrites the Series default toArray() to use the position and limit
     */
    fun toArray(): ByteArray = ByteArray(rem) { i -> get(pos + i) }

    companion object {
        fun unbrace(it: ByteSeries): Boolean = confixFeature(it, "{} ")
        fun unbracket(it: ByteSeries): Boolean = confixFeature(it, "[] ")
        fun unquote(it: ByteSeries): Boolean = confixFeature(it, "\"\" ")

        private fun confixFeature(client: ByteSeries, chlit: String): Boolean {
            var x = 0
            client.confixScope { test: Byte ->
                val target = chlit[x].code.toByte()
                (target == test && x < 2).apply { if (this) x++ }
            }
            return x == 2
        }
    }

}


fun Series<Byte>.isDirtyUTF8(): Boolean {
    var dirty = false
    val bsz = this.size
    //if thereis one more byte to test and the first byte is in the range of 110x xxxx
    //what shr 4 proves: 110x xxxx
    val barLen = bsz.dec()
    for (b in 0 until barLen)
        if ((this[b].toInt() shr 4) in 0x0C..0x0E) {
            // what shr 4 proves: 1110 xxxx
            val byte = this[b.inc()]
            if ((byte.toInt() shr 6) == 0x02) {
                dirty = true
                break
                //what shr 6 proves: 10xx xxxx
            }
        }
    return dirty
}


fun ByteSeries.decodeToString() = decodeUtf8().asString()

fun Series<Byte>.startsWith(s: String): Boolean {
    val join = s.encodeToByteArray() α { it }
    return join.size <= size && join.zip(this).`▶`.all { it.a == it.b }
}

fun Series<Byte>.endsWith(s: String): Boolean {
    val join = s.encodeToByteArray() α { it }
    return join.size <= size && join.zip(this.reversed()).`▶`.all { it.a == it.b }
}

fun ByteSeries.splitWs(): Series<ByteSeries> {
    val result = mutableListOf<ByteSeries>()
    var start = pos
    while (start < limit) {
        while (start < limit && (0xff and get(start).toInt()).toChar().isWhitespace()) start++
        if (start >= limit) break
        var end = start
        while (end < limit && !(0xff and get(end).toInt()).toChar().isWhitespace()) end++
        result.add(ByteSeries(this[start until end]))
        start = end
    }
    return result.toSeries()
}
