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
) : Series<Char> by buf, CharSequence { //delegate to the underlying series

    // ── CharSequence, the conversion currency ────────────────────────────
    // The point of this type was to hand text onward as a CharSequence and
    // never take a String dependency. The exit existed only as `Series<Char>.cs`,
    // an adapter that allocated a fresh wrapper per call and had zero callers in
    // the tree; every real exit went through asString(). Conforming directly
    // removes the wrapper and makes the currency the default rather than a
    // deliberate act.
    //
    // Semantics are EXACTLY the adapter's, which are exactly the indexing this
    // class already had: `Join.get(key) = b(key)`, so a member `get` shadowing
    // that extension is the same call. Nothing indexes differently than before.
    override val length: Int get() = size
    override fun get(index: Int): Char = b(index)
    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
        CharSeries(this[startIndex until endIndex])


    /** get, the verb - the char at the current position and increment position */
    inline val get: Char
        get() {
            if (!hasRemaining) throw IndexOutOfBoundsException("pos: $pos, limit: $limit")
            val c = get(pos); pos++; return c
        }

    //string ctor
    constructor(s: String) : this(s.toCharArray().toSeries())

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
        if (other !is CharSeries) return false
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
    override fun hashCode(): Int = cacheCode


    fun asString(upto: Int = Int.MAX_VALUE): String =
        ((limit - pos) j { x: Int -> this[x + pos] }).toArray().concatToString()

    /**
     * DELIBERATELY NOT THE CHARACTERS. A friendly toString() is not a
     * convenience on this type, it is a reification contract: grant it here and
     * every Series and Join is expected to carry one, which is the java baggage
     * this family exists without. Materialising text is an EFFECT and belongs at
     * a call site that asked for it — [asString], or handing this to something
     * that takes a CharSequence.
     *
     * Note what most CharSequence consumers actually do: StringBuilder.append,
     * Regex and Pattern.matcher read length/get and never call toString. Only
     * String.valueOf and a "$x" template do, and those are exactly the two
     * places where reification should be visible in the source.
     *
     * The peek is taken directly off the accessor. The previous rendering read
     * `asString().take(4)`, which materialised the whole buffer to show four
     * characters — the effect this comment is about, inside the diagnostic that
     * was supposed to be free.
     */
    override fun toString(): String {
        val n = minOf(4, size)
        val peek = CharArray(n) { b(it) }.concatToString()
        return "CharSeries(position=$pos, limit=$limit, mark=$mark, size=$size, take-4=$peek)"
    }

    /** skipws and rtrim */
    val trim: CharSeries
        get() = apply { confixScope(Char::isWhitespace) }

    /** mutating operation to shrink the buffer  */
    fun confixScope(pred: (Char) -> Boolean) {
        var p = pos
        var l = limit
        while (p < l && pred(get(p))) p++
        while (l > p && pred(get(l.dec()))) l--
        lim(l)
        pos(p)
    }


    /**
     * The CURSOR is spent — pos has reached limit. This is not CharSequence's
     * isEmpty(), which asks whether there are no characters at all; a fully
     * parsed buffer is exhausted but not empty. It was named `isEmpty` and
     * commented "isEmpty override" while overriding nothing, and had no callers
     * in the tree; conforming to CharSequence turned that into a JVM signature
     * clash, which is the first time the ambiguity cost anything.
     */
    val isExhausted: Boolean get() = pos == limit

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
        return CharArray(rem) { i -> get(pos + i) }
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

        private fun confixFeature(client: CharSeries, chlit: String): Boolean {
            logNone { "confix $chlit before: ${client.asString()}" }
            var x = 0
            client.confixScope { test: Char ->
                val target = chlit[x]
                (target == test && x < 2).apply { if (this) x++ }
            }
            return x == 2.debug {
                logNone { "confix $chlit  after: ${client.asString()}" }
            }
        }
    }
}

operator fun Series<Char>.div(delim: Char): Series<Series<Char>> { //lazy split
    val intList = mutableListOf<Int>()
    for (x in 0 until size) if (this[x] == delim) intList.add(x)
    val iarr = intList.toIntArray()
    val partsCount = iarr.size + 1

    return partsCount j { partIdx ->
        val p = if (partIdx == 0) 0 else iarr[partIdx - 1] + 1
        val l = if (partIdx == iarr.size) this.size else iarr[partIdx]
        this[p until l]
    }
}

operator fun Series<Byte>.div(delim: Byte): Series<Series<Byte>> { //lazy split
    val intList = mutableListOf<Int>()
    for (x in 0 until size) if (this[x] == delim) intList.add(x)
    val iarr = intList.toIntArray()
    val partsCount = iarr.size + 1

    return partsCount j { partIdx ->
        val p = if (partIdx == 0) 0 else iarr[partIdx - 1] + 1
        val l = if (partIdx == iarr.size) this.size else iarr[partIdx]
        this[p until l]
    }
}

/**
 * CharSequence over a bare [Series]<Char>. [CharSeries] IS a CharSequence and
 * needs no adapter — prefer it, or this, over asString() at any boundary that
 * accepts a CharSequence.
 */
val Series<Char>.cs: CharSequence
    get() = if (this is CharSeries) this else object : CharSequence {
        override val length: Int by ::a
        override fun get(index: Int) = b(index)
        override fun toString(): String = asString()
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = this@cs[startIndex until endIndex].cs
    }

fun CharSeries.splitWs(): Series<CharSeries> {
    val result = mutableListOf<CharSeries>()
    var start = pos
    while (start < limit) {
        while (start < limit && b(start).isWhitespace()) start++
        if (start >= limit) break
        var end = start
        while (end < limit && !b(end).isWhitespace()) end++
        result.add(CharSeries(this[start until end]))
        start = end
    }
    return result.toSeries()
}
