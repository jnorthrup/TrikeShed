@file:Suppress("ControlFlowWithEmptyBody")

package borg.trikeshed.parse

import borg.trikeshed.common.collections._l
import borg.trikeshed.common.parser.simple.CharSeries
import borg.trikeshed.lib.*
import borg.trikeshed.parse.JsonParser.ValueScope

typealias AnyScope = ValueScope<Any?>

val nilset: Join<Int, (Int) -> Int> = 0 j { 0 }

object JsonParser {
    /** scan an input charSeries creating an index of matched {} [] pairs and for each a list of ','  */
    val empty: IntArray = IntArray(0)
    val emptyAlpha: Series<Int> = nilset

    sealed interface ValueScope<T> {
        val anchor: CharSeries
        val start: Int
        val end: Int
        val reify: T
    }


    interface SplittableScope<T> : ValueScope<T> {
        override var end: Int

        var entries: Series<Int>
    }

    /**
     * parses only the top level of a json object or array and simulates a stack by inc/dec of a depth counter to
     * track the nesting of {} [] pairs
     * @param src the input charSeries
     * @return the index of the top level object or array
     */
    fun open(src: CharSeries): SplittableScope<*> {
        lateinit var splittable: SplittableScope<*>
        var depth = 0
        var inString = false
        var escape = false
        val commas = mutableListOf<Int>()
        val mark = src.pos

        do {
            val c = src.mk.get
            if (inString) {
                if (escape) escape = false
                else if (c == '\\') escape = true
                else if (c == '"') inString = false
            } else when (c) {
                '{' -> {
                    if (depth == 0) splittable = SplittableObject(src, src.mark)
                    depth++
                }

                '[' -> {
                    if (depth == 0) splittable = SplittableArray(src, src.mark)
                    depth++
                }

                '}', ']' -> {
                    depth--
                    if (depth == 0) {
                        splittable.end = src.pos
                        splittable.entries = (_l[splittable.start] + commas).toSeries()
                        break
                    }
                }

                '"' -> inString = true
                ',' -> if (depth == 1) commas.add(src.mark)

            }
        } while (src.hasRemaining)
        src.pos(mark)
        return splittable
    }


    class SplittableObject(
        override val anchor: CharSeries,
        override val start: Int = anchor.pos,
        override var end: Int = anchor.limit,
        override var entries: Series<Int> = nilset
    ) : SplittableScope<Map<String, Any?>>, Series2<CharSeries, AnyScope> {

        override val a: Int by entries::size
        override val b: (Int) -> Join<CharSeries, AnyScope> = { x: Int ->

            val scope = values[x]
            scope.let { tmp ->
                while (tmp.mk.get.isWhitespace());
                val c = tmp.res.get
                keys[x] j when (c) {
                    '{', '[' -> open(tmp.res) as AnyScope
                    '\"' -> IndexString(tmp.res) as AnyScope
                    't', 'f' -> IndexBoolean(c) as AnyScope
                    'n' -> IndexNull
                    else -> IndexNumber(tmp.res)
                } as AnyScope
            }
        }


        val keys: Series<CharSeries> by lazy {
            entries.size j { x: Int ->
                val first = entries[x]
                val last = if (x == entries.size - 1) end else entries[x + 1]
                val tmp = anchor.clone().pos(first).lim(last)
                while (tmp.get != '\"');
                var inEscape = false
                val beg1: Int = tmp.pos
                while (!inEscape && tmp.get != '\"') {
                    if (inEscape) inEscape = false
                    else if (tmp.get == '\\') inEscape = true
                    tmp.mk
                }
                CharSeries(anchor[beg1 until tmp.mark])
            }
        }

        val values: Series<CharSeries> by lazy {
            entries.size j { x: Int ->
                val first = entries[x]
                val last = if (x == entries.size - 1) end else entries[x + 1]
                val tmp = anchor.clone().lim(last).pos(first)
                while (tmp.get != ':');

                tmp.slice
            }
        }


        override val reify: Map<String, Any?> by lazy { this.`▶`.map { it.a.asString() to it.b.reify }.toMap() }
    }

    class SplittableArray(
        override val anchor: CharSeries,
        override val start: Int = anchor.pos,
        override var end: Int = anchor.limit,
        override var entries: Series<Int> = nilset
    ) : SplittableScope<Array<Any?>>, Series<AnyScope> {
        override val reify: Array<Any?> by lazy { this.`▶`.map { it.reify }.toTypedArray() }
        override val a: Int by entries::size
        override val b: (Int) -> ValueScope<Any?> = { x: Int ->
            val scope = entries[x]
            val tmp = anchor.slice.lim(end).pos(scope).slice
            while (tmp.mk.get.isWhitespace());
            val c = tmp.mk.get
            when (c) {
                '{', '[' -> open(tmp.res)
                '\"' -> IndexString(tmp.res)
                't', 'f' -> IndexBoolean(c)
                'n' -> IndexNull
                else -> IndexNumber(tmp.res)
            } as AnyScope
        }
    }

    class IndexString(
        override val anchor: CharSeries,
        override val start: Int = anchor.pos,
        override val end: Int = anchor.limit
    ) : ValueScope<String> {
        override val reify: String by lazy {
            val refinedCharSeries = refineCharSeries()
            val asString = refinedCharSeries.asString()
            asString
        }

        //seek to first quote, move one past, seek to next unescaped quote 
        fun refineCharSeries(): CharSeries {

            val ankor = anchor.pos(start)
            while (ankor.get != '\"');
            val p1 = ankor.pos
            var inEscape = false
            while (!inEscape && ankor.mk.get != '\"') {
                if (inEscape) inEscape = false
                else if (ankor.get == '\\') inEscape = true
                ankor.mk
            }
            val p2 = ankor.mark
            return CharSeries(ankor[p1 until p2])
        }
    }

    class IndexBoolean(val c: Char) : ValueScope<Boolean> {
        override val anchor: CharSeries
            get() = TODO("Not yet implemented")
        override val start: Int
            get() = TODO("Not yet implemented")
        override val end: Int
            get() = TODO("Not yet implemented")
        override val reify: Boolean = c == 't'
    }

    class IndexNumber(
        override val anchor: CharSeries,
        override val start: Int = anchor.pos,
        override val end: Int = anchor.limit
    ) : ValueScope<Double> {
        override val reify: Double by lazy { anchor.clone().lim(end).pos(start).slice.parseDouble() }
    }

    object IndexNull : ValueScope<Any?> {
        override val anchor: CharSeries
            get() = TODO("Not yet implemented")
        override val start: Int
            get() = TODO("Not yet implemented")
        override val end: Int
            get() = TODO("Not yet implemented")
        override val reify: Any? = null
    }
}

