package borg.trikeshed.lcnc

import kotlin.test.*

class OroborosMonitorTest {
    @Test
    fun overloadCountsEveryObservationWithoutClaimingEveryDetail() {
        var now = 0L
        val monitor = OroborosMonitor(capacity = 4, binCapacity = 2, eventsPerBin = 2, clock = { now })
        repeat(10) { n ->
            monitor.observe("outer", "write", "commit", mapOf("revision" to n), mapOf("revision" to n + 1), persistent = true)
        }
        val burst = monitor.snapshot()
        assertEquals("spiking", burst["mode"])
        assertEquals(10L, burst["observed"])
        assertEquals(10L, burst["persistent"])
        assertEquals(8L, burst["aggregated"])
        assertEquals(2, (burst["events"] as List<*>).size)
        assertEquals(10L, ((burst["bins"] as List<*>).single() as Map<*, *>)["count"])
        assertEquals(false, burst["complete"])

        now = 250
        monitor.observe("outer", "write", "commit")
        assertEquals("logging", monitor.snapshot()["mode"])
        assertEquals(8L, monitor.snapshot()["aggregated"])
    }

    @Test
    fun retentionAndClockExpiryPreserveLossCounts() {
        var now = 0L
        val monitor = OroborosMonitor(capacity = 2, binCapacity = 2, eventsPerBin = 10, clock = { now })
        repeat(5) { n ->
            now = n * 250L
            monitor.observe("", "n$n", "end")
        }
        val state = monitor.snapshot()
        assertEquals(3L, state["overwritten"])
        assertEquals(0L, state["aggregated"])
        assertEquals(5L, state["observed"])
        assertEquals(2, (state["bins"] as List<*>).size)
        val events = state["events"] as List<*>
        assertEquals(listOf("n3", "n4"), events.map { (it as Map<*, *>)["pointcut"] })
        now = 2000L
        assertEquals(0, (monitor.snapshot()["bins"] as List<*>).size)
        assertEquals(5L, monitor.snapshot()["observed"])
    }

    @Test
    fun attachmentUsesScopeSegmentsAndPointcutIdentity() {
        val monitor = OroborosMonitor(clock = { 0L })
        monitor.attach(scope = "a", pointcut = "write")
        monitor.observe("ab", "write", "end")
        monitor.observe("a", "read", "end")
        monitor.observe("a/nested", "write", "end")
        monitor.observe("a", "write", "end")
        assertEquals(2L, monitor.snapshot()["observed"])
        monitor.detach()
        monitor.observe("a", "write", "end")
        assertEquals(2L, monitor.snapshot()["observed"])
        assertEquals(false, monitor.snapshot()["attached"])
    }

    @Test
    fun spikingRetainsOneRepresentativePerBin() {
        var now = 0L
        val monitor = OroborosMonitor(clock = { now })
        monitor.attach(spiking = true)
        repeat(8) { monitor.observe("scope", "pointcut", "end") }
        now = 250
        repeat(8) { monitor.observe("scope", "pointcut", "end") }
        val state = monitor.snapshot()
        assertEquals("spiking", state["mode"])
        assertEquals(16L, state["observed"])
        assertEquals(14L, state["aggregated"])
        assertEquals(2, (state["events"] as List<*>).size)
    }

    @Test
    fun snapshotsOwnBoundedValuesWithoutCallingOpaqueToString() {
        val monitor = OroborosMonitor(clock = { 0L })
        val nested = mutableMapOf<String, Any?>("value" to 1)
        val opaque = object {
            override fun toString(): String = error("monitor must not invoke guest object rendering")
        }
        monitor.observe("", "pointcut", "end", after = mapOf(
            "nested" to nested,
            "large" to "x".repeat(20000),
            "opaque" to opaque,
        ))
        val snapshot = monitor.snapshot()
        nested["value"] = 2
        val event = (snapshot["events"] as List<*>).single() as Map<*, *>
        val after = event["after"] as Map<*, *>
        assertTrue(after["nested"].toString().contains("1"))
        assertFalse(after["nested"].toString().contains("2"))
        assertTrue(after["large"].toString().length <= OroborosMonitor.VALUE_CHARS)
        assertEquals(true, event["truncated"])
        assertEquals(1L, snapshot["valueTruncations"])
        monitor.observe("", "later", "end")
        assertEquals(1, (snapshot["events"] as List<*>).size)
    }

    @Test
    fun absentAndNullRemainDifferentMutationEffects() {
        val monitor = OroborosMonitor(clock = { 0L })
        monitor.observe("scope", "put", "mutation", emptyMap(), mapOf("value" to null), persistent = true)
        val event = (monitor.snapshot()["events"] as List<*>).single() as Map<*, *>
        assertFalse((event["before"] as Map<*, *>).containsKey("value"))
        assertTrue((event["after"] as Map<*, *>).containsKey("value"))
        assertEquals(null, (event["after"] as Map<*, *>)["value"])
    }
}
