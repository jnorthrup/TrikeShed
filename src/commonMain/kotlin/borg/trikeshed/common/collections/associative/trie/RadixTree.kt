package borg.trikeshed.common.collections.associative.trie

import borg.trikeshed.lib.*

class RadixTree<C : Comparable<C>> {
    internal var root: Node<C>? = null

    internal class Node<C : Comparable<C>>(
        var key: Series<C>,
        var term: Boolean = false,
        var children: MutableList<Node<C>> = mutableListOf()
    ) {
        fun insert(s: Series<C>) {
            var commonLength = 0
            val minLength = minOf(key.size, s.size)

            while (commonLength < minLength && key[commonLength] == s[commonLength]) {
                commonLength++
            }

            when {
                // Complete match with existing node
                commonLength == key.size && commonLength == s.size -> {
                    term = true
                }

                // This node's key is a prefix of the new string
                commonLength == key.size -> {
                    val remaining = s.drop(commonLength) as Series<C>
                    val matchingChild = children.firstOrNull {
                        it.key.isNotEmpty() && it.key[0] == remaining[0]
                    }

                    if (matchingChild != null) {
                        matchingChild.insert(remaining)
                    } else {
                        children.add(Node(remaining, true))
                        children.sortBy { it.key[0] }
                    }
                }

                // Need to split this node
                else -> {
                    val commonPrefix = key.take(commonLength) as Series<C>
                    val oldSuffix = key.drop(commonLength) as Series<C>
                    val newSuffix = s.drop(commonLength) as Series<C>

                    val oldNode = Node(oldSuffix, term, children)
                    val newNode = Node(newSuffix, true)

                    key = commonPrefix
                    term = false
                    children.clear()
                    children.add(oldNode)
                    children.add(newNode)
                    children.sortBy { it.key[0] }
                }
            }
        }

        fun collectKeys(prefix: Series<C>, result: MutableList<Series<C>>) {
            val currentKey = prefix + this.key
            if (term) {
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