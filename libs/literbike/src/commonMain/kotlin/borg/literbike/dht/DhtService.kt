package borg.literbike.dht

/**
 * DHT Service interface for higher-level flows.
 * Ported from literbike/src/dht/service.rs.
 */

const val DHT_SERVICE_KEY: String = "dht_service"

/**
 * Interface for DHT persistence (e.g. DuckDB).
 * Port of DhtPersistence trait.
 */
interface DhtPersistence {
    /** Save or update a peer in persistent storage */
    fun upsertNode(peer: PeerInfo)

    /** Load all known peers from persistent storage */
    fun loadNodes(): List<PeerInfo>

    /** Save or update a DHT value */
    fun upsertValue(key: String, value: ByteArray)
}

/**
 * DHT Service interface for higher-level flows.
 * Port of DhtService struct.
 */
class DhtService(
    private val routingTable: kotlin.collections.MutableMap<String, Any> = mutableMapOf(),
    private var persistence: DhtPersistence? = null
) {
    private var _routingTable: RoutingTable

    val routingTableRef: RoutingTable
        get() = _routingTable

    constructor(localPeerId: PeerId) : this(localPeerId, null)

    constructor(localPeerId: PeerId, persistence: DhtPersistence?) : this() {
        _routingTable = RoutingTable(localPeerId, 20)
        this.persistence = persistence
        if (this.persistence != null) {
            rehydrate()
        }
    }

    /** Add a peer to the routing table and persist if available */
    fun addPeer(peer: PeerInfo) {
        persistence?.upsertNode(peer)
        _routingTable.addPeer(peer)
    }

    /** Get a peer by ID */
    fun getPeer(peerId: PeerId): PeerInfo? = _routingTable.getPeer(peerId)

    /** Find closest peers to a given ID */
    fun closestPeers(peerId: PeerId, count: Int): List<PeerInfo> =
        _routingTable.findClosestPeers(peerId, count)

    /** Reload routing table from persistence */
    fun rehydrate() {
        persistence?.let { p ->
            val nodes = p.loadNodes()
            for (node in nodes) {
                _routingTable.addPeer(node)
            }
        }
    }

    /** Update the persistence adapter and rehydrate */
    fun setPersistence(persistence: DhtPersistence) {
        this.persistence = persistence
        rehydrate()
    }

    /** Get the service key for context composition */
    fun key(): String = DHT_SERVICE_KEY
}
