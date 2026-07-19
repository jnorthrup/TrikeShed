package borg.trikeshed.dht.routing

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.leftIdentity
import borg.trikeshed.dht.id.NUID
import borg.trikeshed.dht.include.Address
import borg.trikeshed.dht.include.Route
import borg.trikeshed.dht.net.NetMask
//import java.lang.Integer.min
import kotlin.math.min

/**
 * once an agent knows its network id it can create a routeTable, the agent will also be
 * responsible for assigning new GUIDS on all routes
 * before touching the route table.
 *
 */
open class RoutingTable<TNum : Comparable<TNum>, Sz : borg.trikeshed.dht.net.NetMask<TNum>>(
    val agentNUID: borg.trikeshed.dht.id.NUID<TNum>, val optimal: Boolean = false,
) {
   val bitOps = agentNUID.ops

    /**
     * contract is to have the route guid id fully realized in agent first
     */
    /**
     * Note: CCEK emission is performed by publishing to the RoutingEventElement present in the coroutine context.
     * Ensure this method is called within a coroutine context having RoutingEventElement to broadcast events.
     */
    suspend fun addRoute(other: borg.trikeshed.dht.include.Route<TNum>, context: kotlin.coroutines.CoroutineContext? = null): Join<borg.trikeshed.dht.id.NUID<TNum>, borg.trikeshed.dht.include.Address>? = other.let { (g: borg.trikeshed.dht.id.NUID<TNum>) ->
        min(agentNUID.netmask.distance(agentNUID.id!!, g.id!!), bucketCount).let {
            if (it > 0) {
                val res = buckets[it.dec()].getOrPut(g.id!!, other.leftIdentity)
                if (context != null) publishRoutingEvent(context, RoutingEvent.RouteAdded(other))
                res
            }
            else null
        }
    }

    suspend fun rmRoute(other: borg.trikeshed.dht.include.Route<TNum>, context: kotlin.coroutines.CoroutineContext? = null): Join<borg.trikeshed.dht.id.NUID<TNum>, borg.trikeshed.dht.include.Address>? = other.let { (g: borg.trikeshed.dht.id.NUID<TNum>) ->
        agentNUID.netmask.distance(agentNUID.id!!, g.id!!).let { origDistance ->
            if (origDistance > 0) {
                val res = buckets.takeIf { it.isNotEmpty() }?.get(min(bucketCount, origDistance.dec()))?.remove(g.id!!)
                if (context != null && res != null) publishRoutingEvent(context, RoutingEvent.RouteEvicted(other))
                res
            }
            else null

        }
    }

    /**
     * Non-suspending variant for legacy compatibility where context is unavailable.
     */
    fun addRouteSync(other: borg.trikeshed.dht.include.Route<TNum>): Join<borg.trikeshed.dht.id.NUID<TNum>, borg.trikeshed.dht.include.Address>? = other.let { (g: borg.trikeshed.dht.id.NUID<TNum>) ->
        min(agentNUID.netmask.distance(agentNUID.id!!, g.id!!), bucketCount).let {
            if (it > 0) buckets[it.dec()].getOrPut(g.id!!, other.leftIdentity)
            else null
        }
    }

    fun bucketFor(g: borg.trikeshed.dht.id.NUID<TNum>): Int =
        min(agentNUID.netmask.distance(agentNUID.id!!, g.id!!), bucketCount).dec()

    open val bucketCount: Int = agentNUID.netmask.bits.let { if (optimal) it else it / 2 + 1 }
    open val bucketSize: Int = agentNUID.netmask.bits.let { if (optimal) it else it / 2 + 1 }

    val buckets: Array<MutableMap<TNum, borg.trikeshed.dht.include.Route<TNum>>> = Array(bucketCount) { linkedMapOf() }

    /** Get K closest nodes to target from routing table */
    fun getClosest(target: TNum, k: Int): List<Join<borg.trikeshed.dht.id.NUID<TNum>, Address>> {
        val allRoutes = mutableListOf<Join<borg.trikeshed.dht.id.NUID<TNum>, Address>>()
        for (bucket in buckets) {
            for ((_, value) in bucket.entries) {
                allRoutes.add(value)
            }
        }
        // Sort by XOR distance to target
        allRoutes.sortBy { agentNUID.netmask.distance(agentNUID.id!!, it.a.id!!) }
        return allRoutes.take(k)
    }
}