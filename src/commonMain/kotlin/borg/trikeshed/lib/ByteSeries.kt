package borg.trikeshed.lib

import borg.trikeshed.lib.CZero.nz

fun Series<Byte>.decodeUtf8(charArray: CharArray? = null): Series<Char> =
    charArray?.let { decodeDirtyUtf8(it) } ?: if (isDirtyUTF8()) decodeDirtyUtf8() else (this α {
        it.toInt().toChar()
    })

fun Series<Byte>.decodeDirtyUtf8(charArray: CharArray = CharArray(size)): Series<Char> {
    var y = 0
    var w = 0
    while (y < this.size && w < charArray.size) {
        val c = this[y++].toInt()
        when (c shr 4) {
            in 0..7 -> charArray[w++] = c.toChar()
            0x0C, 0x0D -> {
                val c2 = this[y++].toInt()
                charArray[w++] = ((c and 0x1F) shl 6 or (c2 and 0x3F)).toChar()
            }
            0x0E -> {
                val c2 = this[y++].toInt()
                val c3 = this[y++].toInt()
                charArray[w++] = ((c and 0x0F) shl 12 or (c2 and 0x3F) shl 6 or (c3 and 0x3F)).toChar()
            }
        }
    }
    return w j charArray::get
}

fun Series<Byte>.asString(): String = toArray().decodeToString()

