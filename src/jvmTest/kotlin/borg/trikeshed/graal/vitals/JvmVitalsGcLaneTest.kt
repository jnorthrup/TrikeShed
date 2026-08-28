package borg.trikeshed.graal.vitals

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * R4 gate — real GC occupancy deltas + allocation-attributed continents.
 *
 * The JDK 25 JFR event shapes are verified against `jfr metadata` on a live recording:
 *  · jdk.GCHeapSummary          — gcId(int), when("Before GC"/"After GC"), heapSpace, heapUsed
 *  · jdk.GCHeapMemoryUsage      — used, committed, max
 *  · jdk.GCHeapMemoryPoolUsage  — name, used, committed, max
 *  · jdk.GCPhasePause           — name, duration
 *  · jdk.ObjectAllocationSample — objectClass, weight
 *
 * The JFR events are hard to force on demand, so the gate drives the RECORDERS — the
 * pure accumulator layer the JFR handlers funnel into — and asserts the snapshot math:
 *
 * 1. GCHeapSummary: before/after matched by gcId → freed bytes = before − after.
 * 2. GCHeapMemoryPoolUsage: per-pool last used + cumulative reclaimed/grown deltas.
 * 3. GCPhasePause: per-phase count + total pause ms.
 * 4. ObjectAllocationSample: per-class sampled bytes, sorted descending, capped.
 * 5. gcLane() folds the sections; snapshot().gc.lane carries it; heapHistogram() carries
 *    the allocation continent as the second terrain source.
 */
class JvmVitalsGcLaneTest {

    @Test
    fun gcHeapSummaryMatchesBeforeAfterByGcId() {
        val v = JvmVitals()
        // gcId 1: before 100, after 20 → freed 80
        v.recordGcHeapSummary(1, "Before GC", 100, 64)
        v.recordGcHeapSummary(1, "After GC", 20, 64)
        // gcId 2: before 120, after 40 → freed 80
        v.recordGcHeapSummary(2, "Before GC", 120, 64)
        v.recordGcHeapSummary(2, "After GC", 40, 64)
        val occ = v.gcHeapOccupancy()
        assertEquals(2, occ["collectionsMatched"], "both gcIds matched")
        assertEquals(160L, occ["totalFreedBytes"], "80 + 80")
        assertEquals(80L, occ["avgFreedBytes"], "(80+80)/2")
        assertEquals(64L, occ["committedBytes"], "last committed size")
    }

    @Test
    fun gcHeapSummaryDropsLateAfterWithNoBefore() {
        val v = JvmVitals()
        // After GC arrives with no matching Before (evicted) → no freed recorded
        v.recordGcHeapSummary(7, "After GC", 20, 64)
        assertEquals(0, v.gcHeapOccupancy()["collectionsMatched"])
        assertEquals(0L, v.gcHeapOccupancy()["totalFreedBytes"])
    }

    @Test
    fun gcPoolUsageTracksPerPoolDeltas() {
        val v = JvmVitals()
        // Eden: 1000 → 200 (reclaimed 800) → 400 (grown 200)
        v.recordGcHeapMemoryPoolUsage("Eden", 1000, 34)
        v.recordGcHeapMemoryPoolUsage("Eden", 200, 34)
        v.recordGcHeapMemoryPoolUsage("Eden", 400, 34)
        // Old: 500 → 600 (grown 100)
        v.recordGcHeapMemoryPoolUsage("Old", 500, 30)
        v.recordGcHeapMemoryPoolUsage("Old", 600, 30)
        val pools = v.gcPoolUsage().associate { it["pool"] as String to it }
        assertEquals(400L, pools["Eden"]!!["lastUsedBytes"])
        assertEquals(800L, pools["Eden"]!!["reclaimedBytes"], "1000→200 reclaimed")
        assertEquals(200L, pools["Eden"]!!["grownBytes"], "200→400 grew")
        assertEquals(3, pools["Eden"]!!["samples"])
        assertEquals(100L, pools["Old"]!!["grownBytes"], "500→600 grew")
        assertEquals(0L, pools["Old"]!!["reclaimedBytes"])
        // sorted by last used desc: Eden (400) before Old (600)? No — Old 600 > Eden 400
        assertEquals("Old", v.gcPoolUsage().first()["pool"])
    }

