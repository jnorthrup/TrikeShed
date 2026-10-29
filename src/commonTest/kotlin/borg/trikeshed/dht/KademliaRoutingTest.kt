package borg.trikeshed.dht

import borg.trikeshed.dht.agent.Agent
import borg.trikeshed.dht.id.WorkerNUID
import borg.trikeshed.dht.id.impl.HexNUID
import borg.trikeshed.dht.id.impl.HexNetMask
import borg.trikeshed.dht.net.NetMask
import borg.trikeshed.dht.routing.RoutingTable
import borg.trikeshed.lib.*
import borg.trikeshed.platform.bitops.impl.ByteBitOps
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class KademliaRoutingTest {
    @Test fun removalUsesTheCappedInsertionBucket() = runTest {
        val mask = HexNetMask(256)
        val table = RoutingTable<String, HexNetMask>(HexNUID("0".repeat(64), mask))
        val route = HexNUID("f".repeat(64), mask) j "farthest"
        table.addRouteSync(route)
        assertEquals(table.bucketCount - 1, table.bucketFor(route.a))
        assertSame(route, table.rmRoute(route))
        assertTrue(table.getClosest("0".repeat(64), 1).isEmpty())
        assertNull(table.rmRoute(route))
    }

    @Test fun numericXorBeatsPopulationCountAndChangesWithTheTarget() {
        val table = RoutingTable<Byte, NetMask.Companion.WarmSz>(WorkerNUID(0))
        table.addRouteSync(WorkerNUID(31) j "lower-many-bits")
        table.addRouteSync(WorkerNUID(64) j "higher-one-bit")
        assertEquals(listOf("lower-many-bits", "higher-one-bit"), table.getClosest(0, 2).map { it.b })
        assertEquals(listOf("higher-one-bit", "lower-many-bits"), table.getClosest(127, 2).map { it.b })
        val signed = object : NetMask<Byte> {
            override val bits = 8
            override val ops = ByteBitOps
        }
        assertTrue(signed.compareDistance(0, 127, -128) < 0)
    }

    @Test fun agentRetainsFullIdentitiesAndItsOwnRouteWithinK() {
        val mask = HexNetMask(256)
        val zero = "0".repeat(64)
        val low = "0".repeat(63) + "1"
        val lowOtherHalf = "0".repeat(31) + "1" + "0".repeat(32)
        val manyBits = "7" + "f".repeat(63)
        val high = "8" + "0".repeat(63)
        val agent = object : Agent<String, HexNetMask> {
            override val NUID = HexNUID(high, mask)
            override val routingTable = RoutingTable<String, HexNetMask>(NUID)
        }
        for (id in listOf(manyBits, lowOtherHalf, low)) agent.routingTable.addRouteSync(HexNUID(id, mask) j id)
        assertEquals(listOf(low, lowOtherHalf, manyBits, high), agent.getClosest(zero, 4, high).map { it.a.id })
        assertEquals(listOf(high), agent.getClosest(high, 1, high).map { it.a.id })
        assertEquals(listOf(low, lowOtherHalf), agent.getClosest(zero, 2, high).map { it.a.id })
        assertTrue(agent.getClosest(zero, 0, high).isEmpty())
    }

    @Test fun hexIdentityOperationsPreserveWidthAndCarry() {
        val mask = HexNetMask(16)
        with(mask.ops) {
            assertEquals("fff0", xor("ffff", "000f"))
            assertEquals("000f", and("ffff", "000f"))
            assertEquals("2340", shl("1234", 4))
            assertEquals("0246", shr("1234", 3))
            assertEquals("0000", shl("ffff", 16))
            assertEquals("0100", plus("00ff", "0001"))
            assertEquals("00ff", minus("0100", "0001"))
            assertEquals("ffff", minus("0000", "0001"))
        }
        assertFailsWith<IllegalArgumentException> { HexNUID("ffff", HexNetMask(256)) }
        assertFailsWith<IllegalArgumentException> { HexNUID("FFFF", mask) }
    }
}
