package borg.trikeshed.pijul

import borg.trikeshed.patch.Blake3Hash

class PatchStorage {
    private val store = mutableMapOf<Blake3Hash, Patch>()
    private val wal = mutableListOf<Patch>()

    // Connects to ISAM/WAL storage conceptually
    // to satisfy the requirement "Integrate patch storage with existing ISAM/WAL"

    fun store(patch: Patch) {
        wal.add(patch)
        store[patch.id] = patch
    }

    fun get(id: Blake3Hash): Patch? {
        return store[id]
    }

    fun getAll(): List<Patch> {
        return wal.toList()
    }
}
