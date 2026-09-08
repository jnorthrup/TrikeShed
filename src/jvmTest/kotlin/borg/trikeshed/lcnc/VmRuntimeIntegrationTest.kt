package borg.trikeshed.lcnc

import borg.trikeshed.forge.server.VmWire
import borg.trikeshed.graal.subvm.CamelRuntime
import borg.trikeshed.parse.json.JsonSupport
import borg.trikeshed.vm.HypervisorVmHost
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VmRuntimeIntegrationTest {
    @Test
    fun lcncAndVmWireOperateOnTheSameRealHostRegistry() = runBlocking {
        val host = HypervisorVmHost()
        try {
            val id = "lcnc-${UUID.randomUUID()}"
            val wire = VmWire(host, this)
            val runners = VmRuntimeNodes.runners(host)
            val spawn = runners.getValue(VmRuntimeNodes.SPAWN).run(
                LcncNode("spawn", VmRuntimeNodes.SPAWN, mapOf("id" to id, "facet" to "js")), emptyMap())
            assertEquals(id, spawn["vmId"])
            val response = assertNotNull(wire.route("POST", "/api/vm/$id/eval", """{"source":"6*7"}""", null))
            assertEquals(200, response.status, response.body)
            assertEquals(42L, ((JsonSupport.parse(response.body) as Map<*, *>)["value"] as Number).toLong())
            val evaluated = runners.getValue(VmRuntimeNodes.EVAL).run(LcncNode("eval", VmRuntimeNodes.EVAL),
                mapOf("vmId" to id, "source" to "7*9"))
            assertEquals(63L, evaluated["value"])
            val stats = runners.getValue(VmRuntimeNodes.STATS).run(LcncNode("stats", VmRuntimeNodes.STATS), mapOf("vmId" to id))
            assertTrue(((stats["stats"] as Map<*, *>)["evals"] as Number).toLong() >= 2)
            val revoked = assertNotNull(wire.route("POST", "/api/vm/$id/revoke", "{}", null))
            assertEquals(200, revoked.status)
            assertTrue(host.get(id)?.isAlive != true)
        } finally {
            host.close()
        }
    }

    @Test
    fun actualCamelTimerContinuesAfterTheNodeInvocationCloses() = runBlocking {
        val ctx = vmRuntimeTestContext(this)
        val id = "lcnc-camel-${UUID.randomUUID()}"
        val ticks = AtomicInteger()
        @Suppress("DEPRECATION")
        val unsubscribe = ctx.blackboard.subscribe {
            if (ctx.blackboard.get(CamelRouteLegos.exchangeKey(id)) != null) ticks.incrementAndGet()
        }
        try {
            val out = withContext(CamelRouteRegistryKey(HostCamelRouteRegistry)) {
                CamelRouteLegos.up(ctx).run(LcncNode("start", CamelRouteLegos.UP, mapOf(
                    "id" to id, "from" to "timer:lcnc?period=100", "to" to "log:lcnc",
                )), emptyMap())
            }
            assertEquals(true, out["ok"], out["error"].toString())
            val before = ticks.get()
            val deadline = System.nanoTime() + 5_000_000_000L
            while (ticks.get() < before + 2 && System.nanoTime() < deadline) Thread.sleep(25)
            assertTrue(ticks.get() >= before + 2, "route must emit after its LCNC invocation has closed")
            assertTrue(CamelRuntime.isRunning(id))
            val stopped = CamelRouteLegos.down(ctx).run(LcncNode("stop", CamelRouteLegos.DOWN, mapOf("id" to id)), emptyMap())
            assertEquals(listOf(id), stopped["stopped"])
        } finally {
            unsubscribe()
            CamelRuntime.stop(id)
        }
    }
}
