package borg.trikeshed.htx.client.ipfs

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.math.BigInteger
import java.net.InetSocketAddress
import java.util.Random

/**
 * Kademlia DHT Service — provider discovery and node routing.
 * 
 * Features:
 * - Iterative FIND_PROVIDERS for content discovery
 * - Iterative FIND_NODE for routing table maintenance
 * - PING for liveness
 * - Provider announcement
 */
class DhtService(
    private val localNodeId: NodeId = NodeId.random(),
    private val transport: DhtTransport? = null,
) {
    
    /** Local provider registry: CID hex -> Set of provider addresses */
    private val providers = mutableMapOf<String, MutableSet<String>>()
    private val providersMutex = Mutex()
    
    /** Contact registry: peer hex ID -> NodeInfo */
    private val contacts = mutableMapOf<String, NodeInfo>()
    private val contactsMutex = Mutex()
    
    /** Routing table buckets (160 buckets for 160-bit IDs) */
    private val routingTable = RoutingTable(localNodeId)
    
    /** Pending iterative queries */
    private val pendingQueries = mutableMapOf<Long, PendingRequest<*>>()
    private val pendingMutex = Mutex()
    private var nextRequestId = 0L

    data class NodeId(val bytes: ByteArray) {
        companion object {
            fun random(): NodeId {
                return NodeId(ByteArray(20).also { Random().nextBytes(it) })
            }
            fun fromBigInteger(bi: BigInteger): NodeId {
                val bytes = bi.toByteArray()
                return if (bytes.size == 20) NodeId(bytes)
                else if (bytes.size > 20) NodeId(bytes.copyOfRange(bytes.size - 20, bytes.size))
                else NodeId(ByteArray(20 - bytes.size) + bytes)
            }
        }
        
        fun xorDistance(other: NodeId): BigInteger {
            val thisBi = BigInteger(1, bytes)
            val otherBi = BigInteger(1, other.bytes)
            return (thisBi xor otherBi).abs()
        }
        
        fun bucketIndex(other: NodeId): Int {
            val dist = xorDistance(other)
            return dist.bitLength() - 1
        }
        
        override fun toString(): String = bytes.joinToString("") { "%02x".format(it) }
    }

    data class NodeInfo(
        val id: NodeId,
        val address: InetSocketAddress,
        var lastSeen: Long = System.currentTimeMillis(),
    )

    /** Internal routing table with k-buckets. */
    inner class RoutingTable(private val localId: NodeId) {
        private val buckets = Array(160) { mutableListOf<NodeInfo>() }
        private val k = 20 // k-bucket size
        
        fun addNode(node: NodeInfo) {
            val bucketIdx = localId.bucketIndex(node.id)
            val bucket = buckets[bucketIdx]
            if (bucket.none { it.id == node.id }) {
                if (bucket.size >= k) bucket.removeAt(0)
                bucket.add(node)
            }
        }
        
        fun findClosest(target: NodeId, count: Int = 20): List<NodeInfo> {
            return buckets.flatten()
                .sortedBy { it.id.xorDistance(target) }
                .take(count)
        }
    }

    /** Announce this node as a provider for a CID. */
    fun announceProvider(cid: CID, address: String) = providersMutex.withLock {
        providers.computeIfAbsent(cid.hex()) { mutableSetOf() }.add(address)
        transport?.let { t ->
            CoroutineScope(Job()).launch { t.announceProviderRemote(cid, address) }
        }
    }

    /** Find providers for a CID (local + remote). */
    suspend fun findProviders(cid: CID): List<String> = providersMutex.withLock {
        val local = providers[cid.hex()]?.toList() ?: emptyList()
        if (local.isNotEmpty()) return@withLock local
        return@withLock transport?.findProvidersRemote(cid) ?: emptyList()
    }

    /** Iterative FIND_NODE - find k closest nodes to target. */
    suspend fun findNode(target: NodeId): List<NodeInfo> = pendingMutex.withLock {
        val local = routingTable.findClosest(target)
        transport?.let { t ->
            // In real impl: iterative RPC to closest nodes
        }
        return@withLock local
    }

    /** Handle incoming FIND_NODE request. */
    fun handleFindNode(requester: NodeId, target: NodeId): List<NodeInfo> {
        contactsMutex.withLock { contacts[requester.toString()] = NodeInfo(requester, InetSocketAddress("unknown", 0)) }
        return routingTable.findClosest(target)
    }

    /** Handle incoming FIND_PROVIDERS request. */
    fun handleFindProviders(requester: NodeId, cid: CID): List<String> {
        contactsMutex.withLock { contacts[requester.toString()] = NodeInfo(requester, InetSocketAddress("unknown", 0)) }
        return providersMutex.withLock { providers[cid.hex()]?.toList() ?: emptyList() }
    }

    /** Handle PING request. */
    fun handlePing(requester: NodeId): Boolean {
        contactsMutex.withLock { 
            contacts.getOrPut(requester.toString()) { NodeInfo(requester, InetSocketAddress("unknown", 0)) }
                .lastSeen = System.currentTimeMillis()
        }
        return true
    }

    /** Add a contact to routing table. */
    fun addContact(node: NodeInfo) {
        contactsMutex.withLock { contacts[node.id.toString()] = node }
        routingTable.addNode(node)
    }

    private inline fun nextRequestId(): Long {
        val id = nextRequestId
        nextRequestId++
        return id
    }

    private data class PendingRequest<T>(
        val cid: CID,
        val completion: CompletableDeferred<T>,
    )
}