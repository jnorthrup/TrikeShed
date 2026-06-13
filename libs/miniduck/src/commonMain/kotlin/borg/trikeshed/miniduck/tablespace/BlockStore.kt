package borg.trikeshed.miniduck.tablespace

import borg.trikeshed.miniduck.BlockRowVec

interface BlockStore {
    fun put(collection: String, block: BlockRowVec): String
    fun get(collection: String, blockId: String): BlockRowVec?
    fun remove(collection: String, blockId: String): Boolean
    fun list(collection: String): List<BlockRowVec>
}

class InMemoryBlockStore : BlockStore {
    private val blocks = mutableMapOf<String, MutableMap<String, BlockRowVec>>()
    private var nextId = 0L

    override fun put(collection: String, block: BlockRowVec): String {
        val id = "blk-${nextId++}"
        putWithId(collection, id, block)
        return id
    }

    fun putWithId(collection: String, blockId: String, block: BlockRowVec) {
        blocks.getOrPut(collection) { linkedMapOf() }[blockId] = block.seal()
    }

    override fun get(collection: String, blockId: String): BlockRowVec? = blocks[collection]?.get(blockId)

    override fun remove(collection: String, blockId: String): Boolean = blocks[collection]?.remove(blockId) != null

    override fun list(collection: String): List<BlockRowVec> = blocks[collection]?.values?.toList() ?: emptyList()
}
