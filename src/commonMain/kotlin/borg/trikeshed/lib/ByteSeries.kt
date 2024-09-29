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

    inline val get: Byte
        get() {
            if (!hasRemaining) throw IndexOutOfBoundsException("pos: $pos, limit: $limit")
            val c = get(pos)
            pos++
            return c
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
    val rtrim: ByteSeries get() = apply { while (rem > 0 && this[limit - 1].toInt().toChar().isWhitespace()) limit-- }

    fun clone(): ByteSeries = ByteSeries(this.toArray() α { it }).also { it.pos = pos; it.limit = limit; it.mark = mark }

    val cacheCode: Int
        get() {
            var h = 1
            for (i in pos until limit) {
                h = 31 * h + this[i].hashCode()
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

    fun asString(upto: Int = Int.MAX_VALUE): String = toArray().decodeToChars().asString().take(upto)

    override fun toString(): String {
        val take = asString().take(4)
        return "ByteSeries(position=$pos, limit=$limit, mark=$mark, cacheCode=$cacheCode, take-4=${take})"
    }

    val trim: ByteSeries
        get() = apply {
            var p = pos
            var l = limit
            while (p < l && this[p].toInt().toChar().isWhitespace()) p++
            while (l > p && this[l - 1].toInt().toChar().isWhitespace()) l--
            lim(l)
            pos(p)
        }

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

    operator fun dec(): ByteSeries = apply { require(pos > 0) { "Underflow" }; pos-- }
    operator fun inc(): ByteSeries = apply { require(hasRemaining) { "Overflow" }; pos++ }

    fun toArray(): ByteArray = ByteArray(rem, ::get)
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
    return join.size <= size && join.zip(this).`▶`.all { it.first == it.second }
}

fun Series<Byte>.endsWith(s: String): Boolean {
    val join = s.encodeToByteArray() α { it }
    return join.size <= size && join.zip(this.reversed()).`▶`.all { it.first == it.second }
}