package borg.trikeshed.miniduck.memory

import borg.trikeshed.miniduck.BlockRowVec
import borg.trikeshed.miniduck.MiniDuckBlockCodec
import borg.trikeshed.miniduck.tablespace.BlockStore
import borg.trikeshed.miniduck.tablespace.InMemoryBlockStore
import borg.trikeshed.lib.MutableSeries
import borg.trikeshed.lib.liveSeries
import borg.trikeshed.cursor.ColumnMeta
import borg.trikeshed.cursor.RowVec
import kotlinx.serialization.Serializable

/**
 * MiniduckMemoryStore - A memory provider storage backend using miniduck's BlockStore.
 *
 * This store uses miniduck's BlockRowVec format for durable, structured memory persistence.
 * It supports:
 * - Multiple collections (namespaces) for different memory types
 * - Block-based storage with NDJSON serialization
 * - In-memory and persistent implementations via BlockStore SPI
 * - Query via miniduck's cursor/series abstractions
 *
 * Collections:
 *   - "facts": Explicit user facts/preferences (DocRowVec)
 *   - "context": Conversation context blocks (BlockRowVec)
 *   - "sessions": Session replay blocks (BlockRowVec)
 *   - "probes": Entity-related fact clusters (DocRowVec)
 */
class MiniduckMemoryStore(
    private val blockStore: BlockStore = InMemoryBlockStore(),
    private val collection: String = "facts"
) {

    companion object {
        val DEFAULT_COLLECTIONS = listOf("facts", "context", "sessions", "probes")
    }

    private val codec = MiniDuckBlockCodec

    /**
     * Store a memory block (sealed BlockRowVec) and return the assigned blockId.
     */
    fun put(block: BlockRowVec): String = blockStore.put(collection, block)

    /**
     * Retrieve a block by ID.
     */
    fun get(blockId: String): BlockRowVec? = blockStore.get(collection, blockId)

    /**
     * List all block IDs in the collection.
     */
    fun list(): List<String> = blockStore.list(collection)

    /**
     * Remove a block by ID.
     */
    fun remove(blockId: String): Boolean = blockStore.remove(collection, blockId)

    /**
     * Encode a block to NDJSON string for transport/export.
     */
    fun encodeBlock(block: BlockRowVec): String = codec.encode(block)

    /**
     * Decode an NDJSON string back to a block.
     */
    fun decodeBlock(text: String): BlockRowVec = codec.decode(text)

    /**
     * Export all blocks in the collection as NDJSON lines.
     */
    fun exportAll(): String {
        return list().map { id ->
            get(id)?.let { codec.encode(it) } ?: ""
        }.filter { it.isNotBlank() }.joinToString("\n")
    }

    /**
     * Import blocks from NDJSON text.
     * Each line is a separate block.
     */
    fun importAll(text: String): List<String> {
        return text.lineSequence()
            .filter { it.isNotBlank() }
            .map { line ->
                val block = codec.decode(line)
                put(block)
            }
            .toList()
    }

    /**
     * Create a cursor over all blocks in the collection.
     * Uses miniduck's series/cursor abstractions.
     */
    fun asCursor(): MutableSeries<BlockRowVec> {
        val ids = list()
        return liveSeries { index ->
            val id = ids[index]
            get(id) ?: BlockRowVec.mutable().seal()
        }
    }

    /**
     * Get the underlying BlockStore for advanced operations.
     */
    fun getBlockStore(): BlockStore = blockStore

    /**
     * Get the collection name.
     */
    fun getCollection(): String = collection

    /**
     * Create a new store instance for a different collection.
     */
    fun withCollection(newCollection: String): MiniduckMemoryStore {
        return MiniduckMemoryStore(blockStore, newCollection)
    }
}

/**
 * Memory entry types for structured storage.
 */
@Serializable
sealed class MemoryEntry {
    @Serializable
    data class Fact(
        val id: String,
        val content: String,
        val category: String = "general",
        val tags: List<String> = emptyList(),
        val trustScore: Double = 0.5,
        val timestamp: Long = System.currentTimeMillis()
    ) : MemoryEntry()

    @Serializable
    data class Context(
        val id: String,
        val query: String,
        val relevance: String,
        val sourceTurns: List<String>,
        val timestamp: Long = System.currentTimeMillis()
    ) : MemoryEntry()

