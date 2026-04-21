package borg.trikeshed.dht.agent


import borg.trikeshed.dht.id.NUID
import borg.trikeshed.dht.net.NetMask
import borg.trikeshed.dht.routing.RoutingTable
import borg.trikeshed.num.BigInt as BigInteger

interface Agent<TNum : Comparable<TNum>, Sz : borg.trikeshed.dht.net.NetMask<TNum>> {
    /**
     * Network Unique Id
     */
    val NUID: borg.trikeshed.dht.id.NUID<TNum>
    val routingTable: borg.trikeshed.dht.routing.RoutingTable<TNum, Sz>
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
