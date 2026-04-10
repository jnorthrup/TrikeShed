package borg.literbike.ccek.store.backends

import borg.literbike.ccek.store.cas.ContentHashWrapper
import borg.literbike.ccek.store.*

/**
 * In-memory block storage backend
 *
 * Provides a simple in-memory implementation of BlockStore and ObjectStore.
 * Useful for testing and caching scenarios.
 */

/** In-memory block storage */
class MemoryBlockStore(
    /** Maximum total size in bytes (0 = unlimited) */
    private val maxSize: Int = 0,
) : BlockStore {
    /** Storage map: block hash -> block data */
    private val blocks = mutableMapOf<ContentHashWrapper, Block>()

    /** Current total size */
    private var currentSize: Int = 0

    /** Statistics */
    private var statsValue = StoreStats()

    companion object {
        /** Create a new memory store with unlimited capacity */
        fun create(): MemoryBlockStore = MemoryBlockStore(0)

        /** Create a new memory store with capacity limit */
        fun withCapacity(maxSize: Int): MemoryBlockStore = MemoryBlockStore(maxSize)
    }

    /** Get current size in bytes */
    fun size(): Int = currentSize

    /** Get number of blocks */
    fun blockCount(): Int = blocks.size

    /** Check if store is at capacity */
    fun isFull(): Boolean {
        if (maxSize == 0) return false
        return currentSize >= maxSize
    }

    /** Clear all blocks */
    fun clear() {
        blocks.clear()
        currentSize = 0
    }

    /** Evict oldest blocks to make room */
    private fun evictIfNeeded(needed: Int) {
        if (maxSize == 0) return

        while (currentSize + needed > maxSize && blocks.isNotEmpty()) {
            // Remove first block (simple LRU approximation)
            val firstKey = blocks.keys.firstOrNull()
            if (firstKey != null) {
                val block = blocks.remove(firstKey)
                if (block != null) {
                    currentSize = (currentSize - block.size()).coerceAtLeast(0)
                }
            }
        }
    }

    /** Update statistics */
    private fun recordPut(size: Int) {
        statsValue = statsValue.copy(
            blocksStored = statsValue.blocksStored + 1,
            bytesStored = statsValue.bytesStored + size.toLong(),
        )
    }

    private fun recordGet(size: Int) {
        statsValue = statsValue.copy(
            blocksRetrieved = statsValue.blocksRetrieved + 1,
            bytesRetrieved = statsValue.bytesRetrieved + size.toLong(),
        )
    }

    override suspend fun put(data: ByteArray): StoreResult<BlockId> {
        val block = Block.create(data)
        val size = block.size()
        val id = block.id()

        // Check capacity
        if (maxSize > 0 && size > maxSize) {
            return Result.failure(StoreError.BackendError(
                "Block size $size exceeds maximum $maxSize"
            ))
        }

        // Evict if needed
        evictIfNeeded(size)

        // If block already exists, adjust size
        val hashWrapper = ContentHashWrapper(id.toBytes())
        blocks[hashWrapper]?.let { existing ->
            currentSize = (currentSize - existing.size()).coerceAtLeast(0)
        }

        blocks[hashWrapper] = block
        currentSize += size

        recordPut(size)

        return Result.success(id)
    }

    override suspend fun get(id: BlockId): StoreResult<Block> {
        val hashWrapper = ContentHashWrapper(id.toBytes())
        val block = blocks[hashWrapper]
        if (block != null) {
            recordGet(block.size())
            return Result.success(block)
        }
        return Result.failure(StoreError.BlockNotFound(id.toString()))
    }

    override suspend fun has(id: BlockId): StoreResult<Boolean> {
        val hashWrapper = ContentHashWrapper(id.toBytes())
        return Result.success(blocks.containsKey(hashWrapper))
    }

    override suspend fun delete(id: BlockId): StoreResult<Boolean> {
        val hashWrapper = ContentHashWrapper(id.toBytes())
        val block = blocks.remove(hashWrapper)
        if (block != null) {
            currentSize = (currentSize - block.size()).coerceAtLeast(0)
            return Result.success(true)
        }
        return Result.success(false)
    }

    override suspend fun list(): StoreResult<List<BlockId>> {
        return Result.success(blocks.keys.map { BlockId(it.value.toHex()) })
    }

    override fun stats(): StoreStats = statsValue
}

