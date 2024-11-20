package borg.trikeshed.common.collections.associative.trie

import borg.trikeshed.common.collections.binarySearch
import borg.trikeshed.lib.*

/**
 * a prefix tree (trie) implementation
 */
class RadixTree<C : Comparable<C>>(var root: RadixTreeNode<C>? = null) {
    // TODO: Implement RadixTree methods
}

/**
 * a node in a prefix tree (trie)
 */
class RadixTreeNode<C : Comparable<C>>(
    var key: Series<C> = emptySeries(),
    var term: Boolean = false,
    var children: Array<RadixTreeNode<C>>? = null
) {
    operator fun plus(other: Series<C>): RadixTreeNode<C> {
        // TODO: Implement plus operation
        throw NotImplementedError()
    }

    fun keys(prefix: Series<C>? = null): List<Series<C>> {
        // TODO: Implement keys method
        throw NotImplementedError()
    }
}
