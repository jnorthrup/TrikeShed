package borg.trikeshed.dht.routing

import borg.trikeshed.dht.id.NUID
import borg.trikeshed.dht.include.Address
import borg.trikeshed.dht.include.Route
import borg.trikeshed.dht.net.NetMask
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.leftIdentity
import kotlin.math.min

open class RoutingTable<TNum : Comparable<TNum>, Sz : NetMask<TNum>>(
    val agentNUID: NUID<TNum>, val optimal: Boolean = false,
) {
   val bitOps = agentNUID.ops

    fun addRoute(other: Route<TNum>): Join<NUID<TNum>, Address>? = other.let { (g: NUID<TNum>) ->
        min(agentNUID.netmask.distance(agentNUID.id!!, g.id!!), bucketCount).let {
            if (it > 0)
                buckets[it.dec()].getOrPut(g.id!!, other.leftIdentity)
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