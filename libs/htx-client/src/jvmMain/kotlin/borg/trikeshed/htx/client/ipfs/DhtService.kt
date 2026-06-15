package borg.trikeshed.htx.client.ipfs

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.InetSocketAddress

class DhtService(
    private val localNodeId: NodeId = NodeId.random(),
    private val transport: DhtTransport? = null,
) {
    private val providers = mutableMapOf<String, MutableSet<String>>()
    private val providersMutex = Mutex()
    private val routingTable = RoutingTable(localNodeId)

    inner class RoutingTable(private val localId: NodeId) {
        private val buckets = Array(160) { mutableListOf<NodeInfo>() }
        private val k = 20
        fun addNode(node: NodeInfo) {
            val idx = localId.bucketIndex(node.id)
            val bucket = buckets[idx]
            if (bucket.none { it.id == node.id }) { if (bucket.size >= k) bucket.removeAt(0); bucket.add(node) }
        }
        fun findClosest(target: NodeId, count: Int = 20): List<NodeInfo> =
            buckets.flatMap { it }.sortedBy { it.id.xorDistance(target) }.take(count)
    }

    suspend fun announceProvider(cid: CID, address: String) {
        providersMutex.withLock { providers.computeIfAbsent(cid.hex()) { mutableSetOf() }.add(address) }
        transport?.let { t -> CoroutineScope(Job()).launch { t.announceProviderRemote(cid, address) } }
    }

    suspend fun findProviders(cid: CID): List<String> = providersMutex.withLock {
        providers[cid.hex()]?.toList() ?: transport?.findProvidersRemote(cid) ?: emptyList()
    }

    suspend fun findNode(target: NodeId): List<NodeInfo> = routingTable.findClosest(target)
}
