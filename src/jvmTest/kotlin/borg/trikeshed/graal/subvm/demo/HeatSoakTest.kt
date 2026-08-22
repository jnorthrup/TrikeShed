package borg.trikeshed.graal.subvm.demo

import borg.trikeshed.graal.subvm.Hypervisor
import borg.trikeshed.graal.subvm.LeafTrainer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Bounded heat soak (default 24 s; `-Dsubvm.soak.seconds=N` for longer). Asserts the invariants a
 * long-lived daemon needs: no heap/thread/latency drift between the first and last third of each
 * phase, caps hold, hot zones end DELEGATED, no refutations, and every sub-VM thread is gone after close.
 */
class HeatSoakTest {
    @Test fun hotZonesStaySteadyAtTemperature() {
        val seconds = System.getProperty("subvm.soak.seconds")?.toIntOrNull() ?: 24
        val report = HeatSoak.run(seconds = seconds, isolates = 4, hotZones = 3)
        println(report.text)
        assertEquals(3, report.hotZones.size)
        assertTrue(report.samples.size >= seconds - 1, "one sample per second: ${report.samples.size}")
        assertTrue(report.samples.last().receipts <= Hypervisor.RECEIPT_LOG_CAP)
        assertTrue(report.samples.last().memoMax <= LeafTrainer.MEMO_CAP)
        assertTrue(report.heatmap.any { it.phase == "DELEGATED" }, report.text)
        assertTrue(report.heatmap.filter { it.zone.endsWith("/impure") }.all { it.phase == "OBSERVED" }, "impure roots never promote: ${report.heatmap}")
        assertEquals(emptyList(), report.findings, report.text)
        // after close nothing of the sub-VM survives
        Thread.sleep(500)
        assertEquals(0, HeatSoak.subVmThreads(), Thread.getAllStackTraces().keys.map { it.name }.filter { it.startsWith("leaf-host") || it.startsWith("subvm-") }.toString())
    }
}
