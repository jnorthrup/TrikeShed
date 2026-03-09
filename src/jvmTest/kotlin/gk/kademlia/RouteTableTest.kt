@file:OptIn(ExperimentalUnsignedTypes::class)
@file:Suppress("PrivatePropertyName", "LocalVariableName")

package gk.kademlia

import borg.trikeshed.lib.FibonacciReporter
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.j
import borg.trikeshed.lib.logDebug
import gk.kademlia.id.NUID
import gk.kademlia.id.WorkerNUID
import gk.kademlia.include.Address
import gk.kademlia.routing.RoutingTable
import gk.kademlia.net.NetMask
import kotlin.test.Test
import kotlin.test.assertEquals

class RouteTableTest {

    private val nuid: WorkerNUID = WorkerNUID(0)
    private val nuid1: WorkerNUID = WorkerNUID(with(nuid) { ops.xor(nuid.capacity, id!!) })
    private val d_ones: ByteArray = ByteArray(nuid.netmask.bits) { with(nuid.ops) { shl(one, it) } }
    private val upper: List<Byte> = d_ones.drop(1)
    private val d_twos: ByteArray = ByteArray(upper.size) { x -> with(nuid.ops) { xor(one, upper[x]) } }
    private val d_twos_point_one: ByteArray = ByteArray(upper.size) { x -> with(nuid.ops) { xor(shl(one, x), upper[x]) } }

