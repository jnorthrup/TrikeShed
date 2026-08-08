package borg.trikeshed.collections

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import borg.trikeshed.lib.j
import borg.trikeshed.collections.associative.FunnelHashIndex

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FunnelHashIndexTest {

    @Test fun basicGet() {
        val keys = arrayOf("a", "b", "c", "d", "e").toSeries()
        val idx = FunnelHashIndex.build(keys, 0x1234L)
        assertEquals(0, idx.get("a"))
        assertEquals(1, idx.get("b"))
        assertEquals(4, idx.get("e"))
        assertNull(idx.get("z"))
    }

    @Test fun contains() {
        val keys = arrayOf("a", "b", "c").toSeries()
        val idx = FunnelHashIndex.build(keys, 0x1234L)
        assertTrue(idx.contains("a"))
        assertTrue(idx.contains("b"))
        assertTrue(idx.contains("c"))
        assertTrue(!idx.contains("d"))
    }

    @Test fun deterministic() {
        val keys = arrayOf("a", "b", "c", "d", "e", "f", "g", "h").toSeries()
        val idx1 = FunnelHashIndex.build(keys, 0xdeadbeefL)
        val idx2 = FunnelHashIndex.build(keys, 0xdeadbeefL)
        (0 until keys.size).forEach { idx -> val it = keys[idx]; assertEquals(idx1.get(it), idx2.get(it)) }
        assertEquals(idx1.totalCapacity(), idx2.totalCapacity())
    }

    @Test fun emptyIndex() {
        val idx = FunnelHashIndex.build(emptyArray<String>().toSeries(), 0L)
        assertEquals(0, idx.totalCapacity())
        assertEquals(0, idx.size())
        assertNull(idx.get("anything"))
    }

    @Test fun singleKey() {
        val idx = FunnelHashIndex.build(arrayOf("solo").toSeries(), 0x1234L)
        assertEquals(0, idx.get("solo"))
        assertNull(idx.get("other"))
    }

    @Test fun manyKeys() {
        val keys = (0 until 200).map { "key${it}" }.toTypedArray().toSeries()
        val idx = FunnelHashIndex.build(keys, 0xcafeL)
        (0 until keys.size).forEach { i -> val k = keys[i]; assertEquals(i, idx.get(k)) }
        assertNull(idx.get("missing"))
    }

    @Test fun probeDistribution() {
        val keys = (0 until 50).map { "key${it}" }.toTypedArray().toSeries()
        val idx = FunnelHashIndex.build(keys, 0xcafeL)
        val probes = idx.probeDistribution()
        val probeSize = probes.size
        assertEquals(50, probes.size)
        assertTrue((0 until probeSize).all { probes[it] > 0 })
    }

    @Test fun testProbeDistributionScaling() {
        val n = 100000
        val keys = (0 until n).map { "rand_key_${it.hashCode() * 31}" }.toTypedArray().toSeries()
        val advKeys = (0 until n).map { "adv_${it shl 4}" }.toTypedArray().toSeries()

        val slacks = listOf(0.05, 0.10, 0.20, 0.50)

        println("=== FunnelHashIndex Probe Measurement ===")
        for (d in slacks) {
            val idxUniform = FunnelHashIndex.build(keys, 0x123L, slack = d)
            val uniformProbes = idxUniform.probeDistribution()
            var maxU = 0; for(i in 0 until uniformProbes.size) { if(uniformProbes[i] > maxU) maxU = uniformProbes[i] }
            var sumU = 0.0; for(i in 0 until uniformProbes.size) { sumU += uniformProbes[i] }; val meanU = sumU / uniformProbes.size

            val idxAdv = FunnelHashIndex.build(advKeys, 0x456L, slack = d)
            val advProbes = idxAdv.probeDistribution()
            var maxA = 0; for(i in 0 until advProbes.size) { if(advProbes[i] > maxA) maxA = advProbes[i] }
            var sumA = 0.0; for(i in 0 until advProbes.size) { sumA += advProbes[i] }; val meanA = sumA / advProbes.size

            val targetO = kotlin.math.ln(1.0 / d) * kotlin.math.ln(1.0 / d)

            println("delta=${d} | uniform(max=${maxU}, mean=${meanU}) | adversarial(max=${maxA}, mean=${meanA}) | log^2(1/d)=${targetO}")
        }
    }
}
