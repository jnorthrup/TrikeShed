package borg.trikeshed.patch

import borg.trikeshed.pijul.*

class ConfixPatchEmitter {
    private val patches = mutableListOf<Patch>()
    private var lastPatchHash: Blake3Hash? = null

    fun emitInsert(pos: Int, content: String) {
        val change = Change.Insert(pos, content)
        val dependencies = lastPatchHash?.let { listOf(Dependency(it)) } ?: emptyList()

        val hashContent = "Insert:\$pos:\$content".encodeToByteArray()
        val hash = Blake3Hash.hash(hashContent)

        val patch = Patch(hash, listOf(change), dependencies)
        patches.add(patch)
        lastPatchHash = hash
    }

    fun emitDelete(pos: Int, length: Int) {
        val change = Change.Delete(pos, length)
        val dependencies = lastPatchHash?.let { listOf(Dependency(it)) } ?: emptyList()

        val hashContent = "Delete:\$pos:\$length".encodeToByteArray()
        val hash = Blake3Hash.hash(hashContent)

        val patch = Patch(hash, listOf(change), dependencies)
        patches.add(patch)
        lastPatchHash = hash
    }

    fun getPatches(): List<Patch> = patches.toList()
}
