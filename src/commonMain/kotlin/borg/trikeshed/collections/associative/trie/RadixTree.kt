package borg.trikeshed.collections.associative.trie

import borg.trikeshed.lib.*
import borg.trikeshed.lib.commonPrefixWith
import borg.trikeshed.lib.drop
import borg.trikeshed.lib.get
import borg.trikeshed.lib.isEmpty
import borg.trikeshed.lib.isNotEmpty
import borg.trikeshed.lib.plus
import borg.trikeshed.lib.size
import borg.trikeshed.lib.take

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

            val commonPrefix = key.commonPrefixWith(s)
            commonLength = commonPrefix.size

            when {
                // Complete match with existing node
                commonLength == key.size && commonLength == s.size -> {
                    term = true
                }

                // This node's key is a prefix of the new string
                commonLength == key.size -> {
                    val remaining: Series<C> = s.drop(commonLength)
                    val matchingChild: Node<C>? = children.firstOrNull { it.key.isNotEmpty() && it.key[0] == remaining[0] }

                    if (matchingChild != null) {
                        matchingChild.insert(remaining)
                    } else {
                        children.add(Node(remaining, true))
                        children.sortBy { it.key[0] }
                    }
                }

                // Need to split this node
                else -> {
                    val oldSuffix = key.drop(commonLength)
                    val newSuffix = s.drop(commonLength)

                    val oldNode = Node(oldSuffix, term, children)
                    val newNode = Node(newSuffix, true)

                    key = key.take(commonLength)
                    term = false
                    children = mutableListOf(oldNode, newNode)
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
        root?.collectKeys(prefix = Join.emptySeriesOf<C>(), result = result)
        return result
    }
}
