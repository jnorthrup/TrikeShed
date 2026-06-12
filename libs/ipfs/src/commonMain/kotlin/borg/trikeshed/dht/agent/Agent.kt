package borg.trikeshed.dht.agent

import borg.trikeshed.dht.id.NUID
import borg.trikeshed.dht.net.NetMask
import borg.trikeshed.dht.routing.RoutingTable
import java.math.BigInteger as JBigInteger

interface Agent<TNum : Comparable<TNum>, Sz : NetMask<TNum>> {
    val NUID: NUID<TNum>
    val routingTable: RoutingTable<TNum, Sz>
}

class WorldAgent(
    override val NUID: NUID<BigInteger>,
    override val routingTable: RoutingTable<BigInteger, WorldNetwork>,
) : Agent<BigInteger, WorldNetwork>

class WorldRouter(agentNUID: NUID<BigInteger>) : RoutingTable<BigInteger, WorldNetwork>(agentNUID)

object WorldNetwork : NetMask<BigInteger> {
    override val bits: Int = 128
}

typealias BigInteger = JBigInteger