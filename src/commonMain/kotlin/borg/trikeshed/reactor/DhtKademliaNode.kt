package borg.trikeshed.reactor

import borg.trikeshed.dht.net.NetMask
import borg.trikeshed.dht.routing.RoutingTable

class DhtKademliaNode<TNum : Comparable<TNum>, Sz : NetMask<TNum>>(
    private val routingTable: RoutingTable<TNum, Sz>,
    private val subnetToNum: (String) -> TNum,
    private val addressToPeerAddress: (String) -> PeerAddress,
    private val k: Int = 20
) : KademliaNode {
    override suspend fun lookup(subnet: String): List<PeerAddress> {
        val targetNum = subnetToNum(subnet)
        val closestRoutes = routingTable.getClosest(targetNum, k)
        val result = mutableListOf<PeerAddress>()
        for (i in 0 until closestRoutes.size) {
            val route = closestRoutes[i]
            result.add(addressToPeerAddress(route.b))
        }
        return result
    }
}
