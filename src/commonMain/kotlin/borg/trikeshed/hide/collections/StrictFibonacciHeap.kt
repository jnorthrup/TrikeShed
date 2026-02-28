package borg.trikeshed.common.collections

import kotlin.math.ln

class StrictFibonacciHeap<T : Comparable<T>> {
    private var root: Node<T>? = null
    private var minNode: Node<T>? = null
    private var size = 0
    private val activeRoots = mutableListOf<Node<T>>()
    private val rankList = mutableListOf<MutableList<Node<T>>>()

    class Node<T : Comparable<T>>(
        var key: T,
        var value: Any? = null,
        var parent: Node<T>? = null,
        var child: Node<T>? = null,
        var left: Node<T>? = null,
        var right: Node<T>? = null,
        var rank: Int = 0,
        var loss: Int = 0,
        var isActive: Boolean = true
    )

    fun insert(key: T, value: Any? = null): Node<T> {
        val newNode = Node(key, value)
        if (root == null) {
            root = newNode
            minNode = newNode
        } else {
            link(newNode, root!!)
            if (newNode.key < minNode!!.key) {
                minNode = newNode
            }
        }
        size++
        activeRoots.add(newNode)
        maintainInvariants()
        return newNode
    }

    fun findMin(): T? = minNode?.key

    fun deleteMin(): T? {
        val min = minNode ?: return null
        if (min.child != null) {
            var child = min.child
            do {
                val next = child!!.right
                child.parent = null
                link(child, root!!)
                activeRoots.add(child)
                child = next
            } while (child != min.child)
        }

        if (min == root) {
            root = min.right
        }
        unlink(min)
        activeRoots.remove(min)
        size--

        if (size == 0) {
            minNode = null
            root = null
        } else {
            minNode = root
            consolidate()
        }

        maintainInvariants()
        return min.key
    }

    fun decreaseKey(node: Node<T>, newKey: T) {
        require(newKey < node.key) { "New key must be smaller than current key" }
        node.key = newKey
        if (node.parent != null && node.key < node.parent!!.key) {
            cut(node)
            cascadingCut(node.parent!!)
        }
        if (node.key < minNode!!.key) {
            minNode = node
        }
        maintainInvariants()
    }

    private fun link(child: Node<T>, parent: Node<T>) {
        child.parent = parent
        if (parent.child == null) {
            parent.child = child
            child.left = child
            child.right = child
        } else {
            child.left = parent.child!!.left
            child.right = parent.child
            parent.child!!.left!!.right = child
            parent.child!!.left = child
        }
        parent.rank++
    }

    private fun unlink(node: Node<T>) {
        if (node.right == node) {
            node.parent?.child = null
        } else {
            node.left!!.right = node.right
            node.right!!.left = node.left
            node.parent?.child = node.right
        }
        node.parent?.rank?.dec()
        node.parent = null
        node.left = null
        node.right = null
    }

    private fun cut(node: Node<T>) {
        unlink(node)
        link(node, root!!)
        node.loss = 0
        node.isActive = true
        activeRoots.add(node)
    }

    private fun cascadingCut(node: Node<T>) {
        val parent = node.parent
        if (parent != null) {
            if (node.isActive) {
                node.loss++
                if (node.loss > 1) {
                    cut(node)
                    cascadingCut(parent)
                }
            } else {
                node.isActive = true
                node.loss = 0
            }
        }
    }

    private fun consolidate() {
        val maxRank = (log2(size.toDouble()) + 1).toInt()
        val ranks = Array<Node<T>?>(maxRank) { null }

        var x = root
        val roots = mutableListOf<Node<T>>()
        while (x != null) {
            roots.add(x)
            x = x.right
            if (x == root) break
        }

        for (node1 in roots) {
            var node=node1
            var r = node.rank
            while (ranks[r] != null) {
                var y = ranks[r]!!
                if (node.key > y.key) {
                    val temp = node
                    node = y
                    y = temp
                }
                link(y, node)
                ranks[r] = null
                r++
            }
            ranks[r] = node
            if (node.key < minNode!!.key) {
                minNode = node
            }
        }

        root = minNode
    }

    private fun maintainInvariants() {
        while (activeRoots.size > 2 * log2(size.toDouble()) + 6) {
            val x = activeRoots.removeAt(activeRoots.lastIndex)
            val y = activeRoots.removeAt(activeRoots.lastIndex)
            if (x.key < y.key) {
                link(y, x)
                activeRoots.add(x)
            } else {
                link(x, y)
                activeRoots.add(y)
            }
        }
    }

    fun meld(other: StrictFibonacciHeap<T>) {
        if (other.root == null) return
        if (root == null) {
            root = other.root
            minNode = other.minNode
            size = other.size
            activeRoots.addAll(other.activeRoots)
        } else {
            val last = root!!.left!!
            val otherLast = other.root!!.left!!

            last.right = other.root
            other.root!!.left = last

            root!!.left = otherLast
            otherLast.right = root

            if (other.minNode!!.key < minNode!!.key) {
                minNode = other.minNode
            }

            size += other.size
            activeRoots.addAll(other.activeRoots)
        }
        maintainInvariants()
    }

    fun delete(node: Node<T>) {
        decreaseKey(node, minNode!!.key)
        deleteMin()
    }

    private fun log2(n: Double): Int = (ln(n) / ln(2.0)).toInt()
}