@file:Suppress("NonAsciiCharacters", "UNCHECKED_CAST", "NAME_SHADOWING")

package borg.trikeshed.memory

import borg.trikeshed.parse.confix.*
import borg.trikeshed.lib.*
import kotlinx.serialization.Serializable

/**
 * ConfixMemoryStore - A memory provider storage backend using ConfixBlockStore.
 *
 * This store uses Confix's facet system for durable, structured memory persistence.
 * It supports:
 * - Multiple collections (namespaces) for different memory types
 * - Block-based storage with NDJSON serialization via ConfixBlockCodec
 * - In-memory and persistent implementations via ConfixDocStore SPI
 * - Query via ConfixDocStore filtering + ConfixCell navigation
 *
 * Collections:
 *   - "facts": Explicit user facts/preferences (DocCell)
 *   - "context": Conversation context blocks (ViewCell)
 *   - "sessions": Session replay blocks (ViewCell)
 *   - "probes": Entity-related fact clusters (DocCell)
 */
class ConfixMemoryStore(
    private val blockStore: ConfixBlockStore = ConfixBlockStore(),
    private val collection: String = "facts",
) {

    companion object {
        val DEFAULT_COLLECTIONS = listOf("facts", "context", "sessions", "probes")
    }

    /**
     * Store a fact and return the assigned blockId.
     */
    fun putFact(
        content: String,
        category: String = "general",
        tags: List<String> = emptyList(),
        trustScore: Double = 0.5,
    ): String {
        return blockStore.put(
            ConfixBlock.mutable().apply {
                append(confixDocCell(
                    keys = listOf("id", "content", "category", "tags", "trustScore", "timestamp"),
                    cells = listOf(
                        java.util.UUID.randomUUID().toString(),
                        content,
                        category,
                        tags,
                        trustScore,
                        System.currentTimeMillis(),
                    ),
                ))
            }
        ).also { blockStore.get(it)?.seal() }
    }

    /**
     * Store a context block and return the blockId.
     */
    fun putContext(
        query: String,
        relevance: String,
        sourceTurns: List<String>,
    ): String {
        return blockStore.put(
            ConfixBlock.mutable().apply {
                append(confixViewCell(
                    id = java.util.UUID.randomUUID().toString(),
                    key = query,
                    value = relevance,
                ))
            }
        ).also { blockStore.get(it)?.seal() }
    }

    /**
     * Store a session block and return the blockId.
     */
    fun putSession(
        sessionId: String,
        messages: List<MessageRef>,
    ): String {
        val ops = ConfixBlockStoreOps(blockStore)
        return ops.storeSession(sessionId, messages)
    }

    /**
     * Store a probe (entity fact cluster) and return the blockId.
     */
    fun putProbe(
        entity: String,
        facts: List<String>,
        confidence: Double,
    ): String {
        return blockStore.put(
            ConfixBlock.mutable().apply {
                append(confixDocCell(
                    keys = listOf("id", "entity", "facts", "confidence", "timestamp"),
                    cells = listOf(
                        java.util.UUID.randomUUID().toString(),
                        entity,
                        facts,
                        confidence,
                        System.currentTimeMillis(),
                    ),
                ))
            }
        ).also { blockStore.get(it)?.seal() }
    }

    /** Retrieve a block by ID. */
    fun get(blockId: String): ConfixBlock? = blockStore.get(blockId)

    /** List all block IDs in the collection. */
    fun list(): List<String> = blockStore.list()

    /** Remove a block by ID. */
    fun remove(blockId: String): Boolean = blockStore.remove(blockId)

    /** Encode a block to NDJSON string for transport/export. */
    fun encodeBlock(block: ConfixBlock): String = ConfixBlockCodec.encode(block)

    /** Decode an NDJSON string back to a block. */
    fun decodeBlock(text: String): ConfixBlock = ConfixBlockCodec.decode(text)

    /** Export all blocks in the collection as NDJSON lines. */
    fun exportAll(): String = blockStore.exportAll()

    /** Import blocks from NDJSON text. Each line is a separate block. */
    fun importAll(text: String): List<String> = blockStore.importAll(text)

    /** Create a cursor over all blocks in the collection. */
    fun asCursor(): Series<ConfixBlock> = blockStore.asCursor()

    /** Get the underlying block store for advanced operations. */
    fun getBlockStore(): ConfixBlockStore = blockStore

    /** Get the collection name. */
    fun getCollection(): String = collection

    /** Create a new store instance for a different collection. */
    fun withCollection(newCollection: String): ConfixMemoryStore {
        return ConfixMemoryStore(blockStore.withCollection(newCollection), newCollection)
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
        val timestamp: Long = System.currentTimeMillis(),
    ) : MemoryEntry()

    @Serializable
    data class Context(
        val id: String,
        val query: String,
        val relevance: String,
        val sourceTurns: List<String>,
        val timestamp: Long = System.currentTimeMillis(),
    ) : MemoryEntry()

    @Serializable
    data class Session(
        val id: String,
        val sessionId: String,
        val messages: List<MessageRef>,
        val startTime: Long,
        val endTime: Long,
    ) : MemoryEntry()

    @Serializable
    data class MessageRef(
        val role: String,
        val content: String,
        val turnIndex: Int,
    )

    @Serializable
    data class Probe(
        val id: String,
        val entity: String,
        val facts: List<String>,
        val confidence: Double,
        val timestamp: Long = System.currentTimeMillis(),
    ) : MemoryEntry()
}

