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
        if (other.isEmpty()) {
            term = true
            return this
        }
        
        val commonPrefixLength = key.commonPrefixWith(other).size
        
        return when {
            commonPrefixLength == key.size -> {
                val remainingKey = other.drop(commonPrefixLength)
                if (remainingKey.isEmpty()) {
                    term = true
                    this
                } else {
                    if (children == null) {
                        children = arrayOf(RadixTreeNode(remainingKey, true))
                    } else {
                        val firstChar = remainingKey.first()
                        val index = children!!.binarySearch { 
                            it.key.first().compareTo(firstChar) 
                        }
                        
                        if (index >= 0) {
                            children!![index] = children!![index] + remainingKey
                        } else {
                            val insertIndex = -index - 1
                            children = children!!.toMutableList().apply {
                                add(insertIndex, RadixTreeNode(remainingKey, true))
                            }.toTypedArray()
                        }
                    }
                    this
                }
            }
            
            commonPrefixLength < key.size -> {
                val commonPrefix = key.take(commonPrefixLength)
                val remainingCurrentKey = key.drop(commonPrefixLength)
                val remainingOtherKey = other.drop(commonPrefixLength)
                
                val newInternalNode = RadixTreeNode(commonPrefix)
                val currentNode = RadixTreeNode(remainingCurrentKey, term, children)
                val newNode = RadixTreeNode(remainingOtherKey, true)
                
                newInternalNode.children = arrayOf(
                    if (remainingCurrentKey.first() <= remainingOtherKey.first()) 
                        currentNode else newNode,
                    if (remainingCurrentKey.first() <= remainingOtherKey.first()) 
                        newNode else currentNode
                )
                
                newInternalNode
            }
            
            else -> {
                val newRoot = RadixTreeNode(other, true)
                newRoot.children = arrayOf(
                    RadixTreeNode(key.drop(commonPrefixLength), term, children)
                )
                newRoot
            }
        }
    }

    fun keys(prefix: Series<C>? = null): List<Series<C>> {
        val currentPrefix = if (prefix == null || prefix.isEmpty()) key 
                           else prefix.plus(key)
        return buildList {
            if (term) add(currentPrefix)
            children?.forEach { child ->
                addAll(child.keys(currentPrefix))
            }
        }
    }
}
