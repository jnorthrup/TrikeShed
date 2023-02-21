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


    interface SplittableScope<T> : ValueScope<T> {     override   var end: Int

        var entries: Series<Int>
    }

    /**
     * parses only the top level of a json object or array and simulates a stack by inc/dec of a depth counter to
     * track the nesting of {} [] pairs
     * @param src the input charSeries
     * @return the index of the top level object or array
     */
    fun open(src: CharSeries): SplittableScope<*> {
        lateinit var index: SplittableScope<*>
        var depth = 0
        var inString = false
        var escape = false
        val commas = mutableListOf<Int>()

        do {
            val c = src.get
            if (inString) {
                if (escape) escape = false
                else if (c == '\\') escape = true
                else if (c == '"') inString = false
            } else when (c) {
                '{' -> {
                    if (depth == 0) index = SplittableObject(src)
                    depth++
                }

                '[' -> {
                    if (depth == 0) index = SplittableArray(src)
                    depth++
                }

                '}', ']' -> {
                    depth--
                    if (depth == 0) {
                        index.end = src.pos
                        index.entries = (_l[index.start] + commas).toSeries()
                        break
                    }
                }

                '"' -> inString = true
                ',' -> if (depth == 1) commas.add(src.pos)
            }
        } while (src.hasRemaining)
        return index
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
            scope.let {
                while (it.mk.get.isWhitespace());
                val c = it.mk.res.get
                keys[x] j when (c) {
                    '{', '[' -> open(it.res) as AnyScope
                    '\"' -> IndexString(it.res) as AnyScope
                    't', 'f' -> IndexBoolean(c) as AnyScope
                    'n' -> IndexNull
                    else -> IndexNumber(it.res)
                } as AnyScope
            }
        }


        val keys: Series<CharSeries> by lazy {
            entries.size j { x: Int ->
                val first = entries[x]
                val last = if (x == entries.size - 1) end else entries[x + 1]
                var tmp = anchor.slice.lim(last).pos(first).slice
                while (tmp.get != '\"');
                tmp = tmp.slice.mk
                while (tmp.get != '\"');
                tmp.fl
            }
        }

        val values: Series<CharSeries> by lazy {
            entries.size j { x: Int ->
                val first = entries[x]
                val last = if (x == entries.size - 1) end else entries[x + 1]
                val tmp = anchor.slice.lim(last).pos(first).slice
                while (tmp.get != ':');
                while (tmp.mk.get.isWhitespace());
                tmp.res.slice
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
            var a=anchor.clone().lim(end).pos(start).slice
            while(a.mk.get!='"');
            a=a.res.slice
            var isEscaped = false
            do {
            //handle escapes until unescaped quote
            } while (a.mk.get != '"' || isEscaped)
            a.fl.asString()
        }
    }

    class IndexBoolean(val c:Char) : ValueScope<Boolean> {
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
    ) : ValueScope<Number> {
        override val reify: Double by lazy { anchor.clone().lim(end).pos(start).slice.parseDouble() }
    }

    object IndexNull: ValueScope<Any?> {
        override val anchor: CharSeries
            get() = TODO("Not yet implemented")
        override val start: Int
            get() = TODO("Not yet implemented")
        override val end: Int
            get() = TODO("Not yet implemented")
        override val reify: Any?=null
    }
}
