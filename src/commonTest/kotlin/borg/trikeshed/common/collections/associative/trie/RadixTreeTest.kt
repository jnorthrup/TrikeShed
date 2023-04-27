package borg.trikeshed.common.collections.associative.trie

import borg.trikeshed.lib.debug
import borg.trikeshed.lib.toSeries
import kotlin.test.Test

class RadixTreeTest {

    @Test
    fun insertAndVerifyNodes() {
        // Insert the values "banana", "banner", "ban", "banshee" into the PrefixTree
        val banana = "banana".toSeries()
        val banner = "banner".toSeries()
        val ban = "ban".toSeries()
        val banshee = "banshee".toSeries()
        val tree = RadixTree<Char>()
        tree + banana
        tree + banner
        tree + ban
        tree + banshee
        debug { }
        // Verify the nodes

    }
}


