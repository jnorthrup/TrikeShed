package borg.trikeshed.kernel

import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.lib.*
import borg.trikeshed.pointcut.*
import kotlinx.coroutines.*
import kotlin.test.*

class GraalKernelPointcutTest {
    @Test fun actualTaskDispatchAndReturnHaveGraalSourceCoordinates() = runBlocking {
        val started = System.currentTimeMillis()
        val board = ConfixBlackboard.empty()
        val adapter = PointcutBlackboardAdapter(board)
        val kernel = KernelElement(coroutineContext, KernelId("reel"))
        try {
            val seat = kernel.seat(KernelId("summary"), KernelCapability.MODEL, parallelism = 2,
                pointcut = GraalKernelPointcut("summary-policy", adapter))
            assertEquals("private result", seat.execute("Summarize changed files") { "private result" })
            assertFailsWith<IllegalArgumentException> {
                seat.execute("Failed summarization") { throw IllegalArgumentException("private prompt") }
            }
        } finally { kernel.drain() }
        val observations = adapter.landings.view.toList()
        assertEquals(4, observations.size)
        assertTrue(observations.all { it.coordinate.timestamp in started..System.currentTimeMillis() })
        assertTrue(observations.all { it.facet == VmFacet.GRAAL_JS && it.sourcePath == "kernel-executive.js" && it.line > 0 })
        assertEquals(listOf("dispatch", "completion", "dispatch", "completion"),
            observations.map { (it.value as Map<*, *>)["phase"] })
        val completion = observations.last().value as Map<*, *>
        assertEquals("FAILURE", completion["outcome"])
        assertEquals("IllegalArgumentException", completion["failure"])
        assertFalse(observations.any { it.value.toString().contains("private") })
        assertTrue(board.keys().any { it.startsWith("pointcut/kernel.reel.summary/") })
        val wire = borg.trikeshed.forge.server.GraalWire(
            borg.trikeshed.graal.vitals.JvmVitals(), null, null, this, pointcuts = adapter)
        val route = observations.last().key
        assertTrue(wire.route("GET", "/api/graal/pointcuts", "", null)!!.body.contains(route))
        val document = wire.route("GET", "/api/graal/doc?id=$route", "", null)!!
        assertEquals(200, document.status)
        assertTrue(document.body.contains("live observation"))
        assertTrue(document.body.contains("kernel-executive.js"))
        assertEquals(200, wire.route("GET", "/api/graal/sheet?id=$route", "", null)!!.status)
    }

    @Test fun graalPredicateRejectsAChildModelEnvelope() {
        val pointcut = GraalKernelPointcut("cellular-policy", PointcutBlackboardAdapter(ConfixBlackboard.empty()))
        try {
            assertFails {
                pointcut.dispatch(KernelTurn(KernelId("child"), KernelId("root"), KernelId("model"), 0,
                    "Denied child task", KernelCapability.MODEL))
            }
        } finally { pointcut.close() }
    }
}