/**
 * High-level memory operations using Confix blocks.
 */
class ConfixMemoryOps(private val store: ConfixMemoryStore) {

    private val counter = java.util.concurrent.atomic.AtomicLong(0)

    private fun nextId(): String = "mem-${counter.incrementAndGet()}"

    /**
     * Add a fact to memory.
     */
    fun addFact(
        content: String,
        category: String = "general",
        tags: List<String> = emptyList(),
        trustScore: Double = 0.5,
    ): String {
        return store.putFact(content, category, tags, trustScore)
    }

    /**
     * Search facts by query string (simple contains match).
     */
    fun searchFacts(query: String, limit: Int = 10): List<MemoryEntry.Fact> {
        val results = mutableListOf<MemoryEntry.Fact>()
        val blocks = store.getBlockStore().asCursor()
        for (i in 0 until blocks.size) {
            val block = blocks[i]
            val json = ConfixBlockCodec.encode(block)
            if (json.contains(query, ignoreCase = true)) {
                // Extract fact from block
                val cells = block.cells
                for (cell in cells) {
                    val reified = cell.reify()
                    if (reified is Map<*, *>) {
                        val content = reified["content"] as? String
                        if (content != null && content.contains(query, ignoreCase = true)) {
                            results.add(MemoryEntry.Fact(
                                id = (reified["id"] as? String) ?: nextId(),
                                content = content,
                                category = (reified["category"] as? String) ?: "general",
                                tags = (reified["tags"] as? List<String>) ?: emptyList(),
                                trustScore = (reified["trustScore"] as? Number)?.toDouble() ?: 0.5,
                                timestamp = (reified["timestamp"] as? Number)?.toLong() ?: System.currentTimeMillis(),
                            ))
                        }
                    }
                    if (results.size >= limit) break
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
        messages: List<MemoryEntry.MessageRef>,
    ): String {
        return store.putSession(sessionId, messages)
    }

    /**
     * Add context to memory.
     */
    fun addContext(
        query: String,
        relevance: String,
        sourceTurns: List<String>,
    ): String {
        return store.putContext(query, relevance, sourceTurns)
    }

    /**
     * Add probe to memory.
     */
    fun addProbe(
        entity: String,
        facts: List<String>,
        confidence: Double,
    ): String {
        return store.putProbe(entity, facts, confidence)
    }
}

/**
 * ConfixMemoryModule - The main module class for Hermes integration.
 *
 * This class provides the Kotlin/JVM side of the Confix-Hermes memory bridge.
 * It can be used from:
 * - Kotlin/JVM code directly
 * - Kotlin/JS or Kotlin/WASM via activejs taxonomy
 * - Python via the memory provider plugin (separate)
 */
class ConfixMemoryModule(
    private val baseBlockStore: ConfixBlockStore = ConfixBlockStore(),
) {

    private val stores = mutableMapOf<String, ConfixMemoryStore>()

    init {
        for (coll in ConfixMemoryStore.DEFAULT_COLLECTIONS) {
            stores[coll] = ConfixMemoryStore(baseBlockStore.withCollection(coll), coll)
        }
    }

    fun getStore(collection: String = "facts"): ConfixMemoryStore =
        stores[collection] ?: ConfixMemoryStore(baseBlockStore.withCollection(collection), collection)

    fun getOps(collection: String = "facts"): ConfixMemoryOps =
        ConfixMemoryOps(getStore(collection))

    fun shutdown() {
        // Flush any pending writes if using persistent store
    }
}