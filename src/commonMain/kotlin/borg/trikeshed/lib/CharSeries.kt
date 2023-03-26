@file:Suppress("SpellCheckingInspection", "ControlFlowWithEmptyBody")

package borg.trikeshed.lib

import borg.trikeshed.lib.CZero.nz

/**
 * char based spiritual successor to ByteBuffer for parsing
 */
class CharSeries(
    buf: Series<Char>,

    /** the mutable position accessor */
    var pos: Int = 0,

    /** the limit accessor */
    var limit: Int = buf.size, //initialized to size

    /** the mark accessor */
    var mark: Int = -1,
) : Series<Char> by buf { //delegate to the underlying series

    /** get, the verb - the char at the current position and increment position */
    inline val get: Char
        get() {
            if (!hasRemaining) throw IndexOutOfBoundsException("pos: $pos, limit: $limit")
            val c = get(pos); pos++; return c
        }

    //string ctor
    constructor(s: String) : this(s.toSeries())

    /**remaining chars*/
    val rem: Int get() = limit - pos

    /** immutable max capacity of this buffer, alias for size*/
    val cap: Int by ::size

    /** boolean indicating if there are remaining chars */
    val hasRemaining: Boolean get() = rem.nz

    /** mark, the verb - marks the current position */
    val mk: CharSeries
        get() = apply {
            mark = pos
        }

    /** reset pos to mark */
    val res: CharSeries
        get() = apply {
            pos = if (mark < 0) pos else mark
        }

    /** flip the buffer, limit becomes pos, pos becomes 0 -- made into a function for possible side effects in debugger */
    fun flip(): CharSeries = apply {
        limit = pos
        pos = 0
        mark = -1
    }

    /**rewind to 0*/
    val rew: CharSeries
        get() = apply {
            pos = 0
        }

    /** clears the mark,pos, and sets limit to size */
    val clr: CharSeries
        get() = apply {
            pos = 0
            limit = size
            mark = -1
        }

    /** position, the verb - holds the position that will be returned by the next get */
    fun pos(p: Int): CharSeries = apply {
        pos = p
    }

    /** slice creates/returns a subrange CharSeries from pos until limit */
    val slice: CharSeries
        get() {
            val pos1 = this.pos
            val limit1 = this.limit
            val intRange = pos1 until limit1
            val buf = (this)[intRange]
            return CharSeries(buf)
        }

    /** limit, the verb - redefines the last position accessable by get and redefines remaining accordingly*/
    fun lim(i: Int): CharSeries = apply { limit = i }

    /** skip whitespace */
    val skipWs: CharSeries get() = apply { while (hasRemaining && mk.get.isWhitespace()); res }

    val rtrim: CharSeries get() = apply { while (rem > 0 && b(limit - 1).isWhitespace()) limit-- }


    fun clone(): CharSeries = CharSeries(a j b).also { it.pos = pos; it.limit = limit; it.mark = mark }


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

    /** skipws and rtrim */
    val trim: CharSeries
        get() = apply { confixScope(Char::isWhitespace) }

    /**     trims and mutates the string to remove front and back quotes  1-deep without escape checking*/
    val unquote: CharSeries
        get() = trim.apply {
            //hackish
            var first = true
            confixScope { (it == '"').also { first = !it } }
            first = true
            confixScope { (it == '"').also { first = !it } }
        }


    /** mutating operation to shrink the buffer  */
    fun confixScope(pred: (Char) -> Boolean) {
        var p = pos
        var l = limit
        while (p < l && pred(get(p))) p++
        while (l > p && pred(get(l.dec()))) l--
        lim(l)
        pos(p)
    }


    //isEmpty override
    val isEmpty: Boolean get() = pos == limit

    /** success move position to the char after found and returns true.
     *  fail returns false and leaves position unchanged */
    fun seekTo(
        /**target*/
        target: Char,
    ): Boolean {
        val anchor = pos
        var escaped = false
        while (hasRemaining) {
            val c = get
            if (c == target) return true
        }
        pos = anchor
        return false
    }

    /** success move position to the char after found and returns true.
     *  fail returns false and leaves position unchanged */
    fun seekTo(
        /**target*/
        target: Char,
        /**if present this escapes one char*/
        escape: Char,
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

    fun seekTo(lit: Series<Char>): Boolean {
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
    operator fun dec(): CharSeries = apply { require(pos > 0) { "Underflow" }; pos-- }

    /** advance 1*/
    operator fun inc(): CharSeries = apply { require(hasRemaining) { "Overflow" };pos++ }

    //toArray override
    fun toArray(): CharArray {
        require(rem > 0) { "heads up: using an empty stateful CharSeries toArray()" }
        return CharArray(rem, ::get)
    }


}

operator fun Series<Char>.div(delim: Char): Series<Series<Char>> { //lazy split
    val intList = mutableListOf<Int>()
    for (x in 0 until size) if (this[x] == delim) intList.add(x)

    /**
     * iarr is an index of delimitted endings of the CharSeries.
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