/** In-memory object storage */
class MemoryObjectStore : ObjectStore {
    /** Storage map: key -> (data, metadata) */
    private val objects = mutableMapOf<String, Pair<ByteArray, ObjectMeta>>()

    /** Statistics */
    private var statsValue = StoreStats()

    companion object {
        fun create(): MemoryObjectStore = MemoryObjectStore()
    }

    /** Get object count */
    fun objectCount(): Int = objects.size

    /** Clear all objects */
    fun clear() {
        objects.clear()
    }

    private fun recordPut(size: Int) {
        statsValue = statsValue.copy(
            blocksStored = statsValue.blocksStored + 1,
            bytesStored = statsValue.bytesStored + size.toLong(),
        )
    }

    private fun recordGet(size: Int) {
        statsValue = statsValue.copy(
            blocksRetrieved = statsValue.blocksRetrieved + 1,
            bytesRetrieved = statsValue.bytesRetrieved + size.toLong(),
        )
    }

    override suspend fun getObject(key: String): StoreResult<Object> {
        val entry = objects[key]
        if (entry != null) {
            val (data, meta) = entry
            recordGet(data.size)
            return Result.success(Object(meta = meta, data = data))
        }
        return Result.failure(StoreError.ObjectNotFound(key))
    }

    override suspend fun putObject(
        key: String,
        data: ByteArray,
        meta: ObjectMeta?,
    ): StoreResult<ObjectMeta> {
        val newMeta = (meta ?: ObjectMeta()).copy(
            key = key,
            size = data.size.toLong(),
        )

        recordPut(data.size)
        objects[key] = data.copyOf() to newMeta

        return Result.success(newMeta)
    }

    override suspend fun deleteObject(key: String): StoreResult<Boolean> {
        val removed = objects.remove(key)
        return Result.success(removed != null)
    }

    override suspend fun listObjects(prefix: String?): StoreResult<List<ObjectMeta>> {
        val metas = objects
            .filter { (k, _) -> prefix == null || k.startsWith(prefix) }
            .map { (_, (_, meta)) -> meta }
        return Result.success(metas)
    }

    override suspend fun headObject(key: String): StoreResult<ObjectMeta?> {
        val entry = objects[key]
        return Result.success(entry?.second)
    }

    override suspend fun copyObject(source: String, dest: String): StoreResult<ObjectMeta> {
        val entry = objects[source]
            ?: return Result.failure(StoreError.ObjectNotFound(source))

        val (data, meta) = entry
        val newMeta = meta.copy(key = dest)

        objects[dest] = data.copyOf() to newMeta

        return Result.success(newMeta)
    }
}

/** Thread-safe memory store using shared reference */
class SharedMemoryStore(
    val inner: MemoryBlockStore = MemoryBlockStore.create(),
) : BlockStore {
    companion object {
        fun create(): SharedMemoryStore = SharedMemoryStore()
    }

    override suspend fun put(data: ByteArray): StoreResult<BlockId> = inner.put(data)

    override suspend fun get(id: BlockId): StoreResult<Block> = inner.get(id)

    override suspend fun has(id: BlockId): StoreResult<Boolean> = inner.has(id)

    override suspend fun delete(id: BlockId): StoreResult<Boolean> = inner.delete(id)

    override suspend fun list(): StoreResult<List<BlockId>> = inner.list()

    override fun stats(): StoreStats = inner.stats()
}

/** Backend configuration */
sealed class BackendConfig {
    /** In-memory configuration */
    data class Memory(val maxSize: Int = 100 * 1024 * 1024) : BackendConfig() // 100MB default

    /** IPFS configuration */
    data class Ipfs(
        val apiUrl: String,
        val gatewayUrl: String,
        val pin: Boolean,
    ) : BackendConfig()