    @Serializable
    data class Session(
        val id: String,
        val sessionId: String,
        val messages: List<MessageRef>,
        val startTime: Long,
        val endTime: Long
    ) : MemoryEntry()

    @Serializable
    data class MessageRef(
        val role: String,
        val content: String,
        val turnIndex: Int
    )

    @Serializable
    data class Probe(
        val id: String,
        val entity: String,
        val facts: List<String>,
        val confidence: Double,
        val timestamp: Long = System.currentTimeMillis()
    ) : MemoryEntry()
}

/**
 * High-level memory operations using miniduck blocks.
 */
class MiniduckMemoryOps(private val store: MiniduckMemoryStore) {

    private val counter = java.util.concurrent.atomic.AtomicLong(0)

    private fun nextId(): String = "mem-${counter.incrementAndGet()}"

    /**
     * Add a fact to memory.
     * Creates a DocRowVec block with the fact content.
     */
    fun addFact(
        content: String,
        category: String = "general",
        tags: List<String> = emptyList(),
        trustScore: Double = 0.5
    ): String {
        // For now, encode as a simple DocRowVec-like structure
        // In a full implementation, this would create actual MiniRowVec types
        val block = BlockRowVec.mutable().apply {
            // Add metadata as first row
            append(createMemoryRow(MemoryEntry.Fact(
                id = nextId(),
                content = content,
                category = category,
                tags = tags,
                trustScore = trustScore
            )))
        }.seal()
        return store.put(block)
    }

    /**
     * Search facts by query string (simple contains match).
     */
    fun searchFacts(query: String, limit: Int = 10): List<MemoryEntry.Fact> {
        val results = mutableListOf<MemoryEntry.Fact>()
        for (id in store.list()) {
            val block = store.get(id)
            if (block != null) {
                // Decode and search - simplified implementation
                val json = store.encodeBlock(block)
                if (json.contains(query, ignoreCase = true)) {
                    // Parse and extract facts - simplified
                    // Full implementation would decode proper MiniRowVec
                }
            }
            if (results.size >= limit) break
        }
        return results
    }

    /**
     * Get all facts for an entity (probe operation).
     */
    fun probeEntity(entity: String): List<MemoryEntry.Fact> {
        return searchFacts(entity)
    }

    /**
     * Store session context.
     */
    fun storeSession(
        sessionId: String,
        messages: List<MemoryEntry.MessageRef>
    ): String {
        val block = BlockRowVec.mutable().apply {
            append(createMemoryRow(MemoryEntry.Session(
                id = nextId(),
                sessionId = sessionId,
                messages = messages,
                startTime = messages.firstOrNull()?.turnIndex?.toLong() ?: System.currentTimeMillis(),
                endTime = messages.lastOrNull()?.turnIndex?.toLong() ?: System.currentTimeMillis()
            )))
        }.seal()
        return store.put(block)
    }

    /**
     * Create a memory row from an entry.
     * In a full implementation, this would create proper MiniRowVec instances.
     */
    private fun createMemoryRow(entry: MemoryEntry): Any {
        // Placeholder - actual implementation would create DocRowVec/JsonRowVec
        return entry
    }
}

/**
 * MiniduckMemoryModule - The main module class for Hermes integration.
 *
 * This class provides the Kotlin/JVM side of the miniduck-Hermes memory bridge.
 * It can be used from:
 * - Kotlin/JVM code directly
 * - Kotlin/JS or Kotlin/WASM via activejs taxonomy
 * - Python via the memory provider plugin (separate)
 */
class MiniduckMemoryModule(
    private val baseStore: BlockStore = InMemoryBlockStore()
) {

    private val stores = mutableMapOf<String, MiniduckMemoryStore>()

    init {
        for (coll in MiniduckMemoryStore.DEFAULT_COLLECTIONS) {
            stores[coll] = MiniduckMemoryStore(baseStore, coll)
        }
    }

    fun getStore(collection: String = "facts"): MiniduckMemoryStore =
        stores[collection] ?: MiniduckMemoryStore(baseStore, collection)

    fun getOps(collection: String = "facts"): MiniduckMemoryOps =
        MiniduckMemoryOps(getStore(collection))

    fun shutdown() {
        // Flush any pending writes if using persistent store
    }
}