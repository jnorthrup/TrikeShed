/*
 * Copyright (c) 2024. TrikeShed Open Source
 * This file is part of TrikeShed, licensed under the GNU Affero General Public License version 3.
 * See the LICENSE file for details.
 */
package borg.trikeshed.reactor

import borg.trikeshed.dht.id.impl.IntNUID
import borg.trikeshed.dht.net.NetMask
import borg.trikeshed.dht.routing.RoutingTable
import borg.trikeshed.lib.j
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class DhtKademliaNodeTest {
    @Test
    fun testLookupReturnsClosestPeers() = runTest {
        val nuid = borg.trikeshed.dht.id.NUID.minNUID(31) as borg.trikeshed.dht.id.impl.IntNUID
        nuid.id = 5
        val routingTable = RoutingTable<Int, NetMask<Int>>(nuid, true)

        val peer1Nuid = borg.trikeshed.dht.id.NUID.minNUID(31) as borg.trikeshed.dht.id.impl.IntNUID
        peer1Nuid.id = 10
        val peer2Nuid = borg.trikeshed.dht.id.NUID.minNUID(31) as borg.trikeshed.dht.id.impl.IntNUID
        peer2Nuid.id = 15

        routingTable.addRouteSync(peer1Nuid j "192.168.1.1:8080")
        routingTable.addRouteSync(peer2Nuid j "192.168.1.2:8081")

        val dhtNode = DhtKademliaNode(
            routingTable = routingTable,
            subnetToNum = { it.toIntOrNull() ?: 0 },
            addressToPeerAddress = { addr ->
                val parts = addr.split(":")
                PeerAddress(parts[0], parts[1].toInt())
            },
            k = 2
        )

        val peers = dhtNode.lookup("12")
        assertEquals(2, peers.size)
        // 12 XOR 10 = 6, 12 XOR 15 = 3
        // So 15 is closer than 10.
        // peers[0] should be 192.168.1.2:8081
        // peers[1] should be 192.168.1.1:8080
        assertEquals(PeerAddress("192.168.1.2", 8081), peers[0])
        assertEquals(PeerAddress("192.168.1.1", 8080), peers[1])
    }
}
