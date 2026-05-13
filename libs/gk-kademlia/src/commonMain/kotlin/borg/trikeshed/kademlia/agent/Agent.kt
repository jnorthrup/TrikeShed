package borg.trikeshed.kademlia.agent
import borg.trikeshed.kademlia.id.NUID
import borg.trikeshed.kademlia.net.NetMask
import borg.trikeshed.kademlia.routing.RoutingTable
import borg.trikeshed.num.BigInt
interface Agent<TNum : Comparable<TNum>, Sz : NetMask<TNum>> {
    val NUID: NUID<TNum>
    val routingTable: RoutingTable<TNum, Sz>
}
class WorldAgent(
    override val NUID: NUID<borg.trikeshed.num.BigInt>,
    override val routingTable: RoutingTable<borg.trikeshed.num.BigInt, WorldNetwork>,
) : Agent<borg.trikeshed.num.BigInt, WorldNetwork>
class WorldRouter(agentNUID: NUID<borg.trikeshed.num.BigInt>) : RoutingTable<borg.trikeshed.num.BigInt, WorldNetwork>(agentNUID)
object WorldNetwork : NetMask<borg.trikeshed.num.BigInt> {
    override val bits: Int
        get() = 128
}