    @Test
    fun gcPhasesDecomposeThePause() {
        val v = JvmVitals()
        v.recordGcPhasePause("Marking", 5_000_000)
        v.recordGcPhasePause("Marking", 5_000_000)
        v.recordGcPhasePause("Sweeping", 10_000_000)
        val phases = v.gcPhases().associate { it["phase"] as String to it }
        assertEquals(2, phases["Marking"]!!["count"])
        assertEquals(10L, phases["Marking"]!!["pauseMsTotal"], "5ms + 5ms")
        assertEquals(10L, phases["Sweeping"]!!["pauseMsTotal"])
        assertEquals(2, v.gcPhases().size)
    }

    @Test
    fun allocationByClassSortsDescendingAndCaps() {
        val v = JvmVitals()
        v.recordAllocationSample("java.lang.String", 1000)
        v.recordAllocationSample("java.lang.String", 500)   // same class accumulates → 1500
        v.recordAllocationSample("java.lang.Object", 200)
        val rows = v.allocationByClass()
        assertEquals("java.lang.String", rows.first()["class"])
        assertEquals(1500L, rows.first()["bytes"], "per-class sum")
        assertEquals("java.lang.Object", rows.last()["class"])
    }

    @Test
    fun gcLaneFoldsAllSections() {
        val v = JvmVitals()
        v.recordGcHeapSummary(1, "Before GC", 100, 64)
        v.recordGcHeapSummary(1, "After GC", 20, 64)
        v.recordGcHeapMemoryPoolUsage("Eden", 1000, 34)
        v.recordGcPhasePause("Marking", 1_000_000)
        v.recordAllocationSample("X", 10)
        val lane = v.gcLane()
        assertTrue(lane.containsKey("heapOccupancy"))
        assertTrue(lane.containsKey("pools"))
        assertTrue(lane.containsKey("phases"))
        assertTrue(lane.containsKey("allocation"))
        @Suppress("UNCHECKED_CAST")
        val occ = lane["heapOccupancy"] as Map<String, Any?>
        assertEquals(80L, occ["totalFreedBytes"])
    }

    @Test
    fun snapshotCarriesTheGcLaneAndHeapCarriesAllocation() {
        val v = JvmVitals()
        // Gates must never self-attach jcmd: GC.class_histogram stops the whole target JVM
        // at a safepoint and a wedged attach freezes the watchdog thread too. Swap the seam.
        v.liveSetSource = {
            listOf(
                JvmVitals.HeapRow("java.lang.String", 10, 240),
                JvmVitals.HeapRow("byte[]", 5, 1000),
            )
        }
        v.recordGcHeapSummary(1, "Before GC", 100, 64)
        v.recordGcHeapSummary(1, "After GC", 20, 64)
        val snap = v.snapshot()
        @Suppress("UNCHECKED_CAST")
        val gc = snap["gc"] as Map<String, Any?>
        assertTrue(gc.containsKey("lane"), "snapshot.gc.lane is present")
        val heap = v.heapHistogram()
        assertTrue(heap.containsKey("allocation"), "heapHistogram carries the allocation continent")
        assertEquals(2, heap["classes"], "live-set rows come from the seam")
        assertEquals(1240L, heap["bytes"], "240 + 1000")
    }

    @Test
    fun parseClassHistogramParsesJcmdTable() {
        val v = JvmVitals()
        val text = """
             num     #instances         #bytes  class name (module)
            -------------------------------------------------------
               1:          1200          48000  java.lang.String (java.base@25.0.4)
               2:           300          24000  byte[] (java.base@25.0.4)
               3:            50           1600  java.lang.Object (java.base@25.0.4)
            Total          1550          73600
        """.trimIndent()
        val rows = v.parseClassHistogram(text)
        assertEquals(3, rows.size, "Total line is not a row")
        assertEquals("java.lang.String", rows[0].className, "sorted by bytes desc")
        assertEquals(48000L, rows[0].bytes)
        assertEquals(1200L, rows[0].count)
    }
}
