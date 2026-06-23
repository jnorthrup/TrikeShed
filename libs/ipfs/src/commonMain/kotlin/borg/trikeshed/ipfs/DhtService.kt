package borg.trikeshed.ipfs

import borg.trikeshed.dht.routing.RoutingTable
import borg.trikeshed.dht.id.NUID
import borg.trikeshed.dht.net.NetMask
import borg.trikeshed.dht.id.ByteNUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlin.collections.mutableSetOf
import java.net.InetSocketAddress
import kotlin.time.Duration

class DhtService(
    private val transport: DhtTransport? = null,
    private val localNUID: NUID<Byte> = ByteNUID(0x12),
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

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}

// Extension for routing table node info
private data class NodeInfo(
    val id: ByteArray,
    val address: InetSocketAddress,
)
