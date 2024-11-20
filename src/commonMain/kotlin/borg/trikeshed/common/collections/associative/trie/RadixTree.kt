package borg.trikeshed.common.collections.associative.trie

import borg.trikeshed.common.collections.binarySearch
import borg.trikeshed.lib.*

private fun <C : Comparable<C>> Series<C>.commonPrefixLength(other: Series<C>): Int {
    val minLength = minOf(this.size, other.size)
    for (i in 0 until minLength) {
        if (this[i] != other[i]) return i
    }
    return minLength
}

/**
 * a prefix tree (trie) implementation
 */
class RadixTree<C : Comparable<C>>(var root: RadixTreeNode<C>? = null) {
    operator fun plus(series: Series<C>): RadixTree<C> {
        if (root == null) {
            root = RadixTreeNode(series, true)
        } else {
            root = root!! + series
        }
        return this
    }

    fun keys(): List<Series<C>> {
        return root?.keys() ?: emptyList()
    }
}

/**
 * a node in a prefix tree (trie)
 */
class RadixTreeNode<C : Comparable<C>>(
    var key: Series<C> = emptySeries(),
    var term: Boolean = false,
    var children: MutableList<RadixTreeNode<C>>? = null
) {
    private fun sortChildren() {
        children?.sortBy { it.key[0] }
    }
    operator fun plus(other: Series<C>): RadixTreeNode<C> {
        // Handle empty input
        if (other.isEmpty()) return this
        if (key.isEmpty() && children == null) {
            return RadixTreeNode(other, true)
        }
        
        // Find common prefix length
        val commonLength = key.commonPrefixLength(other)
        
        if (commonLength == 0) {
            // No common prefix, create new root with both nodes as children
            return RadixTreeNode(emptySeries(), false, 
                mutableListOf(this, RadixTreeNode(other, true)))
        }
        
        if (commonLength == key.size && commonLength == other.size) {
            // Exact match - mark as terminal
            term = true
            return this
        }
        
        if (commonLength == key.size) {
            // This node's key is a prefix of the new key
            val remainder = other.drop(commonLength)
            if (children == null) {
                children = mutableListOf(RadixTreeNode(remainder, true))
                return this
            }
            
            // Try to add to existing child
            val firstChar = remainder[0]
            val existingChild = children!!.find { it.key[0] == firstChar }
            
            if (existingChild != null) {
                val newChild = existingChild + remainder
                children!!.remove(existingChild)
                children!!.add(newChild)
                sortChildren()
            } else {
                children!!.add(RadixTreeNode(remainder, true))
                sortChildren()
            }
            return this
        }
        
        // Split this node
        val commonPrefix = key.take(commonLength)
        val thisRemainder = key.drop(commonLength)
        val otherRemainder = other.drop(commonLength)
        
        val newNode = RadixTreeNode(
            commonPrefix,
            false,
            mutableListOf(
                RadixTreeNode(thisRemainder, term, children),
                RadixTreeNode(otherRemainder, true)
            )
        )
        newNode.sortChildren()
        return newNode
    }

    fun keys(prefix: Series<C>? = null): List<Series<C>> {
        val currentPrefix = if (prefix != null && prefix.size > 0) {
            prefix.plus(key)
        } else {
            key
        }
        
        val result = mutableListOf<Series<C>>()
        if (term) {
            result.add(currentPrefix)
        }
        
        children?.forEach { child ->
            result.addAll(child.keys(currentPrefix))
        }
        
        return result
    }
}
