package borg.trikeshed.ipfs

import borg.trikeshed.dht.agent.WorldAgent
import borg.trikeshed.dht.agent.WorldRouter
import borg.trikeshed.dht.id.NUID
import borg.trikeshed.dht.id.NUID.Companion.minNUID
import borg.trikeshed.dht.id.impl.BigIntegerNUID
import borg.trikeshed.dht.include.Address
import borg.trikeshed.dht.include.Route
import borg.trikeshed.dht.routing.RoutingTable
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlin.collections.mutableSetOf
import kotlin.math.min
import java.math.BigInteger
import java.util.Random

/**
 * Kademlia DHT Service with iterative routing.
 *
 * Supports:
 * - Provider announcement (ANNOUNCE_PROVIDER)
 * - Provider lookup (FIND_PROVIDERS)
 * - Node lookup (FIND_NODE) - iterative closest nodes
 * - Value lookup (FIND_VALUE) - iterative value retrieval
 * - PING for liveness
 */
class DhtService(
    private val localAgent: WorldAgent = createDefaultAgent(),
    private val transport: DhtTransport? = null,
) {

    // Local provider registry: CID hex -> Set of provider addresses
    private val providers: MutableMap<String, MutableSet<String>> = mutableMapOf()

    // Contact registry: peer ID (NUID hex) -> Address
    private val contacts: MutableMap<String, Address> = mutableMapOf()

    // Pending iterative queries
    private val pendingQueries = Mutex()

    /**
     * Announce this node as a provider for a CID.
     */
    fun announceProvider(cid: CID, address: String) {
        val key = hex(cid.bytes)
        providers.getOrPut(key) { mutableSetOf() }.add(address)

        transport?.let { t ->
            try {
                GlobalScope.launch {
                    t.announceProviderRemote(cid, address)
                }
            } catch (_: Throwable) {
                // ignore transport errors in prototype
            }
        }
    }

    /**
     * Find providers for a CID (local + remote).
     */
    suspend fun findProviders(cid: CID): List<String> {
        val key = hex(cid.bytes)
        val local = providers[key]?.toList() ?: emptyList()
        if (local.isNotEmpty()) return local
        return transport?.findProvidersRemote(cid) ?: emptyList()
    }

    /**
     * Iterative FIND_NODE - find k closest nodes to target ID.
     * Returns list of (peerId, address) pairs sorted by XOR distance.
     */
    suspend fun findNode(targetId: NUID<*>): List<Pair<String, Address>> {
        return pendingQueries.withLock {
            // Start with local routing table
            val localClosest = localAgent.routingTable.buckets
                .flatten()
                .map { (peerId, route) -> route.first to route.second }
                .sortedBy { (peerId, _) ->
                    localAgent.NUID.netmask.distance(localAgent.NUID.id!!, peerId as Any)
                }
                .take(20) // K = 20 (Kademlia k-bucket size)

            // If we have transport, do iterative lookup
            transport?.let { t ->
                // In real impl: send FIND_NODE to closest nodes, collect responses,
                // update routing table, recurse until convergence
                // For now, return local results
            }
            return@withLock localClosest
        }
    }

    /**
     * Iterative FIND_VALUE - find value for key (CID), or closest nodes if not found.
     */
    suspend fun findValue(cid: CID): Either<List<String>, List<Pair<String, Address>>> {
        val providers = findProviders(cid)
        if (providers.isNotEmpty()) {
            return Either.Left(providers)
        }
        // If no providers found, return closest nodes
        val targetNuid = minNUID(160) // Would derive from CID in real impl
        val closest = findNode(targetNuid)
        return Either.Right(closest)
    }

    /**
     * Handle incoming FIND_NODE request from remote peer.
     * Returns k closest nodes from local routing table.
     */
    fun handleFindNodeRequest(requesterId: NUID<*>, targetId: NUID<*>): List<Pair<String, Address>> {
        return localAgent.routingTable.buckets
            .flatten()
            .map { (peerId, route) -> route.first to route.second }
            .sortedBy { (peerId, _) ->
                targetId.netmask.distance(targetId.id!!, peerId as Any)
            }
            .take(20)
    }

    /**
     * Handle incoming FIND_VALUE request from remote peer.
     * Returns value if we have it, otherwise closest nodes.
     */
    fun handleFindValueRequest(requesterId: NUID<*>, cid: CID): Either<ByteArray, List<Pair<String, Address>>> {
        val key = hex(cid.bytes)
        // In real impl, check local block store
        // For now, return closest nodes
        val targetNuid = minNUID(160)
        val closest = localAgent.routingTable.buckets
            .flatten()
            .map { (peerId, route) -> route.first to route.second }
            .sortedBy { (peerId, _) ->
                targetNuid.netmask.distance(targetNuid.id!!, peerId as Any)
            }
            .take(20)
        return Either.Right(closest)
    }

    /**
     * Handle incoming PING request.
     */
    fun handlePing(requesterId: NUID<*>): Boolean {
        // Update contact last-seen
        contacts[hex(requesterId.id as ByteArray? ?: byteArrayOf())] = "ping"
        return true
    }

    /**
     * Add a contact to the routing table.
     */
    fun addContact(peerId: NUID<*>, address: Address) {
        val key = hex(peerId.id as ByteArray? ?: byteArrayOf())
        contacts[key] = address

        // Add to local agent's routing table
        val route = Route(peerId, address)
        localAgent.routingTable.addRoute(route)
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    companion object {
        fun createDefaultAgent(): WorldAgent {
            val nuid = minNUID(160) as BigIntegerNUID
            // Assign random ID
            nuid.assign(BigInteger(160, Random()))
            val routingTable = WorldRouter(nuid)
            return WorldAgent(nuid, routingTable)
        }
    }
}

// Type-safe Either for FIND_VALUE result
sealed class Either<out L, out R> {
    data class Left<out L>(val value: L) : Either<L, Nothing>()
    data class Right<out R>(val value: R) : Either<Nothing, R>()
}