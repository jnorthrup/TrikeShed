package gk.kademlia.agent


import gk.kademlia.id.NUID
import gk.kademlia.net.NetMask
import gk.kademlia.routing.RoutingTable
import java.math.BigInteger

interface Agent<TNum : Comparable<TNum>, Sz : NetMask<TNum>> {
    /**
     * Network Unique Id
     */
    val NUID: NUID<TNum>
    val routingTable: RoutingTable<TNum, Sz>
}

class WorldAgent(
    override val NUID: NUID<BigInteger>,
    override val routingTable: RoutingTable<BigInteger, WorldNetwork>,

    ) : Agent<BigInteger, WorldNetwork>

class WorldRouter(agentNUID: NUID<BigInteger>) : RoutingTable<BigInteger, WorldNetwork>(agentNUID)


object WorldNetwork : NetMask<BigInteger> {
    override val bits: Int
        get() = 128
}

/**
 *
 */