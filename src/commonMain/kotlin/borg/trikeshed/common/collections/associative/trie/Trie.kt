package borg.trikeshed.common.collections.associative.trie

import kotlin.collections.contains

/**
 * Created by kenny on 6/6/16.
 */
class Trie(var root: Map<String, Node> = linkedMapOf()) {
    private var freeze: Boolean = false
    fun add(v: Int, vararg values: String) {
        var children: Map<String, Node> = root

        values.forEachIndexed { i: Int, value: String ->
            val isLeaf = i == values.size - 1
            // add new node
            if (!children.contains(value)) {
                val node = Node(pathSeg = value, leaf = isLeaf, payload = v)
                (children as MutableMap)[value] = node
                children = node.children
            } else {
                // exist, so traverse current path + set isLeaf if needed
                val node: Node = children[value]!!

                if (isLeaf != node.leaf) {
                    node.leaf = isLeaf
                }
                children = node.children
            }
        }
    }

    fun contains(vararg values: String): Boolean = search(*values) != null

    operator fun get(vararg key: String): Int? = search(*key)?.payload

    fun frez(n: Node) {

        (n.children.entries).let { cnodes: Set<Map.Entry<String, Node>> ->
            n.children = ArrayMap.sorting(n.children)
            for ((_: String, v: Node) in cnodes) frez(v)
        }
    }

    fun freeze() {
        if (!freeze) {
            freeze = true
            root.values.forEach { frez(it) }
            root = ArrayMap.sorting(root)
        }
    }

    fun search(vararg segments: String): Node? {
        var children: Map<String, Node> = root// exist, so traverse current path, ending if is last value, and is leaf node
        // not at end, continue traversing
        // add new node
        if (children.isNotEmpty()) {
            for ((i: Int, value: String) in segments.withIndex()) {
                val atLeaf = i == segments.lastIndex
                // add new node
                if (children.contains(value)) {
                    // exist, so traverse current path, ending if is last value, and is leaf node
                    val node: Node = children[value]!!
                    if (atLeaf) return if (node.leaf) {
                        node
                    } else {
                        null
                    }
                    // not at end, continue traversing
                    children = node.children
                } else return null
            }
            throw IllegalStateException("Should not get here")
        } else return null
    }

    companion object {
        /**
         * Created by kenny on 6/6/16.
         */
        class Node(
            val pathSeg: String,
            var leaf: Boolean,
            val payload: Int,
            var children: Map<String, Node> = linkedMapOf()
        )
    }
}