    @Test
    fun testRouteAdd() {
        val NUID = nuid
        var routingTable = RoutingTable<Byte, NetMask.Companion.WarmSz>(NUID, optimal = true)

        val other: Join<NUID<Byte>, Address> = nuid.run {
            val id1 = random(netmask.bits)
            WorkerNUID(id1) j "urn:$id1"
        }
        routingTable.addRoute(other).run {
            routingTable.addRoute(nuid1 j "urn:null")

            for (dOne in d_ones) routingTable.addRoute(WorkerNUID(dOne) j "urn:$dOne@net")
            for (dOne in d_twos) routingTable.addRoute(WorkerNUID(dOne) j "urn:$dOne@net")
            for (dOne in d_twos_point_one) routingTable.addRoute(WorkerNUID(dOne) j "urn:$dOne@net")

            assertEquals(routingTable.buckets[0].size, 7)
            assertEquals(routingTable.buckets[1].size, 11)
            assertEquals(routingTable.buckets[6].size, 1)

            for (n in 0 until 20000) {
                routingTable.addRoute(WorkerNUID(nuid.run { random() }) j "urn:$n")
            }

            logDebug {
                "bits: ${routingTable.agentNUID.netmask.bits} fog: ${null} total: ${routingTable.buckets.sumOf { it.size }} bits/count: ${routingTable.buckets.mapIndexed { x, y -> x.inc() to y.size }}"
            }
        }

        assertEquals(1.shl(routingTable.agentNUID.netmask.bits).dec(), routingTable.buckets.sumOf { it.size })

        routingTable = object : RoutingTable<Byte, NetMask.Companion.WarmSz>(NUID) {}

        routingTable.addRoute(nuid.run {
            val id1 = random(nuid.netmask.bits)
            WorkerNUID(id1) j "urn:$id1"
        }).run {
            routingTable.addRoute(other)

            val ich = nuid.ops.one

            run {
                val linkedSetOf = linkedSetOf(ich).also { it.clear() }
                while (linkedSetOf.size < 3) linkedSetOf.add(nuid.run { random(netmask.bits - 1) })
                linkedSetOf.forEach { routingTable.addRoute(WorkerNUID(it) j "urn:$it") }
            }

            run {
                val linkedSetOf = linkedSetOf(ich).also { it.clear() }
                while (linkedSetOf.size < 7) linkedSetOf.add(nuid.run { random(1) })
                linkedSetOf.forEach { routingTable.addRoute(WorkerNUID(it) j "urn:$it") }
            }

            assertEquals(7, routingTable.buckets[0].size)

            val fibonacciReporter = FibonacciReporter(20000, "routed")
            for (n in 0 until 20000) {
                fibonacciReporter.report()?.run { ::logDebug }
                routingTable.addRoute(WorkerNUID(nuid.run { random() }) j "urn:$n")
            }

            logDebug {
                "bits: ${routingTable.agentNUID.netmask.bits} total: ${routingTable.buckets.sumOf { it.size }} bits/count: ${routingTable.buckets.mapIndexed { x, y -> x.inc() to y.size }}"
            }
        }

        routingTable = object : RoutingTable<Byte, NetMask.Companion.WarmSz>(NUID, true) {}

        routingTable.addRoute(other).run {
            routingTable.addRoute(other)

            val satu = nuid.ops.one

            run {
                val linkedSetOf = linkedSetOf(satu).also { it.clear() }
                while (linkedSetOf.size < 3) linkedSetOf.add(nuid.run { random(netmask.bits - 1) })
                linkedSetOf.forEach { routingTable.addRoute(WorkerNUID(it) j "urn:$it") }
            }

            run {
                val linkedSetOf = linkedSetOf(satu).also { it.clear() }
                while (linkedSetOf.size < 7) linkedSetOf.add(nuid.run { random(1) })
                linkedSetOf.forEach { routingTable.addRoute(WorkerNUID(it) j "urn:$it") }
            }

            assertEquals(7, routingTable.buckets[0].size)
            assertEquals(3, routingTable.buckets[5].size)

            val fibonacciReporter = FibonacciReporter(20000, "routed")
            for (n in 0 until 20000) {
                fibonacciReporter.report()
                routingTable.addRoute(WorkerNUID(nuid.run { random() }) j "urn:$n")
            }

            logDebug {
                "bits: ${routingTable.agentNUID.netmask.bits} fog: ${null} total: ${routingTable.buckets.sumOf { it.size }} bits/count: ${routingTable.buckets.mapIndexed { x, y -> x.inc() to y.size }}"
            }
        }

        routingTable = object : RoutingTable<Byte, NetMask.Companion.WarmSz>(NUID, false) {}

        routingTable.addRoute(other).run {
            routingTable.addRoute(other)

            val ich = nuid.ops.one

            run {
                val linkedSetOf = linkedSetOf(ich).also { it.clear() }
                while (linkedSetOf.size < 3) linkedSetOf.add(nuid.run { random(netmask.bits - 1) })
                linkedSetOf.forEach { routingTable.addRoute(WorkerNUID(it) j "urn:$it") }
            }

            run {
                val linkedSetOf = linkedSetOf(ich).also { it.clear() }
                while (linkedSetOf.size < 7) linkedSetOf.add(nuid.run { random(1) })
                linkedSetOf.forEach { routingTable.addRoute(WorkerNUID(it) j "urn:$it") }
            }

            assertEquals(7, routingTable.buckets[0].size)

            val fibonacciReporter = FibonacciReporter(20000, "routed")
            for (n in 0 until 20000) {
                fibonacciReporter.report()
                routingTable.addRoute(WorkerNUID(nuid.run { random() }) j "urn:$n")
            }

            logDebug {
                "bits: ${routingTable.agentNUID.netmask.bits} fog: ${null} total: ${routingTable.buckets.sumOf { it.size }} bits/count: ${routingTable.buckets.mapIndexed { x, y -> x.inc() to y.size }}"
            }
        }

        routingTable = object : RoutingTable<Byte, NetMask.Companion.WarmSz>(NUID) {}

        routingTable.addRoute(other)

        val ich = nuid.ops.one

        run {
            val linkedSetOf = linkedSetOf(ich).also { it.clear() }
            while (linkedSetOf.size < 3) linkedSetOf.add(nuid.run { random(netmask.bits - 1) })
            linkedSetOf.forEach { routingTable.addRoute(WorkerNUID(it) j "urn:$it") }
        }

        run {
            val linkedSetOf = linkedSetOf(ich).also { it.clear() }
            while (linkedSetOf.size < 7) linkedSetOf.add(nuid.run { random(1) })
            linkedSetOf.forEach { routingTable.addRoute(WorkerNUID(it) j "urn:$it") }
        }

        assertEquals(routingTable.buckets[0].size, 7)

        val fibonacciReporter = FibonacciReporter(20000, "routed")
        for (n in 0 until 20000) {
            fibonacciReporter.report()
            routingTable.addRoute(WorkerNUID(nuid.run { random() }) j "urn:$n")
        }

        logDebug {
            "bits: ${routingTable.agentNUID.netmask.bits} fog: ${null} total: ${routingTable.buckets.sumOf { it.size }} bits/count: ${routingTable.buckets.mapIndexed { x, y -> x.inc() to y.size }}"
        }
    }
}