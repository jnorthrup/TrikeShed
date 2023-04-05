@file:OptIn(ExperimentalUnsignedTypes::class)
@file:Suppress("PrivatePropertyName", "LocalVariableName")

package gk.kademlia

import borg.trikeshed.lib.FibonacciReporter
import borg.trikeshed.lib.j
import borg.trikeshed.lib.logDebug
import gk.kademlia.id.WorkerNUID
import gk.kademlia.net.NetMask
import gk.kademlia.routing.RoutingTable
import jdk.internal.jshell.debug.InternalDebugControl.debug
import java.net.URI
import kotlin.test.*
import borg.trikeshed.lib.j as t2


class RouteTableTest {
    private val nuid: WorkerNUID = WorkerNUID(0)
    private val nuid1: WorkerNUID = WorkerNUID(with(nuid) { ops.xor(nuid.capacity, id!!) })
    private val d_ones: ByteArray = ByteArray(nuid.netmask.bits) { with(nuid.ops) { shl(one, it) } }
    private val upper: List<Byte> = d_ones.drop(1)
    private val d_twos: ByteArray = ByteArray(upper.size) { x -> with(nuid.ops) { xor(one, upper[x]) } }
    private val d_twos_point_one: ByteArray =
        ByteArray(upper.size) { x -> with(nuid.ops) { xor(shl(one, x), upper[x]) } }

    @Test
    fun testRouteAdd() {
        val NUID = nuid
        var routingTable = RoutingTable<Byte, NetMask.Companion.WarmSz>(NUID, optimal = true)

        routingTable.addRoute(nuid.run {
            val id1 = random(netmask.bits)
            WorkerNUID(id1) j URI("urn:$id1")
        }).run {
            routingTable.addRoute(nuid1 t2 URI("urn:null"))
            for (dOne in d_ones) routingTable.addRoute(WorkerNUID(dOne) t2 URI("urn:$dOne@net"))
            for (dOne in d_twos) routingTable.addRoute(WorkerNUID(dOne) t2 URI("urn:$dOne@net"))
            for (dOne in d_twos_point_one) routingTable.addRoute(WorkerNUID(dOne) t2 URI("urn:$dOne@net"))

            assertEquals(routingTable.buckets[0].size, 7)
            assertEquals(routingTable.buckets[1].size, 11)
            assertEquals(routingTable.buckets[6].size, 1)

            for (n in 0 until 20000) {

                routingTable.addRoute(WorkerNUID(nuid.run { random() }) t2 URI("urn:$n"))
            }
            logDebug {
                "bits: ${routingTable.agentNUID.netmask.bits} fog: ${null} total: ${routingTable.buckets.sumOf { it.size }} bits/count: ${routingTable.buckets.mapIndexed { x, y -> x.inc() to y.size }}"
            }
        }
        assertEquals(1.shl(routingTable.agentNUID.netmask.bits).dec(), routingTable.buckets.sumOf { it.size })
        var c = 0


        routingTable = object : RoutingTable<Byte, NetMask.Companion.WarmSz>(NUID) {}

        routingTable.addRoute(nuid.run {
            val id1 = random(nuid.netmask.bits)
            WorkerNUID(id1) t2 URI("urn:$id1")
        }).run {
            routingTable.addRoute(nuid.run {
                val id1 = random(netmask.bits)
                WorkerNUID(id1) t2 URI("urn:$id1")
            })

            val ich = nuid.ops.one
            run {
                val linkedSetOf = linkedSetOf(ich).also { it.clear() }

                while (linkedSetOf.size < 3) linkedSetOf.add(nuid.run { random(netmask.bits - 1) })
                linkedSetOf.forEach { routingTable.addRoute(WorkerNUID(it) t2 URI("urn:$it")) }
            }
            run {
                val linkedSetOf = linkedSetOf(ich).also { it.clear() }

                while (linkedSetOf.size < 7) linkedSetOf.add(nuid.run { random(1) })
                linkedSetOf.forEach { routingTable.addRoute(WorkerNUID(it) t2 URI("urn:$it")) }
            }
            debug { }

            assertEquals(7, routingTable.buckets[0].size)
            val fibonacciReporter = FibonacciReporter(20000, "routed")
            for (n in 0 until 20000) {
                fibonacciReporter.report(n)?.let { logDebug { it } }
                routingTable.addRoute(WorkerNUID(nuid.run { random() }) t2 URI("urn:$n"))
            }
            logDebug {
                "bits: ${routingTable.agentNUID.netmask.bits} total: ${routingTable.buckets.sumOf { it.size }} bits/count: ${routingTable.buckets.mapIndexed { x, y -> x.inc() to y.size }}"
            }
        }
        routingTable = object : RoutingTable<Byte, NetMask.Companion.WarmSz>(NUID, true) {}

        routingTable.addRoute(nuid.run {
            val id1 = random(netmask.bits)
            WorkerNUID(id1) t2 URI("urn:$id1")
        })
            .run {
                routingTable.addRoute(nuid.run {
                    val id1 = random(netmask.bits)
                    WorkerNUID(id1) t2 URI("urn:$id1")
                })

                val satu = nuid.ops.one
                run {
                    val linkedSetOf = linkedSetOf(satu).also { it.clear() }

                    while (linkedSetOf.size < 3) linkedSetOf.add(nuid.run { random(netmask.bits - 1) })
                    linkedSetOf.forEach { routingTable.addRoute(WorkerNUID(it) t2 URI("urn:$it")) }
                }
                run {
                    val linkedSetOf = linkedSetOf(satu).also { it.clear() }

                    while (linkedSetOf.size < 7) linkedSetOf.add(nuid.run { random(1) })
                    linkedSetOf.forEach { routingTable.addRoute(WorkerNUID(it) t2 URI("urn:$it")) }
                }
                debug { }

                assertEquals(7, routingTable.buckets[0].size)
                assertEquals(3, routingTable.buckets[5].size)

                val fibonacciReporter = FibonacciReporter(20000, "routed")
                for (n in 0 until 20000) {
                    fibonacciReporter.report(n)
                    routingTable.addRoute(WorkerNUID(nuid.run { random() }) t2 URI("urn:$n"))
                }
                logDebug {
                    "bits: ${routingTable.agentNUID.netmask.bits} fog: ${null} total: ${
                        routingTable.buckets.sumOf { it.size }
                    } bits/count: ${
                        routingTable.buckets.mapIndexed { x, y ->
                            x.inc() to y.size
                        }
                    }"
                }
            }
        routingTable = object : RoutingTable<Byte, NetMask.Companion.WarmSz>(NUID, false) {}

