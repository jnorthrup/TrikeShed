package borg.trikeshed.ipfs

import borg.trikeshed.couch.runtime.CouchElement
import borg.trikeshed.miniduck.tablespace.BlockStore
import borg.trikeshed.miniduck.tablespace.IpfsBlockStore
import borg.trikeshed.ipfs.bitswap.BitswapMessage
import borg.trikeshed.userspace.reactor.MuxReactorElement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
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
    private val reactor: MuxReactorElement,
    override val realm: String = "default",
) : AbstractCoroutineContextElement(Key), IpfsElement(blockStore, dhtService) {

    companion object Key : kotlinx.coroutines.CoroutineContext.Key<IpfsGatewayElement>

    // ═══════════════════════════════════════════════════════════════
    // Lifecycle (Pattern A CCEK) — delegates to reactor
    // ═══════════════════════════════════════════════════════════════

    enum class State { CREATED, OPEN, ACTIVE, DRAINING, CLOSED }

    @Volatile
    var state: State = State.CREATED

    // Use reactor's supervisor and scope - no separate SupervisorJob
    private val scope = CoroutineScope(reactor + coroutineContext)
    private val cqeChannel = Channel<ChannelResult>(100)

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

        // Start DHT reader reactor job
        scope.launch { dhtReaderJob() }

        // Start bitswap listener reactor job
        scope.launch { bitswapListenerJob() }

        // Start CQE dispatch reactor job
        scope.launch { cqeDispatchJob() }
    }

    // ═══════════════════════════════════════════════════════════════
    // Reactor Jobs — each use case gets its own lease-driven coroutine
    // ═══════════════════════════════════════════════════════════════

    /** Job 1: DHT reader — polls for provider announcements/find requests */
    private suspend fun dhtReaderJob() {
        while (isActive) {
            // Request a lease for DHT operations
            val leaseId = requestLease("dht-reader") ?: return
            try {
                // Poll DHT for pending find requests
                processDhtFindRequests()
                // Announce local providers periodically
                announceLocalProviders()
            } finally {
                releaseLease(leaseId)
            }
            delay(1000) // DHT poll interval
        }
    }

    /** Job 2: Bitswap listener — handles incoming block requests/offers */
    private suspend fun bitswapListenerJob() {
        while (isActive) {
            val leaseId = requestLease("bitswap-listener") ?: return
            try {
                // Process incoming bitswap messages via UDP transport
                udpTransport?.let { transport ->
                    val messages = transport.receiveBitswapMessages()
                    messages.forEach { handleBitswapMessage(it) }
                }
            } finally {
                releaseLease(leaseId)
            }
            delay(500) // Bitswap poll interval
        }
    }

    /** Job 3: CQE dispatch — fans out I/O completions to handlers */
    private suspend fun cqeDispatchJob() {
        while (isActive) {
            val cqe = cqeChannel.receive()
            // Dispatch CQE to appropriate handler (DHT, bitswap, block store)
            dispatchCqe(cqe)
        }
    }

    /** Job 4: Replication — syncs Couch collections to IPFS (on-demand lease) */
    suspend fun replicateCollectionJob(couchElement: CouchElement, collectionName: String): Long {
        val leaseId = requestLease("replication-$collectionName") ?: return 0
        try {
            return replicateCollectionToIpfs(couchElement, collectionName)
        } finally {
            releaseLease(leaseId(leaseId)
        }
    }

    /** Job 5: Block fetch — gets a block via bitswap with DHT provider lookup */
    suspend fun fetchBlockJob(cid: CID): ByteArray? {
        val leaseId = requestLease("fetch-${cidHex(cid).take(8)}") ?: return null
        try {
            // 1. Check local store
            val local = blockStore.get(cid)
            if (local != null) return local

            // 2. Find providers via DHT
            val providers = dht.findProviders(cid)
            if (providers.isEmpty()) return null

            // 3. Request block via bitswap from first provider
            return requestBlockViaBitswap(cid, providers.first())
        } finally {
            releaseLease(leaseId)
        }
    }

    private fun requestLease(purpose: String): String? {
        // In real impl: ask reactor for a lease via kanbanEvents or direct call
        // For now, generate a deterministic lease ID
        return "ipfs-$purpose-${System.currentTimeMillis()}"
    }

    private fun releaseLease(leaseId: String) {
        // In real impl: release lease via reactor
    }

    // ═══════════════════════════════════════════════════════════════
    // Reactor Job Helpers
    // ═══════════════════════════════════════════════════════════════

    private suspend fun processDhtFindRequests() {
        // In real impl: query DHT for pending find requests from other peers
    }

    private suspend fun announceLocalProviders() {
        // In real impl: re-announce locally stored CIDs to DHT
    }

    private suspend fun handleBitswapMessage(message: BitswapMessage) {
        // In real impl: route to BitswapEngine
    }

    private suspend fun dispatchCqe(cqe: ChannelResult) {
        // In real impl: dispatch I/O completion to DHT/bitswap/block store handlers
    }

    private suspend fun requestBlockViaBitswap(cid: CID, provider: String): ByteArray? {
        // In real impl: send WANT_BLOCK to provider via bitswap, await BLOCK response
        return null
    }

    private fun cidHex(cid: CID): String = cid.bytes.joinToString("") { "%02x".format(it) }

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

    /** Put a key-value pair into the IPFS-backed store using a reactor job. */
    suspend fun put(domain: String, key: String, value: ByteArray): CID {
        check(state == State.ACTIVE) { "Gateway not ACTIVE" }
        val leaseId = requestLease("put-$domain-$key") ?: return CID(ByteArray(0))
        try {
            val cid = CID.put(value) // pseudo - real impl computes CID from value
            val data = blockStore as? IpfsBlockStore ?: error("BlockStore not IpfsBlockStore")
            val blockId = data.put(domain, /* block */ TODO("build BlockRowVec from key+value"))
            dht.announceProvider(cid, "trikeshed-gateway:$domain/$blockId")
            return cid
        } finally {
            releaseLease(leaseId)
        }
    }

    /** Get a value by key from the IPFS-backed store using a reactor job. */
    suspend fun get(domain: String, key: String): ByteArray? {
        check(state == State.ACTIVE) { "Gateway not ACTIVE" }
        val leaseId = requestLease("get-$domain-$key") ?: return null
        try {
            val data = blockStore as? IpfsBlockStore ?: error("BlockStore not IpfsBlockStore")
            val block = data.get(domain, key)
            return block?.let { /* extract value */ TODO() }
        } finally {
            releaseLease(leaseId)
        }
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
            reactor: MuxReactorElement,
            realm: String = "default",
            bindPort: Int = 0,
        ): IpfsGatewayElement {
            val scope = CoroutineScope(SupervisorJob())
            val diskStore = DiskBlockStore(java.io.File("/tmp/trikeshed-ipfs-blocks"))
            val dht = DhtService()
            val udpTransport = UdpDhtTransport.create(scope, bindPort)
            val ipfsBlockStore = IpfsBlockStore.create(IpfsElement(diskStore, dht))

            return IpfsGatewayElement(ipfsBlockStore, dht, udpTransport, reactor, realm)
        }
    }
}