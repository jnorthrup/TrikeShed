package borg.trikeshed.crdt

import borg.trikeshed.patch.Blake3Hash
import borg.trikeshed.pijul.*
// import borg.trikeshed.lib.Series
// Using basic types for isolated compilation check without entire KMP dependency graph
// For actual runtime, integration with lib.Series / lib.j is expected via KMP stdlib.

class PijulCrdt {
    private val dag = DependencyDag()

    data class VertexId(val patch: Blake3Hash, val offset: Int)

    private val root = VertexId(Blake3Hash(ByteArray(32)), 0)

    private val edges = mutableMapOf<VertexId, MutableList<VertexId>>()
    private val vertexContent = mutableMapOf<VertexId, String>()

    init {
        vertexContent[root] = ""
    }

    fun apply(patch: Patch) {
        dag.add(patch)

        for (change in patch.changes) {
            when (change) {
                is Change.Insert -> {
                    val newVertex = VertexId(patch.id, change.pos)
                    vertexContent[newVertex] = change.content

                    val attachPoint = findAttachPoint(change.pos)
                    val list = edges.getOrElse(newVertex) { mutableListOf() }
                    list.add(attachPoint)
                    edges[newVertex] = list
                }
                is Change.Delete -> {
                    val toDelete = findVerticesInRange(change.pos, change.length)
                    for (v in toDelete) {
                        vertexContent[v] = ""
                    }
                }
            }
        }
    }

    private fun findAttachPoint(pos: Int): VertexId {
        var currentPos = 0
        var lastVertex = root

        for (pair in topologicalSort().iterator()) {
            val v = pair.first
            val content = pair.second
            if (currentPos + content.length >= pos && v != root) {
                return v
            }
            currentPos += content.length
            lastVertex = v
        }
        return lastVertex
    }

    private fun findVerticesInRange(start: Int, length: Int): List<VertexId> {
        val result = mutableListOf<VertexId>()
        var currentPos = 0

        for (pair in topologicalSort().iterator()) {
            val v = pair.first
            val content = pair.second
            val vStart = currentPos
            val vEnd = currentPos + content.length

            if (vStart < start + length && vEnd > start && v != root) {
                result.add(v)
            }
            currentPos += content.length
        }
        return result
    }

    private fun topologicalSort(): Sequence<Pair<VertexId, String>> = sequence {
        val visited = mutableSetOf<VertexId>()
        val queue = mutableListOf(root)

        while (queue.isNotEmpty()) {
            val current = queue.removeAt(0)
            if (visited.add(current)) {
                yield(Pair(current, vertexContent[current] ?: ""))

                for ((v, deps) in edges.entries) {
                    if (current in deps && v !in visited) {
                        queue.add(v)
                    }
                }
            }
        }

        for ((v, content) in vertexContent.entries) {
            if (visited.add(v)) {
                yield(Pair(v, content))
            }
        }
    }

    fun render(): String {
        val sb = StringBuilder()
        for (pair in topologicalSort().iterator()) {
            sb.append(pair.second)
        }
        return sb.toString()
    }
}
