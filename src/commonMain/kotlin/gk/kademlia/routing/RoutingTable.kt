package gk.kademlia.routing

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.`↺`
import gk.kademlia.id.NUID
import gk.kademlia.include.Address
import gk.kademlia.include.Route
import gk.kademlia.net.NetMask
import java.lang.Integer.min


/**
 * once an agent knows its network id it can create a routeTable, the agent will also be
 * responsible for assigning new GUIDS on all routes
 * before touching the route table.
 *
 */
open class RoutingTable<TNum : Comparable<TNum>, Sz : NetMask<TNum>>(
    val agentNUID: NUID<TNum>, val optimal: Boolean = false,
) {
    private val bitOps = agentNUID.ops

    /**
     * contract is to have the route guid id fully realized in agent first
     */
    fun addRoute(other: Route<TNum>): Join<NUID<TNum>, Address>? = other.let { (g: NUID<TNum>) ->
        min(agentNUID.netmask.distance(agentNUID.id!!, g.id!!), bucketCount).let {
            if (it > 0)
                buckets[it.dec()].getOrPut(g.id!!, other.`↺`)
            else null
        }
    }

    fun rmRoute(other: Route<TNum>): Join<NUID<TNum>, Address>? = other.let { (g: NUID<TNum>) ->
        agentNUID.netmask.distance(agentNUID.id!!, g.id!!).let { origDistance ->
            if (origDistance > 0)
                buckets.takeIf { it.isNotEmpty() }?.get(min(bucketCount, origDistance.dec()))?.remove(g.id!!)
            else null

        }
    }

    fun bucketFor(g: NUID<TNum>): Int =
        min(agentNUID.netmask.distance(agentNUID.id!!, g.id!!), bucketCount).dec()

    open val bucketCount: Int = agentNUID.netmask.bits.let { if (optimal) it else it / 2 + 1 }
    open val bucketSize: Int = agentNUID.netmask.bits.let { if (optimal) it else it / 2 + 1 }

    val buckets: Array<MutableMap<TNum, Route<TNum>>> = Array(bucketCount) { linkedMapOf() }

}