class ByteSeries(
    buf: Series<Byte>,
    var pos: Int = 0,
    var limit: Int = buf.size,
    var mark: Int = -1,
) : Series<Byte> by buf {

    // Small byte-window cache to improve locality. Uses buf.b(index) fallback when cache miss.
   var _byteCache: ByteArray? = null
   var _cacheBase: Int = 0
   var _cacheLen: Int = 0
   val BYTE_CACHE_WINDOW: Int = 4096

   fun raw(i: Int): Byte {
        val c = _byteCache
        if (c != null) {
            val b = _cacheBase
            val l = _cacheLen
            if (i >= b && i < b + l) return c[i - b]
        }
        return b(i)
    }

    val get: Byte
        get() {
            if (!hasRemaining) throw IndexOutOfBoundsException("pos: $pos, limit: $limit")
            val c = raw(pos); pos++; return c
        }

    constructor(s: String) : this(s.toSeries().encodeToByteArray().toSeries())

    constructor(buf: ByteArray, pos: Int = 0, limit: Int = buf.size) : this(
        buf α { it },
        pos
    )

    val rem: Int get() = limit - pos
    val cap: Int by ::size
    val hasRemaining: Boolean get() = rem.nz

    val mk: ByteSeries get() = apply { mark = pos }
    val res: ByteSeries get() = apply { pos = if (mark < 0) pos else mark }

    fun flip(): ByteSeries = apply {
        limit = pos
        pos = 0
        mark = -1
    }

    val rew: ByteSeries get() = apply { pos = 0 }
    val clr: ByteSeries get() = apply {
        pos = 0
        limit = size
        mark = -1
    }

    fun pos(p: Int): ByteSeries = apply { pos = p }

    val slice: ByteSeries
        get() = ByteSeries(this[pos until limit])

    fun lim(i: Int): ByteSeries = apply { limit = i }

    val skipWs: ByteSeries get() = apply { while (hasRemaining && get.toInt().toChar().isWhitespace()); res }
    val rtrim: ByteSeries get() = apply { while (rem > 0 && raw(limit - 1).toInt().toChar().isWhitespace()) limit-- }

    fun clone(): ByteSeries = ByteSeries(a j b).also { it.pos = pos; it.limit = limit; it.mark = mark }

    val cacheCode: Int
        get() {
            var h = 1
            for (i in pos until limit) {
                h = 31 * h + raw(i).hashCode()
            }
            return h
        }

    override fun equals(other: Any?): Boolean {
        when {
            this === other -> return true
            other !is ByteSeries -> return false
            pos != other.pos -> return false
            limit != other.limit -> return false
            mark != other.mark -> return false
            size != other.size -> return false
            else -> {
                for (i in 0 until size) if (b(i) != other.b(i)) return false
                return true
            }
        }
    }

    override fun hashCode(): Int {
        var result = pos
        result = 31 * result + limit
        result = 31 * result + mark
        result = 31 * result + size
        result = 31 * result + cacheCode
        return result
    }

    fun asString(upto: Int = Int.MAX_VALUE): String =
        ((limit - pos) j { x: Int -> raw(x + pos) }).toArray().decodeToString().take(upto)

    override fun toString(): String {
        val take = asString().take(4)
        return "ByteSeries(position=$pos, limit=$limit, mark=$mark, cacheCode=$cacheCode, take-4=${take})"
    }

    /** mutating operation to shrink the buffer  */
    fun confixScope(pred: (Byte) -> Boolean) {
        var p = pos
        var l = limit
        while (p < l && pred(raw(p))) p++
        while (l > p && pred(raw(l - 1))) l--
        lim(l)
        pos(p)
    }

    val trim: ByteSeries
        get() = apply { confixScope { it.toInt().toChar().isWhitespace() } }

    val isEmpty: Boolean get() = pos == limit

    fun seekTo(target: Byte, escape: Byte? = null): Boolean {
        val anchor = pos
        var escaped = false
        while (hasRemaining) {
            val c = get
            if (escaped) escaped = false
            else if (c == target) return true
            else if (escape != null && c == escape) escaped = true
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

    operator fun dec(): ByteSeries = apply { require(pos > 0) { "Underflow" }; pos-- }
    operator fun inc(): ByteSeries = apply { require(hasRemaining) { "Overflow" }; pos++ }

    /** Split on whitespace into zero-copy ByteSeries slices. */
    fun splitWs(): Series<ByteSeries> {
        val parts = mutableListOf<ByteSeries>()
        var i = pos
        while (i < limit && raw(i).toInt().toChar().isWhitespace()) i++
        while (i < limit) {
            val start = i
            while (i < limit && !raw(i).toInt().toChar().isWhitespace()) i++
            parts.add(ByteSeries(this[start until i]))
            while (i < limit && raw(i).toInt().toChar().isWhitespace()) i++
        }
        return parts.toSeries()
    }

    fun toArray(): ByteArray = ByteArray(rem, ::get)

    companion object {

        /** returns true and advances the position if the confix is {} */
        fun unbrace(it: ByteSeries): Boolean {
            val chlit = byteArrayOf('{'.code.toByte(), '}'.code.toByte(), ' '.code.toByte())
            return confixFeature(it, chlit)
        }

        /** returns true and advances the position if the confix is [] */
        fun unbracket(it: ByteSeries): Boolean {
            val chlit = byteArrayOf('['.code.toByte(), ']'.code.toByte(), ' '.code.toByte())
            return confixFeature(it, chlit)
        }

        /** returns true and advances the position if the series is quoted */
        fun unquote(it: ByteSeries): Boolean {
            val chlit = byteArrayOf('"'.code.toByte(), '"'.code.toByte(), ' '.code.toByte())
            return confixFeature(it, chlit)
        }

       fun confixFeature(client: ByteSeries, chlit: ByteArray): Boolean {
            logNone { "confix ${chlit.decodeToString()} before: ${client.asString()}" }
            var x = 0
            client.confixScope { test: Byte ->
                val target = chlit[x]
                (target == test && x < 2).apply { if (this) x++ }
            }
            return x == 2.debug {
                logNone { "confix ${chlit.decodeToString()}  after: ${client.asString()}" }
            }
        }
    }
}

/**
 * Checks if the byte series contains "dirty" UTF-8 sequences.
 *
 * "Dirty" UTF-8 refers to sequences that contain multi-byte characters.
 * This function scans the byte series to identify such sequences.
 *
 * @receiver Series<Byte> The byte series to check.
 * @return Boolean True if the byte series contains dirty UTF-8 sequences, false otherwise.
 */
fun Series<Byte>.isDirtyUTF8(): Boolean {
    var dirty = false
    val bsz = this.size
    val barLen = bsz - 1
    for (b in 0 until barLen)
        if ((this[b].toInt() shr 4) in 0x0C..0x0E) {
            val byte = this[b + 1]
            if ((byte.toInt() shr 6) == 0x02) {
                dirty = true
                break
            }
        }
    return dirty
}

fun ByteSeries.decodeToString() = decodeUtf8().asString()

fun Series<Byte>.startsWith(s: String): Boolean {
    val join = s.encodeToByteArray() α { it }
    return join.size <= size && join.zip(this).view.all { it.a == it.b }
}

fun Series<Byte>.endsWith(s: String): Boolean {
    val join = s.encodeToByteArray() α { it }
    return join.size <= size && join.zip(this.reversed()).view.all { it.a == it.b }
}

operator fun Series<Byte>.div(delim: Byte): Series<Series<Byte>> { //lazy split
    val intList = mutableListOf<Int>()
    for (x in 0 until size) if (this[x] == delim) intList.add(x)

    /**
     * iarr is an index of delimitted endings of the ByteSeries.
     */
    val iarr: IntArray = intList.toIntArray()

    return iarr α { x ->
        val p = if (x == 0) 0 else iarr[x.dec()].inc() //start of next
        val l = //is x last index?
            if (x == iarr.lastIndex)
                this.size
            else
                iarr[x].dec()
        this[p until l]
    }
}
