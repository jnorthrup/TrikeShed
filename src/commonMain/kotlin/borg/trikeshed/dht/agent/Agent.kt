package borg.trikeshed.dht.agent


import borg.trikeshed.dht.id.NUID
import borg.trikeshed.dht.net.NetMask
import borg.trikeshed.dht.routing.RoutingTable
import borg.trikeshed.dht.include.Address
import borg.trikeshed.dht.include.Route
import borg.trikeshed.lib.j
import borg.trikeshed.num.BigInt as BigInteger

interface Agent<TNum : Comparable<TNum>, Sz : borg.trikeshed.dht.net.NetMask<TNum>> {
    /**
     * Network Unique Id
     */
    val NUID: borg.trikeshed.dht.id.NUID<TNum>
    val routingTable: borg.trikeshed.dht.routing.RoutingTable<TNum, Sz>

    /** The local agent remains eligible even though its table stores only other routes. */
    fun getClosest(target: TNum, k: Int, localAddress: Address): List<Route<TNum>> {
        require(k >= 0) { "Negative routing result count" }
        if (k == 0) return emptyList()
        return (routingTable.getClosest(target, k) + (NUID j localAddress))
            .sortedWith { a, b -> NUID.netmask.compareDistance(target, a.a.id!!, b.a.id!!) }.take(k)
    }
}

class WorldAgent(
    override val NUID: borg.trikeshed.dht.id.NUID<BigInteger>,
    override val routingTable: borg.trikeshed.dht.routing.RoutingTable<BigInteger, borg.trikeshed.dht.agent.WorldNetwork>,

    ) : borg.trikeshed.dht.agent.Agent<BigInteger, borg.trikeshed.dht.agent.WorldNetwork>

class WorldRouter(agentNUID: borg.trikeshed.dht.id.NUID<BigInteger>) : borg.trikeshed.dht.routing.RoutingTable<BigInteger, borg.trikeshed.dht.agent.WorldNetwork>(agentNUID)


object WorldNetwork : borg.trikeshed.dht.net.NetMask<BigInteger> {
    override val bits: Int
        get() = 128
}
