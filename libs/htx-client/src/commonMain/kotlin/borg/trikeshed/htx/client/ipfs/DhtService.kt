package borg.trikeshed.htx.client.ipfs

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.math.BigInteger
import java.net.InetSocketAddress
import java.util.Random

class DhtService(
    private val localNodeId: NodeId = NodeId.random(),
    private val transport: DhtTransport? = null,
) {
    
    private val providers = mutableMapOf<String, MutableSet<String>>()
    private val contacts = mutableMapOf<String, NodeInfo>()
    private val routingTable = RoutingTable(localNodeId)

    data class NodeId(val bytes: ByteArray) {
        companion object {
            fun random(): NodeId = NodeId(ByteArray(20).also { Random().nextBytes(it) })
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
        
        fun bucketIndex(other: NodeId): Int = xorDistance(other).bitLength() - 1
        override fun toString(): String = bytes.joinToString("") { "%02x".format(it) }
    }

    data class NodeInfo(
        val id: NodeId,
        val address: InetSocketAddress,
        var lastSeen: Long = System.currentTimeMillis(),
    )

    inner class RoutingTable(private val localId: NodeId) {
        private val buckets = Array(160) { mutableListOf<NodeInfo>() }
        private val k = 20
        
        fun addNode(node: NodeInfo) {
            val bucketIdx = localId.bucketIndex(node.id)
            val bucket = buckets[bucketIdx]
            if (bucket.none { it.id == node.id }) {
                if (bucket.size >= k) bucket.removeAt(0)
                bucket.add(node)
            }
        }
        
        fun findClosest(target: NodeId, count: Int = 20): List<NodeInfo> {
            return buckets.flatMap { it }
                .sortedBy { it.id.xorDistance(target) }
                .take(count)
        }
    }

    fun announceProvider(cid: CID, address: String) {
        providers.computeIfAbsent(cid.hex()) { mutableSetOf() }.add(address)
        transport?.let { t ->
            CoroutineScope(Job()).launch { t.announceProviderRemote(cid, address) }
        }
    }

    suspend fun findProviders(cid: CID): List<String> =
        providers[cid.hex()]?.toList() ?: transport?.findProvidersRemote(cid) ?: emptyList()

    suspend fun findNode(target: NodeId): List<NodeInfo> = routingTable.findClosest(target)

    fun handleFindNode(requester: NodeId, target: NodeId): List<NodeInfo> {
        contacts[requester.toString()] = NodeInfo(requester, InetSocketAddress("unknown", 0))
        return routingTable.findClosest(target)
    }

    fun handleFindProviders(requester: NodeId, cid: CID): List<String> {
        contacts[requester.toString()] = NodeInfo(requester, InetSocketAddress("unknown", 0))
        return providers[cid.hex()]?.toList() ?: emptyList()
    }

    fun handlePing(requester: NodeId): Boolean {
        contacts.getOrPut(requester.toString()) { NodeInfo(requester, InetSocketAddress("unknown", 0)) }
            .lastSeen = System.currentTimeMillis()
        return true
    }

    fun addContact(node: NodeInfo) {
        contacts[node.id.toString()] = node
        routingTable.addNode(node)
    }
}