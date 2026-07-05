package borg.trikeshed.ipfs

import borg.trikeshed.couch.runtime.CouchElement
import borg.trikeshed.miniduck.tablespace.BlockStore
import borg.trikeshed.miniduck.tablespace.IpfsBlockStore
import borg.trikeshed.ipfs.bitswap.BitswapMessage
import borg.trikeshed.userspace.reactor.MuxReactorElement
import borg.trikeshed.userspace.LiburingElement
import borg.trikeshed.userspace.FanoutDispatcherElement
import borg.trikeshed.userspace.installLiburingWithFanout
import borg.trikeshed.userspace.nio.channels.spi.ChannelOperations
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
    private val liburingWithFanout = installLiburingWithFanout()
    private val scope = CoroutineScope(reactor + liburingWithFanout.first + liburingWithFanout.second + coroutineContext)
    private val cqeChannel = Channel<ChannelResult>(100)

    // Reactor job tracking
    private val pendingJobs = mutableMapOf<String, CompletableDeferred<Unit>>()
    private val activeLeases = mutableMapOf<String, String>() // leaseId -> purpose

    override val key: CoroutineContext.Key<*> get() = Key

    init {
        // Register IPFS capacity keys in reactor
        registerIpfsCapacityKeys()
        // Subscribe to reactor lease grants
        subscribeToLeaseEvents()
    }

    private fun subscribeToLeaseEvents() {
        scope.launch {
            reactor.kanbanEvents
                .filterIsInstance<KanbanEvent.KeyLeased>()
                .onEach { event ->
                    val purpose = activeLeases[event.leasedTo]
                    if (purpose != null) {
                        val deferred = pendingJobs.remove(event.leasedTo)
                        private fun executeJobForPurpose(purpose: String, leaseId: String) {
                            scope.launch {
                                activeLeases[leaseId] = purpose
                                try {
                                    when (purpose) {
                                        "dht-transport" -> dhtTransportJob(leaseId)
                                        "bitswap-stream" -> bitswapStreamJob(leaseId)
                                        "blockstore-io" -> blockstoreIoJob(leaseId)
                                        "gateway-kv" -> gatewayKvJob(leaseId)
                                        "replication" -> replicationJob(leaseId)
                                        else -> println("[IpfsGateway] Unknown job purpose: $purpose")
                                    }
                                } finally {
                                    activeLeases.remove(leaseId)
                                    reactor.releaseLease("ipfs-gateway-$purpose", leaseId)
                                }
                            }
                        }

                        // ═══════════════════════════════════════════════════════════════
                        // Reactor Job Implementations
                        // ═══════════════════════════════════════════════════════════════

                        private suspend fun dhtTransportJob(leaseId: String) {
                            // Job 1: DHT transport - polls for provider announcements/find requests
                            while (isActive && activeLeases[leaseId] != null) {
                                processDhtFindRequests()
                                announceLocalProviders()
                                delay(1000)
                            }
                        }

                        private suspend fun bitswapStreamJob(leaseId: String) {
                            // Job 2: Bitswap stream - handles incoming block requests/offers
                            while (isActive && activeLeases[leaseId] != null) {
                                udpTransport?.let { transport ->
                                    val messages = transport.receiveBitswapMessages()
                                    messages.forEach { handleBitswapMessage(it) }
                                }
                                delay(500)
                            }
                        }

                        private suspend fun blockstoreIoJob(leaseId: String) {
                            // Job 3: Block store I/O - fans out I/O completions to handlers
                            while (isActive && activeLeases[leaseId] != null) {
                                val cqe = cqeChannel.receive()
                                dispatchCqe(cqe)
                            }
                        }

                        private suspend fun gatewayKvJob(leaseId: String) {
                            // Job 4: Gateway KV operations - put/get/list/remove with reactor lease
                            // This job is triggered on-demand via the public API methods
                            while (isActive && activeLeases[leaseId] != null) {
                                delay(100) // idle wait for on-demand operations
                            }
                        }

                        private suspend fun replicationJob(leaseId: String) {
                            // Job 5: Replication - syncs Couch collections to IPFS
                            while (isActive && activeLeases[leaseId] != null) {
                                delay(1000)
                            }
                        }
                }
                .launchIn(scope)
        }
    }

    private fun registerIpfsCapacityKeys() {
        // Register IPFS capacity keys in reactor
        val capacities = listOf(
            "dht-transport" to "DHT transport operations",
            "bitswap-stream" to "Bitswap stream operations",
            "blockstore-io" to "Block store I/O operations",
            "gateway-kv" to "Gateway KV operations",
            "replication" to "Collection replication operations",
        )
        capacities.forEach { (keyId, label) ->
            reactor.recordAccess(
                keyId = keyId,
                provider = "ipfs-gateway",
                label = label,
                modelUrl = "",
            )
        }
    }

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

        // Request leases for each reactor job type
        requestLease("dht-transport")
        requestLease("bitswap-stream")
        requestLease("blockstore-io")
        requestLease("gateway-kv")
        requestLease("replication")
    }

    private fun requestLease(purpose: String) {
        val leaseId = "ipfs-gateway-$purpose-${System.currentTimeMillis()}"
        activeLeases[leaseId] = purpose
        val deferred = CompletableDeferred<Unit>()
        pendingJobs[leaseId] = deferred
        // Request lease from reactor - uses the capacity key we registered
        scope.launch {
            reactor.tick() // triggers lease dispatch
        }
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
        val leaseId = requestLease("replication")
        try {
            return replicateCollectionToIpfs(couchElement, collectionName)
        } finally {
            releaseLease(leaseId)
        }
    }

    /** Job 5: Block fetch — gets a block via bitswap with DHT provider lookup */
    suspend fun fetchBlockJob(cid: CID): ByteArray? {
        val leaseId = requestLease("gateway-kv")
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

    private fun requestLease(purpose: String): String {
        val leaseId = "ipfs-gateway-$purpose-${System.currentTimeMillis()}"
        activeLeases[leaseId] = purpose
        val deferred = CompletableDeferred<Unit>()
        pendingJobs[leaseId] = deferred
        scope.launch {
            reactor.tick() // triggers lease dispatch
        }
        return leaseId
    }

    private fun releaseLease(leaseId: String) {
        activeLeases.remove(leaseId)
        pendingJobs.remove(leaseId)
        val purpose = activeLeases[leaseId] ?: "unknown"
        reactor.releaseLease("ipfs-gateway-$purpose", leaseId)
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
        val leaseId = requestLease("gateway-kv")
        try {
            val cid = CID.put(value)
            val data = blockStore as? IpfsBlockStore ?: error("BlockStore not IpfsBlockStore")
            val blockId = data.put(domain, /* block */ TODO("build BlockRowVec from key+value"))
            dht.announceProvider(cid, "trikeshed-gateway:$domain/$blockId")
            return cid
        } finally {
            releaseLease(leaseId)
        }
    }

    suspend fun get(domain: String, key: String): ByteArray? {
        check(state == State.ACTIVE) { "Gateway not ACTIVE" }
        val leaseId = requestLease("gateway-kv")
        try {
            val data = blockStore as? IpfsBlockStore ?: error("BlockStore not IpfsBlockStore")
            val block = data.get(domain, key)
            return block?.let { /* extract value */ TODO() }
        } finally {
            releaseLease(leaseId)
        }
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
         * Installs LiburingElement + FanoutDispatcherElement for io_uring-backed UDP.
         */
        fun create(
            reactor: MuxReactorElement,
            realm: String = "default",
            bindPort: Int = 0,
        ): IpfsGatewayElement {
            val diskStore = DiskBlockStore(java.io.File("/tmp/trikeshed-ipfs-blocks"))
            val dht = DhtService()
            val gateway = IpfsGatewayElement(DiskBlockStore(java.io.File("/tmp/trikeshed-ipfs-blocks")), dht, null, reactor, realm)
            val udpTransport = UdpDhtTransport.create(gateway.scope, bindPort)
            // Recreate with the transport
            return IpfsGatewayElement(diskStore, dht, udpTransport, reactor, realm)
        }
    }
}