@file:Suppress("ControlFlowWithEmptyBody")

package borg.trikeshed.parse

import borg.trikeshed.common.collections.s_
import borg.trikeshed.lib.*
import borg.trikeshed.lib.CharSeries.Companion.unbrace
import borg.trikeshed.lib.CharSeries.Companion.unquote

typealias JsElement = Join<Twin<Int>, Series<Int>> //(openIdx j closeIdx) j commaIdxs
typealias JsIndex = Join<Twin<Int>, Series<Char>> //(twin j src)
typealias JsContext = Join<JsElement, Series<Char>>


typealias JsPathElement = Either<String, Int>
typealias JsPath = Series<JsPathElement>

//private fun logDebug(t: () -> String) {} //logging turned off for now

fun JsIndex.toSeries(): Series<Char> = this.second[a.a until a.b]

val List<*>.toJsPath: JsPath
    get() = this.toSeries() α {
        when (it) {
            is String -> JsPathElement.left(it)
            is Int -> JsPathElement.right(it)
            else -> throw IllegalArgumentException("expected String or Int, got $it")
        }
    }

/** delimiter-exclusive segments in a splittable json element.
 *  e.g. ",1," in "[0,1,2,3]" would be "1"
 *
 *  "[ 0 , " in "[0,1,2,3]" would be "0"
 *
 *  " 3 ]" in "[0,1,2,3]" would be "3"
 */
val JsContext.segments: Iterable<JsIndex>
    get() {
        val (element, src) = this
        val (openIdx, closeIdx) = element.first
        val commaIdxs: Series<Int> = combine(s_[openIdx], element.second, s_[closeIdx])
        return commaIdxs.`▶`.zipWithNext().map { (a: Int, b: Int) -> a.inc() j b }.toList() α { it j src }
    }

/** a json scanner that indexes and optionally reifies the json chars
 * and provides a way to query it.
 */
