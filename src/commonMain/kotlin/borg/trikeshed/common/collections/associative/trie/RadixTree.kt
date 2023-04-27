package borg.trikeshed.common.collections.associative.trie

import borg.trikeshed.lib.*


/**
 * a prefix tree (trie) implementation
 */
class RadixTree<C : Comparable<C>>(var root: RadixTreeNode<C>? = null) {
    operator fun plus(key: Series<C>): RadixTreeNode<C> {
        root = root?.plus(key) ?: RadixTreeNode(key, true)
        return root!!
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
    operator fun plus(other: Series<C>): RadixTreeNode<C> {
        // Find the common prefix length between the current node's key and the other key
        val commonPrefixLength = key.commonPrefixWith(other).size

        // If the common prefix length is equal to the current node's key length,
        // it means that we need to insert the remaining part of the other key into the children of the current node
        if (commonPrefixLength == key.size) {
            val remainingKey = other.drop(commonPrefixLength)

            // If there is no remaining part, it means that the other key is equal to the current node's key,
            // so we just need to mark the current node as a terminal node
            if (remainingKey.isEmpty()) {
                term = true
                return this
            }

            // If the current node has children, we try to find a child with a matching prefix for the remaining key
            if (children != null) for (child in children!!)
                if (child.key.first() == remainingKey.first())
                    return child + remainingKey
                else children = mutableListOf()

            // If there is no matching child, we create a new child node with the remaining key and add it to the children
            val newNode = RadixTreeNode(remainingKey, true)
            children!!.add(newNode)
            return this
        }

        // If the common prefix length is less than the current node's key length,
        // it means that we need to split the current node into a new internal node and two child nodes
        if (commonPrefixLength < key.size) {
            val commonPrefix = key.take(commonPrefixLength)
            val remainingCurrentKey = key.drop(commonPrefixLength)
            val remainingOtherKey = other.drop(commonPrefixLength)

            // Create the new internal node with the common prefix
            val newInternalNode = RadixTreeNode(commonPrefix)

            // Create a new child node for the remaining part of the current node's key
            val newChildNode = RadixTreeNode(remainingCurrentKey, term, children)

            // Create a new child node for the remaining part of the other key
            val newOtherNode = RadixTreeNode(remainingOtherKey, true)

            // Set the new internal node's children
            newInternalNode.children = mutableListOf(newChildNode, newOtherNode)

            return newInternalNode
        }

        // If the common prefix length is 0, it means that there is no common prefix, so we cannot insert the other key
        throw IllegalArgumentException("Cannot insert a key with no common prefix.")
    }

}