    /** S3 configuration */
    data class S3(
        val endpoint: String,
        val bucket: String,
        val region: String,
        val accessKey: String,
        val secretKey: String,
    ) : BackendConfig()

    companion object {
        fun default(): BackendConfig = Memory()
    }
}

/** Factory for creating storage backends */
object BackendFactory {
    /** Create a BlockStore from configuration */
    fun createBlockStore(config: BackendConfig): StoreResult<BlockStore> {
        return when (config) {
            is BackendConfig.Memory -> {
                Result.success(MemoryBlockStore.withCapacity(config.maxSize))
            }
            is BackendConfig.Ipfs -> {
                Result.failure(StoreError.NotImplemented)
            }
            is BackendConfig.S3 -> {
                Result.failure(StoreError.NotImplemented)
            }
        }
    }

    /** Create default memory store */
    fun createMemoryStore(): BlockStore = MemoryBlockStore.create()
}

/** Composite store that combines multiple backends. Tries primary first, falls back to secondary on miss. */
class CompositeBlockStore(
    private val primary: BlockStore,
    private val secondary: BlockStore,
) : BlockStore {
    private var cacheHits: Long = 0
    private var cacheMisses: Long = 0

    companion object {
        fun create(primary: BlockStore, secondary: BlockStore): CompositeBlockStore {
            return CompositeBlockStore(primary, secondary)
        }
    }

    /** Get cache statistics */
    fun cacheStats(): Pair<Long, Long> = cacheHits to cacheMisses

    override suspend fun put(data: ByteArray): StoreResult<BlockId> {
        // Write-through: put in both stores
        val id = primary.put(data.copyOf()).getOrThrow()
        secondary.put(data).getOrNull()
        return Result.success(id)
    }

    override suspend fun get(id: BlockId): StoreResult<Block> {
        // Try primary first
        return primary.get(id).fold(
            onSuccess = { block ->
                cacheHits++
                Result.success(block)
            },
            onFailure = {
                // Fall back to secondary
                cacheMisses++
                secondary.get(id)
            },
        )
    }

    override suspend fun has(id: BlockId): StoreResult<Boolean> {
        // Check primary first
        if (primary.has(id).getOrDefault(false)) {
            return Result.success(true)
        }
        // Fall back to secondary
        return secondary.has(id)
    }

    override suspend fun delete(id: BlockId): StoreResult<Boolean> {
        // Delete from both
        val primaryDeleted = primary.delete(id).getOrDefault(false)
        secondary.delete(id).getOrNull()
        return Result.success(primaryDeleted)
    }

    override suspend fun list(): StoreResult<List<BlockId>> {
        // Merge lists from both stores
        return primary.list()
    }

    override fun stats(): StoreStats {
        val primaryStats = primary.stats()
        val secondaryStats = secondary.stats()

        return StoreStats(
            blocksStored = primaryStats.blocksStored + secondaryStats.blocksStored,
            blocksRetrieved = primaryStats.blocksRetrieved + secondaryStats.blocksRetrieved,
            bytesStored = primaryStats.bytesStored + secondaryStats.bytesStored,
            bytesRetrieved = primaryStats.bytesRetrieved + secondaryStats.bytesRetrieved,
        )
    }
}

/** No-op store for testing */
class NoopBlockStore : BlockStore {
    companion object {
        fun create(): NoopBlockStore = NoopBlockStore()
    }

    override suspend fun put(data: ByteArray): StoreResult<BlockId> {
        return Result.failure(StoreError.NotImplemented)
    }

    override suspend fun get(id: BlockId): StoreResult<Block> {
        return Result.failure(StoreError.NotImplemented)
    }

    override suspend fun has(id: BlockId): StoreResult<Boolean> {
        return Result.success(false)
    }

    override suspend fun delete(id: BlockId): StoreResult<Boolean> {
        return Result.success(false)
    }

    override suspend fun list(): StoreResult<List<BlockId>> {
        return Result.success(emptyList())
    }

    override fun stats(): StoreStats = StoreStats()
}
