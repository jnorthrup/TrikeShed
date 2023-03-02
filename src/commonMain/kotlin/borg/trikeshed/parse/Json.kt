@file:Suppress("ControlFlowWithEmptyBody")

package borg.trikeshed.parse

import borg.trikeshed.common.collections.s_
import borg.trikeshed.common.parser.simple.CharSeries
import borg.trikeshed.lib.*

typealias JsElement = Join<Twin<Int>, Series<Int>> //(openIdx j closeIdx) j commaIdxs


object JsonParser {

    /** includes open and close braces and provides a list of comma indexes*/

    fun index(src: Series<Char>): JsElement {
        var depth = 0
        var openIdx = -1
        var closeIdx = -1
        val commaIdxs: MutableList<Int> = mutableListOf()
        var insideQuote = false
        var escapeNextChar = false
        for (i in 0 until src.size) {
            val c: Char = src[i]
            when {
                insideQuote -> {
                    when {
                        escapeNextChar -> escapeNextChar = false
                        c == '\\' -> escapeNextChar = true
                        c == '"' -> insideQuote = false
                    }
                }

                else -> when (c) {

                    '{', '[' -> {
                        depth++
                        if (depth == 1) openIdx = i
                    }

                    '}', ']' -> {
                        depth--
                        if (depth == 0) {
                            closeIdx = i
                            break
                        }
                    }

                    ',' -> if (depth == 1) commaIdxs.add(i)
                    '"' -> insideQuote = true
                }
            }
        }
        return (openIdx j closeIdx) j commaIdxs.toIntArray().toSeries()
    }


    fun reify(
        /** includes open and close braces, or both quotes, or the raw type*/
        src1: Series<Char>
    ): Any? {
        val src: CharSeries = CharSeries(src1).trim

        val c: Char = src.mk.get
        return when (c) {
            '{', '[' -> {
                val index = index(src)
                val (openIdx, closeIdx) = index.first
                val commaIdxs = index.second

                val isObj = '{' == c
                //if obj we create k-v pairs otherwise we create values

                //iterate  segments exclusive of src first and last and commas in the middle
                (s_[openIdx] + commaIdxs + s_[closeIdx]).`▶`.zipWithNext().map { (before, after) ->
                    if (isObj) {
                        val tmp = CharSeries(src[before.inc() until after]).trim
                        val quoted = tmp.seekTo('"')
                        if (!quoted) throw Exception("expected quoted key")
                        val openQuote = tmp.pos
                        val keyBegin = tmp.seekTo('"', '\\')
                        if (!keyBegin) throw Exception("expected end of quoted key")
                        val keyLast = tmp.pos - 1
                        val colonBegin = tmp.seekTo(':')
                        if (!colonBegin) throw Exception("expected colon")
                        val valueContext = tmp.slice
                        val value = reify(valueContext)

                        //set limit to create a slice that excludes the quotes
                        tmp.lim(keyLast).pos(openQuote).asString() to value
                    } else {
                        reify(src[before.inc() until after])
                    }
                }.let { res ->
                    if (isObj) {
                        (res as List<Pair<String, Any?>>).toMap()
                    } else {
                        res.toTypedArray()
                    }
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
            'n' -> null
            else -> {
                src.parseDoubleOrNull()
            }
        }
    }
}