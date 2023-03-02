package borg.trikeshed.common.parser.simple

import borg.trikeshed.lib.*
import borg.trikeshed.lib.CZero.nz
import kotlin.math.max

/**
 * char based spiritual successor to ByteBuffer for parsing
 */
class CharSeries(buf: Series<Char>) : Series<Char> {

    override val a: Int = buf.a
    override val b: (Int) -> Char = buf.b

    /** the mutable position accessor */
    var pos = 0

    /** the limit accessor */
    var limit = size //initialized to size

    /** the mark accessor */
    var mark = -1

    /** get, the verb - the char at the current position and increment position */
    val get: Char get() {
            if (!hasRemaining) throw IndexOutOfBoundsException("pos: $pos, limit: $limit")
            val c = b(pos); pos++; return c }

    //string ctor
    constructor(s: String) : this(s.toSeries())

    /**remaining chars*/
    val rem: Int get() = limit - pos

    /** immutable max capacity of this buffer, alias for size*/
    val cap: Int by ::size

    /** boolean indicating if there are remaining chars */
    val hasRemaining get() = rem.nz

    /** mark, the verb - marks the current position */
    val mk
        get() = apply {
            mark = pos
        }

    /** reset pos to mark */
    val res
        get() = apply {
            pos = if (mark < 0) pos else mark
        }

    /** flip the buffer, limit becomes pos, pos becomes 0 */
    val fl
        get() = apply {
            limit = pos
            pos = 0
            mark = -1
        }

    /**rewind to 0*/
    val rew
        get() = apply {
            pos = 0
        }

    /** clears the mark,pos, and sets limit to size */
    val clr
        get() = apply {
            pos = 0
            limit = size
            mark = -1
        }

    /** position, the verb - holds the position that will be returned by the next get */
    fun pos(p: Int) = apply {
        pos = p
    }

    /** slice creates/returns a subrange CharSeries from pos until limit */
    val slice: CharSeries get() = CharSeries(this[pos until limit])

    /** limit, the verb - redefines the last position accessable by get and redefines remaining accordingly*/
    fun lim(i: Int) = apply { limit = i }

    /** skip whitespace */
    val skipWs get() = apply { while (hasRemaining && mk.get.isWhitespace()); res }

    val rtrim get() = apply { while(rem>0 && b(limit-1).isWhitespace()) limit-- }



    fun clone() = CharSeries(a j b).also { it.pos = pos; it.limit = limit; it.mark = mark }


    /** a hash of contents only. not position, limit, mark */
    val cacheCode: Int
        get() {
            var h = 1
            for (i in pos until limit) {
                h = 31 * h + b(i).hashCode()
            }
            return h
        }

    override fun equals(other: Any?): Boolean {
        when {
            this === other -> return true
            other !is CharSeries -> return false
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

    /** idempotent, a cache can contain this hash and safely deduce the result from previous inserts */
    override fun hashCode(): Int {
        var result = pos
        result = 31 * result + limit
        result = 31 * result + mark
        result = 31 * result + size
//include cachecode
        result = 31 * result + cacheCode
        return result
    }


    fun asString(upto: Int = Int.MAX_VALUE): String =
        ((limit - pos) j { x: Int -> this[x + pos] }).toArray().concatToString()

    override fun toString(): String {
        val take = asString().take(4)
        return "CharSeries(position=$pos, limit=$limit, mark=$mark, cacheCode=$cacheCode,take-4=${take})"
    }

    fun trim(): CharSeries {
        var p = pos
        var l = limit
        while (p < l && get(p).isWhitespace()) p++
        while (l > p && get(l.dec()).isWhitespace()) l--
        return clone().pos(p).lim(l)
    }


    //isEmpty override
    val isEmpty: Boolean get() = pos == limit

}


//lazy split
operator fun CharSeries.div(delim: Char): Series<CharSeries> {
//fold -- forward scan to record a list of commas from hdr.get
    val intList = mutableListOf<Int>()
    while (this.hasRemaining) {
        val c = get
        if (c == delim) {
            intList.add(pos)
        }
    }

    /**
     * iarr is an index of delimitted endings of the CharSeries.
     */
    val iarr = intList.toIntArray()

    return iarr α { x ->
        /**handle first and last index) if (x == 0) 0 else iarr[x.dec()].inc()*/
        val p = if (x == 0) 0 else iarr[x.dec()].inc() //start of next
        val l = //is x last index?
            if (x == iarr.lastIndex)
                this.limit
            else
                iarr[x].dec()
        clone().slice.pos(p).lim(l)
    }
}

