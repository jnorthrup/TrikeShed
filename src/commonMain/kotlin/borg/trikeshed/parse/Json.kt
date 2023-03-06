@file:Suppress("ControlFlowWithEmptyBody")

package borg.trikeshed.parse

import borg.trikeshed.common.collections.s_
import borg.trikeshed.common.parser.simple.CharSeries
import borg.trikeshed.lib.*

typealias JsElement = Join<Twin<Int>, Series<Int>> //(openIdx j closeIdx) j commaIdxs
typealias JsIndex = Join<Twin<Int>, Series<Char>> //(twin j src)
typealias JsContext = Join<JsElement, Series<Char>> //(element j src)
typealias JsPathElement = Either<String, Int>
typealias JsPath = Series<JsPathElement>

val List<*>.toJsPath: JsPath
    get() = this.α {
        when (it) {
            is String -> JsPathElement.left(it)
            is Int -> JsPathElement.right(it)
            else -> throw IllegalArgumentException("expected String or Int, got $it")
        }
    }

val JsContext.segments: Series<JsIndex>
    get() {
        val (element, src) = this
        val (openIdx, closeIdx) = element.first
        val commaIdxs: Series<Int> = combine(s_[openIdx], element.second, s_[closeIdx])
        return commaIdxs.`▶`.zipWithNext().map { (a: Int, b: Int) -> ((a j b)) }.toList() α { it j src }
    }

object JsonParser {
    /** includes open and close braces and provides a list of comma indexes*/
    fun index(
        src: Series<Char>,
        /** depths is passed in for the purpose of queries being able to skip a slot if it is too shallow */
        depths: MutableList<Int>? = mutableListOf(),
        /*  * an optional int that gives you n commas max, presuming undefined null bias in the last comma */
        takeFirst: Int? = null,
    ): JsElement {
        var depth = 0
        var openIdx = -1
        var closeIdx = -1
        val commaIdxs: MutableList<Int> = mutableListOf()
        var insideQuote = false
        var escapeNextChar = false
        var maxDepth = -1

        for (i in 0 until src.size) {
            val c: Char = src[i]
            when {
                takeFirst?.takeIf { commaIdxs.size >= it } != null -> break
                insideQuote -> {
                    when {
                        escapeNextChar -> escapeNextChar = false
                        c == '\\' -> escapeNextChar = true
                        c == '"' -> insideQuote = false
                    }
                }

                else -> when (c) {

                    '{', '[' -> {
                        depth++.also { if (it > maxDepth) maxDepth = it }
                        if (depth == 1) openIdx = i
                    }

                    '}', ']' -> {
                        if (depth == 1) depths?.add(maxDepth)

                        depth--
                        if (depth == 0) {
                            closeIdx = i
                            break
                        }
                    }

                    ',' -> if (depth == 1) {
                        commaIdxs.add(i)

                        //record and reset maxDepth
                        depths?.add(maxDepth)
                        maxDepth = -1


                    }

                    '"' -> insideQuote = true
                }
            }
        }
        return (openIdx j closeIdx) j commaIdxs.toIntArray().toSeries()



    }


    fun reify(
        /** includes open and close braces, or both quotes, or the raw type*/
        src1: Series<Char>,
    ): Any? {
        val src: CharSeries = CharSeries(src1).trim

        return when (val c: Char = src.mk.get) {
            '{', '[' -> {
                val index = index(src)
                val (openIdx, closeIdx) = index.first
                val commaIdxs: Series<Int> = index.second

                val isObj = '{' == c
                //if obj we create k-v pairs otherwise we create values

                //iterate  segments exclusive of src first and last and commas in the middle
                combine(s_[openIdx], commaIdxs, s_[closeIdx]).`▶`.zipWithNext().α { (before, after) ->
                    if (isObj) {
                        val tmp = CharSeries(src[before.inc() until after]).trim
                        require(tmp.seekTo('"')) {
                            "malformed open quote in ${tmp.take(40).asString()}"
                        }
                        tmp.pos.let { openQuote ->
                            require(tmp.seekTo('"', '\\')) {
                                "malformed close-quote in ${tmp.take(40).asString()}"
                            }
                            (tmp.pos - 1).let { closeQuote ->
                                require(tmp.seekTo(':')) {
                                    "expected colon in ${tmp.take(40).asString()}"
                                }
                                tmp.slice.let { valueContext ->
                                    tmp.lim(closeQuote).pos(openQuote).asString() j reify(valueContext)
                                }
                            }
                        }
                    } else reify(CharSeries(src[before.inc() until after]).trim)
                }.let { if (isObj) it.`▶`.map { (it as Join<*, *>).pair }.toMap() else it }
            }

            '"' -> {
                val beg = src.pos
                val seekTo = src.seekTo('"', '\\')
                if (!seekTo) throw Exception("expected end of quoted string")
                val end = src.pos - 1
                src.lim(end).pos(beg).asString()
            }

            't', 'f' -> 't' == c
//            'n' -> null
            else -> src.res.slice.parseDoubleOrNull()
        }
    }

