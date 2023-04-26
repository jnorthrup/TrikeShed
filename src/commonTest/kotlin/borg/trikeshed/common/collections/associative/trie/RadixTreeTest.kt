package borg.trikeshed.common.collections.associative.trie

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import kotlin.test.Test
import kotlin.test.assertEquals

class RadixTreeTest {

    @Test
    fun insertAndVerifyNodes() {
        // Insert the values "banana", "banner", "ban", "banshee" into the PrefixTree
        val banana = "banana".toSeries()
        val banner = "banner".toSeries()
        val ban = "ban".toSeries()
        val banshee = "banshee".toSeries()
        val tree = RadixTree<Char, Series<Char>>()
        tree + banana
        tree + banner
        tree + ban
        tree + banshee
        assertEquals(4, tree.size)


    }
}


