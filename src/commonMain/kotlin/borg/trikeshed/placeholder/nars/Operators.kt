@file:Suppress("ControlFlowWithEmptyBody")

package borg.trikeshed.placeholder.nars

import borg.trikeshed.lib.*
import borg.trikeshed.common.parser.simple.CharSeries
import kotlinx.coroutines.*

class chgroup_(
    s: String,//sort and distinct the chars first to make the search faster,
    val chars: Series<Char> = s.toCharArray().distinct().sorted().toSeries()
) :ConditionalUnaryCharOp  {
      override fun invoke(p1: CharSeries): CharSeries? {
        // see https://pvk.ca/Blog/2012/07/03/binary-search-star-eliminates-star-branch-mispredictions/

        if (p1.hasRemaining) {
            val c = p1.get
            val i = chars.binarySearch(c)
            if (i >= 0) return p1
        }
        return null
    }
    companion object {
        //factory method for idempotent chgroup ops
        private val cache = mutableMapOf<String, chgroup_>()
        fun of(s: String) = cache.getOrPut(s) { chgroup_(s.debug { s -> logDebug { "chgrp: ($s) " } }) }
        val digit = of("0123456789")
        val hexdigit = of("0123456789abcdefABCDEF")
        val letter = of("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ")
        val lower = of("abcdefghijklmnopqrstuvwxyz")
        val upper = of("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
        val whitespace = of(" \t\n\r")
        val symbol = of("!@#\$%^&*()_+-=[]{}|;':\",./<>?")
    }
}
