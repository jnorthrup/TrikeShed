package trie

class Trie(var root: Map<String, Node> = linkedMapOf()) {
    private var freeze: Boolean = false
    fun add(v: Int, vararg values: String) {
        var children = root
        for ((i, value) in values.withIndex()) {
            val isLeaf = i == values.size - 1
            if (!children.contains(value)) {
                val node = Node(value, isLeaf, v)
                (children as MutableMap)[value] = node
                children = node.children
            } else {
                val node = children[value]!!
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
        (n.children.entries).let { cnodes ->
            n.children = ArrayMap.sorting(n.children)
            for ((_, v) in cnodes) frez(v)
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
        var children = root
        if (children.isNotEmpty()) {
            for ((i, value) in segments.withIndex()) {
                val atLeaf = i == segments.lastIndex
                if (children.contains(value)) {
                    val node = children[value]!!
                    if (atLeaf) return if (node.leaf) {
                        node
                    } else {
                        null
                    }
                    children = node.children
                } else return null
            }
            throw IllegalStateException("Should not get here")
        } else return null
    }
}

