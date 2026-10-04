package borg.trikeshed.lcnc

/**
 * An upload's documents as a folder tree: ids split on `/`, each twin (extract, notes, footnotes) folded under the
 * document it was derived from, so removing a document visibly takes its twins with it.
 */
class AssetTree(ids: Iterable<String>) {
    class Node(val path: String, val name: String, val folder: Boolean) {
        val folders = LinkedHashMap<String, Node>()
        val documents = LinkedHashMap<String, Node>()
        /** The twins of this document, by id. */
        val twins = ArrayList<String>()
        /** Documents at or under this node. */
        var count = 0
    }

    val root = Node("", "", true)

    init {
        val sorted = ids.sorted()
        for (id in sorted) if (ProjectNodes.twinOf(id) == null) place(id)
        for (id in sorted) ProjectNodes.twinOf(id)?.let { base -> (find(base) ?: place(base)).twins.add(id) }
    }

    private fun place(id: String): Node {
        val parts = id.split('/')
        var at = root
        at.count++
        for (k in 0 until parts.size - 1) {
            val p = parts.subList(0, k + 1).joinToString("/")
            at = at.folders.getOrPut(parts[k]) { Node(p, parts[k], true) }
            at.count++
        }
        return at.documents.getOrPut(parts.last()) { Node(id, parts.last(), false) }
    }

    fun find(id: String): Node? {
        val parts = id.split('/')
        var at = root
        for (k in 0 until parts.size - 1) at = at.folders[parts[k]] ?: return null
        return at.documents[parts.last()]
    }
}
