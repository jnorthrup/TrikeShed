package borg.trikeshed.common.parser.simple

import borg.trikeshed.lib.*
import borg.trikeshed.lib.CZero.nz
import borg.trikeshed.lib.CZero.z
import kotlin.math.max

/**
 * char based spiritual successor to ByteBuffer for parsing
 */
class CharSeries(buf: Series<Char>) : Series<Char> {
    override val a: Int = buf.a
    override val b: (Int) -> Char = buf.b

    var pos = 0
    var limit = size
    var mark = -1

    val get: Char
        get() {
            require(pos < limit)
            return b(pos++)
        }

    //string ctor
    constructor(s: String) : this(s.toSeries())

    /**remaining chars*/
    val rem: Int get() = limit - pos

    /**max capacity of this buffer*/
    val cap: Int get() = size

    /**remaining chars as a string*/
    val hasRemaining get() = rem.nz

    /** mark the current position */
    val mk
        get() = apply {
            mark = pos
        }

    //reset
    val res
        get() = apply {
            pos = max(mark, pos)
        }

    //flip
    val fl
        get() = apply {
            limit = pos
            pos = 0
            mark = -1
        }

    //rewind
    val rew
        get() = apply {
            pos = 0
        }

    //clear
    val clr
        get() = apply {
            pos = 0
            limit = size
            mark = -1
        }

    //position
    fun pos(p: Int) = apply {
        pos = p
    }

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

    //Java ByteBuffer style slice
    val slice: CharSeries get() = CharSeries(this[pos until limit])

    fun asString(upto: Int = Int.MAX_VALUE): String =
        ((limit - pos) j { x: Int -> this[x + pos] }).toArray().concatToString()

    override fun toString(): String {
        val take = asString().take(4)
        return "CharSeries(position=$pos, limit=$limit, mark=$mark, cacheCode=$cacheCode,take-4=${take})"
    }

    fun lim(i: Int) = apply { limit = i }

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