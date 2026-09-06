package borg.trikeshed.graal.vitals

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AllocationSitesTest {
    private val frame = AllocationFrame("java.lang.Integer", "valueOf", "(I)Ljava/lang/Integer;", 0, 1, "Inlined", "bootstrap", 0)

    @Test
    fun sumsRepeatedStacksExpiresOldSamplesAndPreservesDescriptorAndLoader() {
        val sites = AllocationSites()
        sites.record("java.lang.Integer", 100, listOf(frame), false, 100_000)
        sites.record("java.lang.Integer", 200, listOf(frame), false, 101_000)
        sites.record("java.lang.Integer", 50, listOf(frame.copy(loaderId = 2)), false, 101_000)
        val snapshot = sites.snapshot("java.lang.Integer", 110_000)
        assertEquals(350L, snapshot["bytes"])
        val rows = snapshot["sites"] as List<*>
        assertEquals(2, rows.size)
        val id = (rows[0] as Map<*, *>)["id"] as String
        assertEquals(frame, sites.frame(id, 0, 110_000))
        assertNull(sites.frame(id, -1, 110_000))
        assertEquals(250L, sites.snapshot("java.lang.Integer", 130_000)["bytes"])
        assertEquals(0L, sites.snapshot("java.lang.Integer", 131_000)["bytes"])
        assertNull(sites.frame(id, 0, 131_000))
    }

    @Test
    fun capacityLossMissingStackAndDepthLimitAreExplicit() {
        val sites = AllocationSites(2)
        sites.record("A", 10, emptyList(), false, 100_000)
        sites.record("B", 20, List(40) { frame }, false, 100_000)
        sites.record("C", 30, listOf(frame), false, 100_000)
        val snapshot = sites.snapshot(null, 100_000)
        assertEquals(30L, snapshot["bytes"])
        assertEquals(30L, snapshot["omittedBytes"])
        assertEquals(1L, snapshot["omittedSamples"])
        val rows = snapshot["sites"] as List<*>
        val first = rows.first() as Map<*, *>
        assertEquals(true, first["truncated"])
        assertEquals(24, (first["frames"] as List<*>).size)
        assertTrue(((rows.last() as Map<*, *>)["frames"] as List<*>).isEmpty())
        sites.record("C", 40, listOf(frame), false, 131_000)
        assertEquals(40L, sites.snapshot(null, 131_000)["bytes"])
    }
}
