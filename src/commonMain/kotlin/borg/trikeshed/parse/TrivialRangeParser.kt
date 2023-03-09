package borg.trikeshed.parse

import borg.trikeshed.common.collections.s_
import borg.trikeshed.lib.CharSeries
import borg.trikeshed.lib.div
import borg.trikeshed.lib.*

/***** Key and Index expressions against Range Indices:
 * simple range expressions:
 *   [min]..[max]  //inclusive range binary operator with defaults
 *   [min]until<max>   //endExclusive range binary operator with defaults
 *   (expr)','(expr) //comma separated expressions, in this case ordered
 *   '!'(index) //post-negation of a single index
 *
 *   the IntRange itself applies as a filter; so a blanket statement like: ..100 will work with ranges that
 *   don't reach 101
 *
 *   @return The concatenation of the ranges and constants, in order specified, with any negated indices removed
 *
 *   modelled after the simplicity of tasksel cpu selection expressions, not tweaked for efficiency on huge ranges
 *
 */
operator fun Series<Int>.get(expr: Series<Char>): Series<Int> {

    // outer scope is ','
    val groups: Series<CharSeries> =
        ((expr / ',') α { x:Series<Char> -> CharSeries(x).trim }).takeIf { it.isNotEmpty() } ?: s_[CharSeries(expr).trim]
    val negated = mutableSetOf<Int>()

    val bag = mutableListOf<Int>()
    (groups α { it.trim }).`▶`.forEach { y: CharSeries ->

        val tmp1: CharSeries = y
        when {
            y[0] == ('!') ->
                negated += y.pos(1).slice.trim.parseLong().toInt()

            y.seekTo("..".toSeries()) ->
                ((if (tmp1[0] == '.') this.first
                else
                    tmp1.slice.trim.parseLong().toInt()
                        )..(//is the second part empty?
                        if (tmp1[tmp1.limit - 1] == '.') this.last()
                        else tmp1.pos(tmp1.limit - 1).slice.trim.parseLong().toInt()
                        )).forEach { bag += it }

            y.seekTo("until".toSeries()) ->
                ((if (tmp1[0] == 'u') this.first
                else
                    tmp1.slice.trim.parseLong().toInt()
                        ) until tmp1.pos(tmp1.limit - 1).slice.trim.parseLong().toInt()).forEach { bag += it }

            else -> bag += y.slice.trim.parseLong().toInt()
        }
    }
    bag.toMutableSet().removeAll(negated)
    val toSet = this.toSet()
    return (bag.filter { it in toSet }).toSeries()
}


