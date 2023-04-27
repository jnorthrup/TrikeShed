package borg.trikeshed.common.collections.associative.trie

import borg.trikeshed.lib.*
import kotlin.test.Test

class RadixTreeTest {
    val banana = "banana".toSeries()
    val banner = "banner".toSeries()
    val ban = "ban".toSeries()
    val banshee = "banshee".toSeries()

    @Test
    fun testKeys() {
        val tree = RadixTree<Char>()
        tree + banshee
        tree + ban
        tree + banana
        tree + banner
        tree + "b1bomber".toSeries()
        debug { }
        val keys = tree.keys()
        assert(keys.size == 5)
        debug {
            for (key in keys) {
                println(key.asString())
            }

        }
    }

    @Test
    fun insertAndVerifyNodes() {
        // Insert the values "banana", "banner", "ban", "banshee" into the PrefixTree

        val tree = RadixTree<Char>()
        tree + banana
        tree + banner
        tree + ban
        tree + banshee
        debug { }
        // Verify the nodes
        tree.root?.let { root ->
            assert(0 == root.key.cpb.compareTo("ban".toSeries().cpb))
            assert(root.term)
            assert(root.children?.size == 3)
            root.children?.let { children ->
                assert(children[0].key.asString() == "ana".toSeries().asString())
                assert(children[0].term)
                assert(children[0].children == null)
                assert(children[1].key.asString() == "ner".toSeries().asString())
                assert(children[1].term)
                assert(children[1].children == null)
                assert(children[2].key.asString() == "shee".toSeries().asString())
                assert(children[2].term)
                assert(children[2].children == null)
            }
        }
    }

}