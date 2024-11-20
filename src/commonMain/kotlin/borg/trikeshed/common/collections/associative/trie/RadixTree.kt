package borg.trikeshed.common.collections.associative.trie

import borg.trikeshed.lib.*

class RadixTree<C : Comparable<C>> {
    private var root: Node<C>? = null

    private class Node<C : Comparable<C>>(
        var prefix: Series<C>,
        var isTerminal: Boolean = false,
        val children: MutableList<Node<C>> = mutableListOf()
    ) {
        fun insert(s: Series<C>) {
            var commonLength = 0
            val minLength = minOf(prefix.size, s.size)
            
            while (commonLength < minLength && prefix[commonLength] == s[commonLength]) {
                commonLength++
            }

            when {
                // Complete match with existing node
                commonLength == prefix.size && commonLength == s.size -> {
                    isTerminal = true
                }
                
                // This node's prefix is a prefix of the new string
                commonLength == prefix.size -> {
                    val remaining = s.drop(commonLength) as Series<C>
                    val matchingChild = children.firstOrNull { 
                        it.prefix.isNotEmpty() && it.prefix[0] == remaining[0] 
                    }
                    
                    if (matchingChild != null) {
                        matchingChild.insert(remaining)
                    } else {
                        children.add(Node(remaining, true))
                        children.sortBy { it.prefix[0] }
                    }
                }
                
                // Need to split this node
                else -> {
                    val commonPrefix = prefix.take(commonLength) as Series<C>
                    val oldSuffix = prefix.drop(commonLength) as Series<C>
                    val newSuffix = s.drop(commonLength) as Series<C>
                    
                    val oldNode = Node(oldSuffix, isTerminal, children)
                    val newNode = Node(newSuffix, true)
                    
                    prefix = commonPrefix
                    isTerminal = false
                    children.clear()
                    children.add(oldNode)
                    children.add(newNode)
                    children.sortBy { it.prefix[0] }
                }
            }
        }

        fun collectKeys(prefix: Series<C>, result: MutableList<Series<C>>) {
            val currentKey = prefix + this.prefix
            if (isTerminal) {
                result.add(currentKey)
            }
            for (child in children) {
                child.collectKeys(currentKey, result)
            }
        }
    }

    operator fun plus(s: Series<C>): RadixTree<C> {
        if (s.isEmpty()) return this
        
        if (root == null) {
            root = Node(s, true)
        } else {
            root!!.insert(s)
        }
        return this
    }

    fun keys(): List<Series<C>> {
        val result = mutableListOf<Series<C>>()
        root?.collectKeys(emptySeries(), result)
        return result
    }
}
