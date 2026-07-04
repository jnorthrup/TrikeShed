package borg.trikeshed.ipfs

import borg.trikeshed.dht.routing.RoutingTable
import borg.trikeshed.dht.id.NUID
import borg.trikeshed.dht.net.NetMask
import borg.trikeshed.dht.id.ElectionNUID
import borg.trikeshed.dht.include.Route
import borg.trikeshed.lib.Join
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll
import kotlin.collections.mutableSetOf
import java.net.InetSocketAddress
import kotlin.time.Duration

class DhtService(
    private val transport: DhtTransport? = null,
    private val localNUID: NUID<Byte> = ElectionNUID(0x12),
) {
    private val providers: MutableMap<String, MutableSet<String>> = mutableMapOf()
    private val scope = CoroutineScope(SupervisorJob())
    
    // Kademlia routing table for iterative routing
    private val routingTable = RoutingTable(localNUID, optimal = false)
    
    // Bootstrap nodes for initial routing table population
    private val bootstrapNodes = listOf(
        InetSocketAddress("127.0.0.1", 4001),
        InetSocketAddress("127.0.0.1", 4002),
    )
    
    // Kademlia constants
    private val K = 8 // Kademlia bucket size
    private val ALPHA = 3 // Concurrency factor
    private val MAX_ITERATIONS = 5
    
    // Pending requests for FIND_NODE/FIND_VALUE
    private val pendingFindNode = mutableMapOf<String, CompletableDeferred<List<InetSocketAddress>>>()
    private val pendingFindValue = mutableMapOf<String, CompletableDeferred<ByteArray>>()
    private var nextRequestId = 0L

    fun announceProvider(cid: CID, address: String) {
        val key = hex(cid.bytes)
        providers.getOrPut(key) { mutableSetOf() }.add(address)

        transport?.let { t ->
            scope.launch {
                try {
                    t.announceProviderRemote(cid, address)
                } catch (_: Throwable) {
                    // ignore transport errors in prototype
                }
            }
        }
    }

    suspend fun findProviders(cid: CID): List<String> {
        val key = hex(cid.bytes)
        val local = providers[key]?.toList() ?: emptyList()
        if (local.isNotEmpty()) return local
        return transport?.findProvidersRemote(cid) ?: emptyList()
    }

    fun close() {
        scope.cancel()
    }

    // ════════════════════════════════════════════════════════════════
    // Kademlia Iterative Routing
    // ════════════════════════════════════════════════════════════════

    /** 
     * Iterative FIND_NODE: finds k closest nodes to the target CID using Kademlia routing.
     * Algorithm:
     * 1. Start with bootstrap nodes as initial candidates
     * 2. For each iteration (max MAX_ITERATIONS):
     *    a. Select ALPHA closest unqueried candidates to target
     *    b. Send FIND_NODE to each in parallel
     *    c. Await NEIGHBORS responses
     *    d. Add returned nodes to routing table and candidate set
     *    e. If no new closer nodes found, break
     * 3. Return K closest nodes from routing table
     */
    suspend fun findNode(target: CID): List<InetSocketAddress> {
        val requestId = nextRequestId++.toString()
        val deferred = CompletableDeferred<List<InetSocketAddress>>()
        pendingFindNode[requestId] = deferred
        
        scope.launch {
            try {
                val result = iterativeFindNode(target, requestId)
                deferred.complete(result)
            } catch (e: Throwable) {
                deferred.completeExceptionally(e)
            } finally {
                pendingFindNode.remove(requestId)
            }
        }
        
        return try {
            deferred.await()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /** Core iterative FIND_NODE implementation */
    private suspend fun iterativeFindNode(target: CID, requestId: String): List<InetSocketAddress> {
        // Candidates: (distance, addressString) - sorted by XOR distance to target
        val candidates = mutableListOf<Pair<Int, String>>()
        val queried = mutableSetOf<String>()
        
        // Initialize with bootstrap nodes
        bootstrapNodes.forEach { addr ->
            val addrStr = addr.toString()
            val dist = localNUID.netmask.distance(localNUID.id!!, target.bytes.toUByteArray())
            candidates.add(dist to addrStr)
        }
        candidates.sortBy { it.first }
        
        var iteration = 0
        while (iteration < MAX_ITERATIONS && candidates.isNotEmpty()) {
            iteration++
            
            // Select ALPHA closest unqueried candidates
            val toQuery = candidates
                .filter { (_, addr) -> addr !in queried }
                .take(ALPHA)
                .map { it.second }
            
            if (toQuery.isEmpty()) break
            
            // Mark as queried
            toQuery.forEach { queried.add(it) }
            
            // Send FIND_NODE in parallel and collect NEIGHBORS responses
            val responses = sendFindNodeParallel(toQuery, target, requestId)
            
            // Process responses: add nodes to routing table and candidate set
            var foundCloser = false
            for (response in responses) {
                for (nodeInfo in response.nodes) {
                    val nodeAddrStr = nodeInfo.address.toString()
                    // Use first byte of CID as node ID for 8-bit NUID
                    val nodeIdByte = nodeInfo.id.getOrNull(0)?.toByte() ?: 0
                    
                    // Add to routing table (uses String address)
                    val nodeNUID = ElectionNUID(nodeIdByte)
                    val route: Route<Byte> = Join(nodeNUID, nodeAddrStr)
                    routingTable.addRoute(route)
                    
                    // Add to candidates if not queried
                    if (nodeAddrStr !in queried) {
                        val dist = localNUID.netmask.distance(localNUID.id!!, nodeIdByte)
                        candidates.add(dist to nodeAddrStr)
                        foundCloser = true
                    }
                }
            }
            
            // Re-sort candidates by distance
            candidates.sortBy { it.first }
            
            // If no new closer nodes found, we've converged
            if (!foundCloser) break
        }
        
        // Return K closest from routing table, convert String addresses to InetSocketAddress
        // Use first byte of target CID as the target ID for 8-bit NUID
        val targetId = target.bytes.firstOrNull()?.toByte() ?: 0
        return routingTable.getClosest(targetId, K)
            .map { it.b }
            .map { InetSocketAddress.createUnresolved(it.split(":")[0], it.split(":")[1].toInt()) }
    }

    /**
     * Iterative FIND_VALUE: finds the value associated with a key.
     * Returns the value if found, or closest nodes if not.
     */
    suspend fun findValue(key: CID): ByteArray? {
        val requestId = nextRequestId++.toString()
        val deferred = CompletableDeferred<ByteArray>()
        pendingFindValue[requestId] = deferred
        
        // Send FIND_VALUE to bootstrap nodes
        sendFindValue(bootstrapNodes, key, requestId)
        
        return try {
            deferred.await()
        } catch (_: Throwable) {
            null
        } finally {
            pendingFindValue.remove(requestId)
        }
    }

    private suspend fun sendFindNode(nodes: List<InetSocketAddress>, target: CID, requestId: String) {
        nodes.forEach { node ->
            transport?.let { t ->
                scope.launch {
                    try {
                        val message = DhtMessage.FindNode(target, requestId)
                        t.sendTo(node, DhtMessage.encode(message))
                    } catch (_: Throwable) {}
                }
            }
        }
    }

    private suspend fun sendFindValue(nodes: List<InetSocketAddress>, key: CID, requestId: String) {
        nodes.forEach { node ->
            transport?.let { t ->
                scope.launch {
                    try {
                        val message = DhtMessage.FindValue(key, requestId)
                        t.sendTo(node, DhtMessage.encode(message))
                    } catch (_: Throwable) {}
                }
            }
        }
    }

    /** Send FIND_NODE to multiple nodes in parallel and await NEIGHBORS responses */
    private suspend fun sendFindNodeParallel(
        nodes: List<String>, 
        target: CID, 
        requestId: String
    ): List<DhtMessage.Neighbors> {
        return coroutineScope {
            nodes.map { nodeStr ->
                async {
                    val node = InetSocketAddress.createUnresolved(
                        nodeStr.split(":")[0], 
                        nodeStr.split(":")[1].toInt()
                    )
                    transport?.let { t ->
                        try {
                            val message = DhtMessage.FindNode(target, requestId)
                            val response = t.sendAndReceive(node, DhtMessage.encode(message))
                            val decoded = DhtMessage.decode(response)
                            if (decoded is DhtMessage.Neighbors) {
                                decoded
                            } else {
                                null
                            }
                        } catch (_: Throwable) {
                            null
                        }
                    }
                }
            }.awaitAll()
                .filterNotNull()
        }
    }

    /** Send FIND_VALUE to multiple nodes in parallel and await VALUE/NEIGHBORS responses */
    private suspend fun sendFindValueParallel(
        nodes: List<String>, 
        key: CID, 
        requestId: String
    ): List<Any> {
        return coroutineScope {
            nodes.map { nodeStr ->
                async {
                    val node = InetSocketAddress.createUnresolved(
                        nodeStr.split(":")[0], 
                        nodeStr.split(":")[1].toInt()
                    )
                    transport?.let { t ->
                        try {
                            val message = DhtMessage.FindValue(key, requestId)
                            val response = t.sendAndReceive(node, DhtMessage.encode(message))
                            val decoded = DhtMessage.decode(response)
                            decoded
                        } catch (_: Throwable) {
                            null
                        }
                    }
                }
            }.awaitAll()
                .filterNotNull()
        }
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}

// Extension for routing table node info
private data class NodeInfo(
    val id: ByteArray,
    val address: InetSocketAddress,
)