object JsonParser {
    /** includes open and close braces and provides a list of comma indexes*/
    fun index(
        src: Series<Char>,
        /** depths is passed in for the purpose of queries being able to skip a slot if it is too shallow;
         * format is _a[1,1,2,] where any valid segment is at least 1.
         * */
        depths: MutableList<Int>? = null,
        /*  * an optional int that gives you n commas max, presuming undefined null bias in the last comma */
        takeFirst: Int? = null,
    ): JsElement {
        var depth = 0
        var openIdx = -1
        var closeIdx = -1
        val commaIdxs: MutableList<Int> = mutableListOf()
        var insideQuote = false
        var escapeNextChar = false
        var maxDepth = 0
        for (i in 0 until src.size) {
            val c: Char = src[i]
            when {

                insideQuote -> when {
                    escapeNextChar -> escapeNextChar = false
                    c == '\\' -> escapeNextChar = true
                    c == '"' -> insideQuote = false
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
                        maxDepth = 0
                        if (takeFirst != null && commaIdxs.size >= takeFirst) break
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
                val index: JsElement = index(src)
                val (openIdx: Int, closeIdx: Int) = index.first
                val commaIdxs: Series<Int> = index.second

                val isObj = '{' == c
                //if obj we create k-v pairs otherwise we create values

                //iterate  segments exclusive of src first and last and commas in the middle
                val combine: Series<Int> = combine(s_[openIdx], commaIdxs, s_[closeIdx])
                if (commaIdxs.isEmpty()) {
                    val (before, after) = combine.toArray()
                    val possiblyEmpty = src.clone().lim(after).pos(before + 1).trim
                    if (!possiblyEmpty.hasRemaining)
                        return if (isObj) emptyMap<String, Any?>()
                        else emptyArray<Any?>()
                }

                combine.`▶`.zipWithNext().map { (before, after) ->
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
                }.let {
                    if (isObj) it.associate {
                        val join = it as Join<*, *>
                        val (key, value) = join
                        key.let {
                            it as? String ?: (it as? Series<Char>)?.asString() ?: (it as? CharSeries)?.asString()
                            ?: it
                        } to value
                    } else it as? String ?: (it as? Series<Char>)?.asString() ?: (it as? CharSeries)?.asString()
                    ?: it
                }
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
     *  the ints are indexes into elements, the strings are keys into objects exclusively
     *  @param context the current context, the element and the src
     *  @param path the path to the desired node
     *  @param depths the depths of the nodes, this is used to skip nodes that are too shallow
     *  @param reifyResult whether to reify the payload or return the JsIndex
     *  @param payload a mutable array that is used to pass the payload back up the stack
     *  @return on success the payload, on any other outcome Unit is returned
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
        depths: List<Int>? = null,
//        payload: Array<Any?> = arrayOf(Unit),
    ): Any? {
        val (pathHead: JsPathElement, pathTail: JsPath) = path.first() j path.drop(1)

        return pathHead.fold(
            /** this, String branch, performs the search of a key by descending into each segment, and looking for a
             * key that matches, and then will recurse into that, or return the desired form
             */
            selectByKey(context, pathTail, reifyResult),
            /** this will check for enough segments to select the desired element, and
             *  then will recurse into that or return the desired form, discarding the
             *  segment key as necessary.  This works on both obj and array */
            selectByIndex(context, pathTail, reifyResult)
        )
    }

    private fun selectByKey(
        context: JsContext,
        pathTail: Series<Either<String, Int>>,
        reifyResult: Boolean,
    ): (String) -> Any? = { key: String ->
        val (element: JsElement, src) = context
        var counter = 0
        var r: Any? = Unit  //this is the payload
        val cs = CharSeries(src).trim
        logDebug { "select By Key $key from ${cs.asString()}" }
        if (unbrace(cs)) {

            for (segment in (context.segments α { t ->
                val (bounds: Twin<Int>, src: Series<Char>) = t
                val (pos, lim) = bounds
                CharSeries(src, pos, lim).trim
            })) {
                /*
                        we have arrived at a place where string has been trimmed and braces have been confirmed and descoped.
                        we are now in the payload of the obj  and we are testing keys to see if they match the desired key
                        we are goign to forego escaped quotes for now, and just assume that the key is not source code.
                        */
                segment.also {
                    logDebug { "segment is ${it.asString()} at index ${counter++}" }
                }
                val tmp = (segment.trim).slice
                val valueMark = tmp.seekTo(':')
                assert(valueMark) { "expected colon in ${tmp.asString()}" }
                if (!valueMark) continue
                val value = tmp.slice //
                val key0 = (tmp.dec().flip()).debug { logDebug { "key0 is ${it.asString()}" } }.trim


                if (unquote(key0) && key0.seekTo(key.toSeries())
                    && (!key0.hasRemaining)
                ) {
                    logDebug { "key matches" }
                    (value).debug { logDebug { "using value ${it.asString()}" } }
                    r = resumePath(pathTail, reifyResult, value)
                    break
                }
            }
        }
        r.debug { logDebug { "final return is ${(r as? Series<Char>)?.asString() ?: r}" } }
    }


    private fun resumePath(
        pathTail: Series<Either<String, Int>>,
        reifyResult: Boolean,
        tmp: CharSeries,

        ): Any? {
        return if (pathTail.isEmpty()) {
            if (reifyResult) reify(tmp.slice) else tmp.slice

        } else {
            val depths1: MutableList<Int> = mutableListOf()

            val nextPath = pathTail.take(1).first()
            if (nextPath.isLeft) logDebug { "descending with pathTail ${pathTail.size}" } else {
                val rightOrNull = nextPath.rightOrNull
                logDebug { "descending with pathTail ${pathTail.size} and index $rightOrNull" }
            }
            jsPath(
                index(tmp, depths1, nextPath.rightOrNull?.inc()) j tmp,
                pathTail.debug { logDebug { "descending with pathTail ${pathTail.size}" } },
                reifyResult,
                depths1
            )
        }
    }

    fun selectByIndex(
        context: JsContext,
        pathTail: Series<Either<String, Int>>,
        reifyResult: Boolean,
    ): (Int) -> Any? = { idx: Int ->
        var r: Any? = Unit
        val (element: JsElement, src) = context
        val (twin, _) = element
        val (pos, lim) = twin
        val cs = CharSeries(src, pos, lim).trim
        val inObj = cs[0] == '{'
        do {
            val tmp = CharSeries(context.segments.elementAtOrNull(idx)?.toSeries() ?: break)
            val value: CharSeries = if (inObj) {
                if (!tmp.seekTo(':')) break
                tmp.slice
            } else tmp

            r = resumePath(pathTail, reifyResult, value)
        } while (false)
        r
    }
}

