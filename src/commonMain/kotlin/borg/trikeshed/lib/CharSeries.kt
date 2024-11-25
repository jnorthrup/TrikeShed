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

    /**
     * Extension property to trim whitespace from both ends of a `CharSeries`.
     *
     * @receiver The `CharSeries` to be trimmed.
     * @return The `CharSeries` with leading and trailing whitespace removed.
     */
    val trim: CharSeries
        get() = apply { confixScope(Char::isWhitespace) }

    /**
     * Mutating operation to shrink the buffer.
     *
     * @param pred A predicate function that takes a `Char` and returns a `Boolean`.
     *             The buffer will be shrunk by removing characters from the start and end
     *             that satisfy this predicate.
     */
    fun confixScope(pred: (Char) -> Boolean) {
        var p = pos
        var l = limit
        // Increment the start position while the predicate is true
        while (p < l && pred(get(p))) p++
        // Decrement the end position while the predicate is true
        while (l > p && pred(get(l.dec()))) l--
        // Set the new limit
        lim(l)
        // Set the new position
        pos(p)
    }

    //isEmpty override
    val isEmpty: Boolean get() = pos == limit

    /**
     * Moves the position to the character after the target character if found.
     *
     * @param target The character to seek.
     * @return `true` if the target character is found and the position is moved to the character after it,
     *         `false` if the target character is not found and the position remains unchanged.
     */
    fun seekTo(
        /** The target character to seek. */
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

    /**
     * Moves the position to the character after the target character if found.
     *
     * @param target The character to seek.
     * @param escape If present, this character escapes one character.
     * @return `true` if the target character is found and the position is moved to the character after it,
     *         `false` if the target character is not found and the position remains unchanged.
     */
    fun seekTo(
        /** The target character to seek. */
        target: Char,
        /** If present, this character escapes one character. */
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

    /**
     * Moves the position to the end of the given literal if found.
     *
     * @param lit The series of characters to seek.
     * @return `true` if the literal is found and the position is moved to the end of it,
     *         `false` if the literal is not found and the position remains unchanged.
     */
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

    /**
     * Moves the position back by one character.
     *
     * @return The `CharSeries` with the position moved back by one character.
     * @throws IllegalArgumentException if the position is already at the start.
     */
    operator fun dec(): CharSeries = apply { require(pos > 0) { "Underflow" }; pos-- }

    /**
     * Moves the position forward by one character.
     *
     * @return The `CharSeries` with the position moved forward by one character.
     * @throws IllegalArgumentException if there are no remaining characters.
     */
    operator fun inc(): CharSeries = apply { require(hasRemaining) { "Overflow" }; pos++ }

    /**
     * Converts the remaining characters in the `CharSeries` to a `CharArray`.
     *
     * @return A `CharArray` containing the remaining characters.
     * @throws IllegalStateException if the `CharSeries` is empty.
     */
    fun toArray(): CharArray {
        require(rem > 0) { "heads up: using an empty stateful CharSeries toArray()" }
        return CharArray(rem, ::get)
    }

    companion object {

        /**returns true and advances the position if the confix is {}*/
        fun unbrace(it: CharSeries): Boolean {
            val chlit = "{} "
            return confixFeature(it, chlit)
        }

        /**returns true and advances the position if the confix is []*/
        fun unbracket(it: CharSeries): Boolean {
            val chlit = "[] "
            return confixFeature(it, chlit)

        }

        /**returns true and advances the position if the series is quoted */
        fun unquote(it: CharSeries): Boolean {
            val chlit = "\"\" "
            return confixFeature(it, chlit)

        }

        /**
         * Applies a confix feature to the given CharSeries.
         *
         * @param client The CharSeries to apply the confix feature to.
         * @param chlit A string representing the confix characters.
         * @return True if the confix feature was successfully applied, false otherwise.
         */
        private fun confixFeature(client: CharSeries, chlit: String): Boolean {
            // Log the initial state of the CharSeries
            logNone { "confix $chlit before: ${client.asString()}" }
            var x = 0
            // Apply the confix scope to the CharSeries
            client.confixScope { test: Char ->
                val target = chlit[x]
                // Check if the current character matches the target character
                (target == test && x < 2).apply { if (this) x++ }
            }
            // Return true if the confix feature was successfully applied, false otherwise
            return x == 2.debug {
                // Log the final state of the CharSeries
                logNone { "confix $chlit  after: ${client.asString()}" }
            }
        }
    }
}

/**
 * Extension function to split a `Series<Char>` by a given delimiter.
 *
 * @receiver The `Series<Char>` to be split.
 * @param delim The character delimiter to split the series by.
 * @return A `Series<Series<Char>>` where each sub-series is a segment of the original series split by the delimiter.
 */
operator fun Series<Char>.div(delim: Char): Series<Series<Char>> { // lazy split
    // List to hold the indices of delimiter positions
    val intList = mutableListOf<Int>()
    // Iterate over the series and add the index of each delimiter to the list
    for (x in 0 until size) if (this[x] == delim) intList.add(x)

    /**
     * iarr is an index of delimited endings of the CharSeries.
     */
    val iarr: IntArray = intList.toIntArray()

    // Create and return a series of sub-series split by the delimiter
    return iarr α { x ->
        // Determine the start position of the next segment
        val p = if (x == 0) 0 else iarr[x.dec()].inc() // start of next
        // Determine the end position of the current segment
        val l = // is x last index?
            if (x == iarr.lastIndex)
                this.size
            else
                iarr[x].dec()
        // Return the sub-series from start to end position
        this[p until l]
    }
}

val Series<Char>.cs: CharSequence
    get() = object : CharSequence {
        override val length: Int by ::a
        override fun get(index: Int) = b(index)
        override fun toString(): String = asString()
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = this@cs[startIndex until endIndex].cs
    }
