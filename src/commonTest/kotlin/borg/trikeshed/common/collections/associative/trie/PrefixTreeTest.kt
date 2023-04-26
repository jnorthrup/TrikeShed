package borg.trikeshed.common.collections.associative.trie

import borg.trikeshed.common.collections.s_
import borg.trikeshed.lib.isEmpty
import borg.trikeshed.lib.toSeries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class PrefixTreeTest {

    @Test
    fun insertAndVerifyNodes() {
        // Insert the values "banana", "banner", and "ban" into the PrefixTree
        val sBanana = "banana".toSeries()
        val sBanner = "banner".toSeries()
        val sBan = "ban".toSeries()
        var tree = RadixTree<Char>()
        val tsBanana = tree.insert(sBanana).also { tree = it }
        val tsBanner = tree.insert(sBanner).also { tree = it }
        val tsBan = tree.insert(sBan).also { tree = it }

        // Verify 1 Transient node and 3 sorted Terminal nodes
        when (tree) {
            is RadixTree.TransientNode<Char> -> {
                assertEquals(1, (tree as RadixTree.TransientNode<Char>).children.size, "Expected 1 Transient node")

                when (val transientNode = (tree as RadixTree.TransientNode<Char>).children.firstOrNull()) {
                    is RadixTree.TransientNode<Char> -> {
                        assertEquals(3, transientNode.children.size, "Expected 3 sorted Terminal nodes")
                        assertEquals(sBan, transientNode)
                        val terminalNodes = transientNode.children.map { it as RadixTree.TerminalNode<Char> }
                        assertTrue(terminalNodes[0].isEmpty())
                        assertEquals(s_['a', 'n', 'a'], terminalNodes[1])
                        assertEquals(s_['n', 'e', 'r'], terminalNodes[2])
                    }

                    is RadixTree.TerminalNode -> TODO()
                    null -> TODO()
                    else -> fail("Expected TransientNode as a child of the root")
                }
            }

            else -> fail("Expected TransientNode as the root")
        }
    }
}
