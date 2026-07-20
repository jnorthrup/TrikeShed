package borg.trikeshed.pijul

import borg.trikeshed.patch.Blake3Hash

sealed class Change {
    data class Insert(val pos: Int, val content: String) : Change()
    data class Delete(val pos: Int, val length: Int) : Change()
}

data class Dependency(val id: Blake3Hash)

data class Patch(
    val id: Blake3Hash,
    val changes: List<Change>,
    val dependencies: List<Dependency>
)

enum class EdgeFlag {
    INSERTED, DELETED, ALIVE
}

data class Edge(
    val source: Blake3Hash,
    val target: Blake3Hash,
    val flag: EdgeFlag
)

class DependencyDag {
    private val patches = mutableMapOf<Blake3Hash, Patch>()

    fun add(patch: Patch) {
        patches[patch.id] = patch
    }

    fun contains(id: Blake3Hash): Boolean {
        return patches.containsKey(id)
    }

    fun getDependencies(id: Blake3Hash): List<Dependency> {
        return patches[id]?.dependencies ?: emptyList()
    }
}