        routingTable.addRoute(nuid.run {
            val id1 = random(netmask.bits)
            WorkerNUID(id1) t2 URI("urn:$id1")
        }).run {
            routingTable.addRoute(nuid.run {
                val id1 = random(netmask.bits)
                WorkerNUID(id1) t2 URI("urn:$id1")
            })

            val ich = nuid.ops.one
            run {
                val linkedSetOf = linkedSetOf(ich).also { it.clear() }
                while (linkedSetOf.size < 3) linkedSetOf.add(nuid.run { random(netmask.bits - 1) })
                linkedSetOf.forEach { routingTable.addRoute(WorkerNUID(it) t2 URI("urn:$it")) }
            }
            run {
                val linkedSetOf = linkedSetOf(ich).also { it.clear() }

                while (linkedSetOf.size < 7) linkedSetOf.add(nuid.run { random(1) })
                linkedSetOf.forEach { routingTable.addRoute(WorkerNUID(it) t2 URI("urn:$it")) }
            }
            debug { }

            assertEquals(7, routingTable.buckets[0].size)
            val fibonacciReporter = FibonacciReporter(20000, "routed")
            for (n in 0 until 20000) {
                fibonacciReporter.report(n)
                routingTable.addRoute(WorkerNUID(nuid.run { random() }) t2 URI("urn:$n"))
            }
            logDebug {
                "bits: ${routingTable.agentNUID.netmask.bits} fog: ${null} total: ${routingTable.buckets.sumOf { it.size }} bits/count: ${routingTable.buckets.mapIndexed { x, y -> x.inc() to y.size }}"
            }
        }
        routingTable = object : RoutingTable<Byte, NetMask.Companion.WarmSz>(NUID) {}

        routingTable.addRoute(nuid.run {
            val id1 = random(netmask.bits)
            WorkerNUID(id1) t2 URI("urn:$id1")
        })

        val ich = nuid.ops.one
        run {
            val linkedSetOf = linkedSetOf(ich).also { it.clear() }

            while (linkedSetOf.size < 3) linkedSetOf.add(nuid.run { random(netmask.bits - 1) })
            linkedSetOf.forEach { routingTable.addRoute(WorkerNUID(it) t2 URI("urn:$it")) }
        }
        run {
            val linkedSetOf = linkedSetOf(ich).also { it.clear() }

            while (linkedSetOf.size < 7) linkedSetOf.add(nuid.run { random(1) })
            linkedSetOf.forEach { routingTable.addRoute(WorkerNUID(it) t2 URI("urn:$it")) }
        }
        debug { }

        assertEquals(routingTable.buckets[0].size, 7)
        val fibonacciReporter = FibonacciReporter(20000, "routed")
        for (n in 0 until 20000) {
            fibonacciReporter.report(n)
            routingTable.addRoute(WorkerNUID(nuid.run { random() }) t2 URI("urn:$n"))
        }
        logDebug {

            "bits: ${routingTable.agentNUID.netmask.bits} fog: ${null} total: ${routingTable.buckets.sumOf { it.size }} bits/count: ${routingTable.buckets.mapIndexed { x, y -> x.inc() to y.size }}"
        }
    }
}