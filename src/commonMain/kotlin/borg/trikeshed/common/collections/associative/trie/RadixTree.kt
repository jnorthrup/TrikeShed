package borg.trikeshed.common.collections.associative.trie

import borg.trikeshed.common.collections.binarySearch
import borg.trikeshed.lib.*

/**
 * a prefix tree (trie) implementation
 */
class RadixTree<C : Comparable<C>>(var root: RadixTreeNode<C>? = null) {
    operator fun plus(key: Series<C>): RadixTreeNode<C> {
        root = root?.plus(key) ?: RadixTreeNode(key, true)
        return root!!
    }
    fun keys() = root?.keys() ?: emptyList()

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
        // Handle empty key case
        if (other.isEmpty()) {
            term = true
            return this
        }
        
        // Find common prefix length
        val commonPrefixLength = key.commonPrefixWith(other).size
        
        when {
            // Case 1: Current node's key is a prefix of the other key
            commonPrefixLength == key.size -> {
                val remainingKey = other.drop(commonPrefixLength)
                if (remainingKey.isEmpty()) {
                    term = true
                    return this
                }
                
                children?.let { children ->
                    val index = (children.toSeries() α { it.key.first() })
                        .binarySearch(remainingKey.first())
                    return when {
                        index >= 0 -> children[index] + remainingKey
                        else -> {
                            val insertIndex = -index - 1
                            val newNode = RadixTreeNode(remainingKey, true)
                            children.toMutableList().apply {
                                add(insertIndex, newNode)
                            }.toTypedArray().also { this.children = it }
                            this
                        }
                    }
                } ?: run {
                    children = arrayOf(RadixTreeNode(remainingKey, true))
                    return this
                }
            }
            
            // Case 2: Split current node
            commonPrefixLength < key.size -> {
                val commonPrefix = key.take(commonPrefixLength)
                val remainingCurrentKey = key.drop(commonPrefixLength)
                val remainingOtherKey = other.drop(commonPrefixLength)
                
                val newInternalNode = RadixTreeNode(commonPrefix)
                val currentNode = RadixTreeNode(remainingCurrentKey, term, children)
                val newNode = RadixTreeNode(remainingOtherKey, true)
                
                newInternalNode.children = if (remainingCurrentKey.first() < remainingOtherKey.first())
                    arrayOf(currentNode, newNode)
                else 
                    arrayOf(newNode, currentNode)
                    
                return newInternalNode
            }
            
            // Case 3: Other key is a prefix of current node's key
            else -> {
                val newRoot = RadixTreeNode(other, true)
                newRoot.children = arrayOf(RadixTreeNode(key.drop(commonPrefixLength), term, children))
                return newRoot
            }
        }
    }

    fun keys(prefix: Series<C>? = null): List<Series<C>> {
        val ret = mutableListOf<Series<C>>()
        val newPrefix = prefix?.takeUnless { it.isEmpty() }?.plus(this.key) ?: this.key

        if (term) ret.add(newPrefix)
        children?.let { children ->
            for (child in children) ret.addAll(child.keys(newPrefix))
        }
        return ret
    }
}
