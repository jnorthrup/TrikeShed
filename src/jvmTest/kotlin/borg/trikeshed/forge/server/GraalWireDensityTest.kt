package borg.trikeshed.forge.server

import borg.trikeshed.cas.LineCas
import borg.trikeshed.cas.LineCasIndex
import borg.trikeshed.cas.LineSpine
import borg.trikeshed.collections.LineAperture
import borg.trikeshed.job.CasStore
import borg.trikeshed.lib.size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * R1 gate — per-ring density shading: `residualDensity(probe, aperture)` serialized through
 * [GraalWire.densityMap].
 *
 * Builds a real [LineCasIndex] from two spines, runs the regional top-k at every aperture,
 * and asserts the serializer walks the `Series<Join<Int, Series<Join<ContentId, OverlapCounts>>>>`
 * algebra: region count matches the aperture's band count, totals equal the sum of their rows,
 * and every row carries a 64-hex cid.
 */
class GraalWireDensityTest {

    private fun wire(): GraalWire = GraalWire(
        vitals = borg.trikeshed.graal.vitals.JvmVitals(),
        couchStore = null,
        report = null,
        scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob()),
    )

    @Test
    fun residualDensityProducesRegionsPerAperture() {
        val cas = CasStore.inMemory()
        val spineA: LineSpine = LineCas.spineInto(cas, "val x = 1\nfun f() {\n    return x\n}\n".repeat(8))
        LineCas.spineInto(cas, "fun g(y: Int): Int {\n    val x = y\n    return x * 2\n}\n".repeat(8))
        val idx = LineCasIndex()
        idx.ingestSpine(spineA)
        idx.ingestSpine(LineCas.spineInto(cas, "fun g(y: Int): Int {\n    val x = y\n    return x * 2\n}\n".repeat(8)))
        assertEquals(2, idx.documentCount, "two docs indexed")
        val w = wire()
        for (ap in LineAperture.entries) {
            val result = idx.residualDensity(spineA, ap)
            val map = w.densityMap(result)
            val regions = map["regions"] as List<*>
            val bands = when (ap) {
                LineAperture.L0 -> 1; LineAperture.L1 -> 4; LineAperture.L2 -> 16; LineAperture.L3 -> 64
            }
            assertTrue(regions.size == minOf(bands, spineA.size), "regions = min(bands, probe.size)")
            val totals = map["totals"] as Map<*, *>
            var l = 0; var p = 0; var c = 0
            for (r in regions) {
                val rm = r as Map<*, *>
                val rows = rm["rows"] as List<*>
                for (row in rows) {
                    val cid = (row as Map<*, *>)["cid"] as String
                    assertEquals(64, cid.length, "cid hex is 64 chars")
                    l += (row["linked"] as Int)
                    p += (row["partial"] as Int)
                    c += (row["contentOnly"] as Int)
                }
            }
            assertEquals(l, totals["linked"], "total linked = sum of rows")
            assertEquals(p, totals["partial"], "total partial = sum of rows")
            assertEquals(c, totals["contentOnly"], "total content = sum of rows")
        }
    }
}