    /** a recursive depth-first search of the json tree, the path is a series of strings and ints,
     *  the ints are indexes into arrays, the strings are keys into objects
     *  @param context the current context, the element and the src
     *  @param path the path to the desired node
     *  @param depths the depths of the nodes, this is used to skip nodes that are too shallow
     *  @param reifyResult whether to reify the payload or return the JsIndex
     *  @param payload a mutable array that is used to pass the payload back up the stack
     *  @return on success the payload, on any other outcome Unit is returned
     *
     *
     *
     *
     */
    fun jsPath(
        /**contains the indexes and the src chars */
        context: JsContext,
        /** strings will enter by obj key, indexes will deliver nth slot for obj or array*/
        path: JsPath,
        /**whether success payload is reified or JsIndex */
        reifyResult: Boolean = true,
        /**path and depth have a relationship, path needs depth to succeed, so this can aid in skipping shallow
        nodes */
        depths: MutableList<Int>? = null,
//        payload: Array<Any?> = arrayOf(Unit),
    ): Any? {
        val (element, src) = context
        val (pathHead: JsPathElement, pathTail: JsPath) = path.first() j path.drop(1)

        return pathHead.fold(
            { key ->
                val (openIdx, closeIdx) = element.first
                var r: Any? = Unit
                if ('{' == src[openIdx]) {
                    val segments = context.segments
                    var segIdx = 0
                    do {//while segments

                        val segment = segments[segIdx]
                        if (depths?.size == segments.size && depths[segIdx] < path.size) continue

                        val (segOpenIdx, segCloseIdx) = segment.first
                        val tmp = CharSeries(src).lim(segCloseIdx).pos(segOpenIdx).trim
                        if (tmp.get != '"') continue
                        val q0 = tmp.pos
                        if (!tmp.seekTo('"', '\\')) continue
                        val q1 = tmp.pos
                        if (tmp[q0 until q1].asString() != key) continue
                        if (tmp.skipWs.get != ':') continue
                        if (pathTail.isEmpty()) {
                            r = if (reifyResult) reify(tmp.trim) else tmp.trim
                            break
                        }
                        //index pos .. lim
                        val depths1: MutableList<Int> = mutableListOf()
                        val src1 = tmp.slice
                        val context1 = index(src1, depths1)

                        r = jsPath(context1 j tmp.slice, pathTail, reifyResult, depths1)
                        if (r != Unit) break
                    } while (++segIdx < segments.size)
                }
                return@fold r
            },
            { idx ->
                var r: Any? = Unit
                do {
                    val (openIdx, closeIdx) = element.first
                    val isObj = '{' == src[openIdx]

                    val segments = context.segments
                    if (segments.size <= idx) break
                    val segment = segments[idx]
                    if (depths?.size == segments.size && depths[idx] < path.size) break
                    if (pathTail.isEmpty()) {//this is the sought element
                        // if obj skip the key and colon
                        val (segOpenIdx, segCloseIdx) = segment.first
                        val tmp = CharSeries(src).lim(segCloseIdx).pos(segOpenIdx).trim
                        if (isObj) {
                            if (tmp.get != '"') break
                            if (!tmp.seekTo('"', '\\')) break
                            if (tmp.skipWs.get != ':') break
                        }
                        val trim = tmp.trim
                        r = if (reifyResult) reify(trim) else trim

                    } else {
                        val depths1: MutableList<Int> = mutableListOf()
                        val ctx: JsContext = index(src, depths1) j src
                        r = jsPath(ctx, pathTail, reifyResult, depths1)
                    }
                } while (false)
                return@fold r
            }
        )
    }

}
