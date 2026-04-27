package borg.trikeshed.miniduck.tablespace

import borg.trikeshed.miniduck.BlockRowVec

/**
 * BlockStore: the storage SPI for a tablespace region.
 *
 * Implementations:
 *   - InMemoryBlockStore  (testing, local dev)
 *   - ObjectStorageBlockStore (S3/GCS/Minio blob per block)
 *   - IsamBlockStore      (cursor ISAM — sequential block scan)
 *   - SqlBlockStore       (SQL table-backed block store)
 *
 * Keys are (collection, blockId) pairs. BlockId is assigned on put.
 * All blocks are sealed before put — the store never receives mutable blocks.
 */
interface BlockStore {

    /** Put a sealed block into [collection]. Returns the assigned blockId. */
    fun put(collection: String, block: BlockRowVec): String

    /** Get a block by [collection] and [blockId]. Returns null if not found. */
    fun get(collection: String, blockId: String): BlockRowVec?

    /** List all blockIds in [collection]. */
    fun list(collection: String): List<String>

    /** Remove a block. Returns true if it existed. */
    fun remove(collection: String, blockId: String): Boolean
}

/**
 * In-memory BlockStore for testing and local development.
 * Collections are just string-keyed maps of sealed blocks.
 */
class InMemoryBlockStore : BlockStore {

   val collections = mutableMapOf<String, MutableMap<String, BlockRowVec>>()
   var counter = 0L

    override fun put(collection: String, block: BlockRowVec): String {
        val id = "blk-${counter++}"
        collections.getOrPut(collection) { mutableMapOf() }[id] = block
        return id
    }

    /**
     * Put a block with a specific blockId — used by WAL replay to reconstruct
     * the exact blockId that was originally assigned.
     */
    fun putWithId(collection: String, blockId: String, block: BlockRowVec) {
        collections.getOrPut(collection) { mutableMapOf() }[blockId] = block
    }

    override fun get(collection: String, blockId: String): BlockRowVec? =
        collections[collection]?.get(blockId)

    override fun list(collection: String): List<String> =
        collections[collection]?.keys?.toList() ?: emptyList()

    override fun remove(collection: String, blockId: String): Boolean =
        collections[collection]?.remove(blockId) != null
}
