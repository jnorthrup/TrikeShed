package borg.trikeshed.ipfs

import borg.trikeshed.couch.runtime.CouchElement
import borg.trikeshed.miniduck.tablespace.BlockStore
import borg.trikeshed.miniduck.tablespace.IpfsBlockStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineContext
import kotlinx.coroutines.launch
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * IPFS Gateway Element — CCEK Pattern A element providing a KV domain store backed by IPFS.
 *
 * This is the central integration point for Couch ↔ IPFS replication:
 * - Exposes `BlockStore` SPI backed by IPFS content-addressed storage
 * - Provides DHT operations for provider announcement and content discovery
 * - Shares the reactor's io_uring ring via ChannelOperations CCEK
 * - Cross-context access: can read CouchElement collections, CouchElement can read IPFS blocks
 *
 * PRELOAD.md contract:
 * - Pattern A: companion object Key : AsyncContextKey<Self>()
 * - Lifecycle: CREATED → OPEN → ACTIVE → DRAINING → CLOSED
 * - Fanout: structured concurrency via coroutineScope
 * - Cold Series α-projection for all queries
 */
open class IpfsGatewayElement(
    private val blockStore: BlockStore,
    private val dhtService: DhtService,
    private val udpTransport: UdpDhtTransport?,
    override val realm: String = "default",
) : AbstractCoroutineContextElement(Key), IpfsElement(blockStore, dhtService) {

    companion object Key : kotlinx.coroutines.CoroutineContext.Key<IpfsGatewayElement>

    // ═══════════════════════════════════════════════════════════════
    // Lifecycle (Pattern A CCEK)
    // ═══════════════════════════════════════════════════════════════

    enum class State { CREATED, OPEN, ACTIVE, DRAINING, CLOSED }

    @Volatile
    var state: State = State.CREATED

    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(supervisor + coroutineContext)
    private val cqeChannel = Channel<ChannelResult>(100) // CQE completion channel

    override val key: CoroutineContext.Key<*> get() = Key

    fun open() {
        require(state == State.CREATED) { "open() requires CREATED, was $state" }
        state = State.OPEN

        // Install UDP transport completion handler
        udpTransport?.let { transport ->
            // Register for CQE fanout from the reactor
            // In real impl: Liburing.registerFanoutHandler(token) { cqe -> cqeChannel.send(cqe) }
        }
    }

    fun activate() {
        require(state == State.OPEN) { "activate() requires OPEN, was $state" }
        state = State.ACTIVE

        // Start DHT reader coroutine
        scope.launch {
            while (isActive) {
                // In real impl: receive CQE from channel, dispatch to udpTransport.onCompletion
                delay(100) // placeholder
            }
        }
    }

    fun drain() {
        if (state == State.CLOSED) return
        state = State.DRAINING
        supervisor.complete()
    }

    fun close() {
        if (state == State.CLOSED) return
        state = State.CLOSED
        supervisor.complete()
        udpTransport?.close()
    }

    // ═══════════════════════════════════════════════════════════════
    // KV Domain Store API (exposed to Couch via cross-context access)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Put a key-value pair into the IPFS-backed store.
     * Collection acts as the KV domain (e.g., "users", "config", "blocks").
     */
    suspend fun put(domain: String, key: String, value: ByteArray): CID {
        check(state == State.ACTIVE) { "Gateway not ACTIVE" }
        val cid = CID.put(value) // pseudo - real impl computes CID from value
        val data = blockStore as? IpfsBlockStore ?: error("BlockStore not IpfsBlockStore")
        // Store under blockId = key, then announce
        val blockId = data.put(domain, /* block */ TODO("build BlockRowVec from key+value"))
        dht.announceProvider(cid, "trikeshed-gateway:$domain/$blockId")
        return cid
    }

    /**
     * Get a value by key from the IPFS-backed store.
     */
    suspend fun get(domain: String, key: String): ByteArray? {
        check(state == State.ACTIVE) { "Gateway not ACTIVE" }
        val data = blockStore as? IpfsBlockStore ?: error("BlockStore not IpfsBlockStore")
        val block = data.get(domain, key)
        return block?.let { /* extract value */ TODO() }
    }

    /**
     * List all keys in a domain.
     */
    suspend fun list(domain: String): List<String> {
        check(state == State.ACTIVE) { "Gateway not ACTIVE" }
        val data = blockStore as? IpfsBlockStore ?: error("BlockStore not IpfsBlockStore")
        return data.list(domain)
    }

    /**
     * Remove a key from a domain.
     */
    suspend fun remove(domain: String, key: String): Boolean {
        check(state == State.ACTIVE) { "Gateway not ACTIVE" }
        val data = blockStore as? IpfsBlockStore ?: error("BlockStore not IpfsBlockStore")
        return data.remove(domain, key)
    }

    // ═══════════════════════════════════════════════════════════════
    // Cross-Context Access (CCEK)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Cross-context: CouchElement can call this to sync collections to IPFS.
     * Replicates a Couch collection to IPFS as a set of blocks.
     */
    suspend fun replicateCollectionToIpfs(couchElement: CouchElement, collectionName: String): Long {
        // Get all documents from Couch collection
        // Each doc -> BlockRowVec -> put into IPFS BlockStore
        // Build index block mapping docId -> CID
        // Announce provider for collection index CID
        return 0 // count
    }

    /**
     * Cross-context: IPFS can read Couch state via coroutineContext[CouchElement.Key].
     */
    suspend fun getCouchCollectionState(collectionName: String): Any? {
        val couch = coroutineContext[CouchElement.Key]
        return couch?.collections?.get(collectionName)?.activeDocuments()?.let { it.toSeries() }
    }

    // ═══════════════════════════════════════════════════════════════
    // Factory
    // ═══════════════════════════════════════════════════════════════

    companion object {
        /**
         * Create a full IPFS Gateway with reactor-wired UDP transport.
         * Requires ChannelOperations in coroutine context (provided by reactor).
         */
        fun create(
            realm: String = "default",
            bindPort: Int = 0,
        ): IpfsGatewayElement {
            val scope = CoroutineScope(SupervisorJob())
            val diskStore = DiskBlockStore(java.io.File("/tmp/trikeshed-ipfs-blocks"))
            val dht = DhtService()
            val udpTransport = UdpDhtTransport.create(scope, bindPort)
            val ipfsBlockStore = IpfsBlockStore.create(IpfsElement(diskStore, dht))

            return IpfsGatewayElement(ipfsBlockStore, dht, udpTransport, realm)
        }
    }
}