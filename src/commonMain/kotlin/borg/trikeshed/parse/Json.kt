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
        src1: Series<Char> ): Any? {
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
}