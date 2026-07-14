package borg.trikeshed.collections.multiindex

import borg.trikeshed.job.ContentId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * S1 — Exact index contract RED tests.
 *
 * Unique and non-unique exact indexes are distinct contracts.
 */
class ExactIndexContractTest {

    /**
     * Unique index: one value per key. Duplicate key overwrites.
     */
    @Test
    fun uniqueIndexOneValuePerKey() {
        val idx = UniqueExactIndex<String, String>()
        idx.insert("k1", "v1")
        idx.insert("k2", "v2")

        assertEquals("v1", idx.get("k1"))
        assertEquals("v2", idx.get("k2"))
        assertNull(idx.get("k3"))

        // Overwrite
        idx.insert("k1", "v1b")
        assertEquals("v1b", idx.get("k1"))
    }

    /**
     * Non-unique index: multiple values per key.
     */
    @Test
    fun nonUniqueIndexMultipleValuesPerKey() {
        val idx = NonUniqueExactIndex<String, String>()
        idx.insert("k1", "v1")
        idx.insert("k1", "v2")
        idx.insert("k1", "v3")

        val vals = idx.getAll("k1").toList()
        assertEquals(3, vals.size)
        assertTrue(vals.containsAll(listOf("v1", "v2", "v3")))
    }

    /**
     * containsKey distinguishes absent key from present key mapped to null.
     */
    @Test
    fun containsKeyDistinguishesAbsentFromNull() {
        val idx = UniqueExactIndex<String, String?>()
        idx.insert("present", null)

        assertTrue(idx.containsKey("present"), "key mapped to null must be present")
        assertFalse(idx.containsKey("absent"), "absent key must be false")
        assertNull(idx.get("present"), "value must be null but key is present")
    }

    /**
     * Modify/retract removes a value from non-unique index.
     */
    @Test
    fun retractRemovesValueFromNonUniqueIndex() {
        val idx = NonUniqueExactIndex<String, String>()
        idx.insert("k1", "v1")
        idx.insert("k1", "v2")
        idx.insert("k1", "v3")

        idx.retract("k1", "v2")
        val vals = idx.getAll("k1").toList()
        assertEquals(2, vals.size)
        assertFalse(vals.contains("v2"))
    }

    /**
     * Retract removes stale keys from every index (from the plan gate).
     */
    @Test
    fun retractRemovesStaleKeys() {
        val idx = NonUniqueExactIndex<String, String>()
        idx.insert("k1", "v1")
        idx.insert("k2", "v2")

        idx.retract("k1", "v1")
        assertFalse(idx.containsKey("k1"), "stale key must be removed after retracting last value")
    }
}

/**
 * S2 — Hash index algorithm RED tests.
 *
 * LinearHashMap is the mutable exact-index baseline.
 * Elastic and Funnel refer to specific paper algorithms.
 */
class HashIndexAlgorithmTest {

    /**
     * C13: LinearHashMap triangular probing.
     * Probe counts are recorded at occupancy points.
     */
    @Test
    fun linearHashMapProbeDistributionAtOccupancy() {
        val map = LinearHashMap<String, String>(initialCapacity = 256)
        val probeCounts = mutableMapOf<Int, Int>()

        // Insert at 50%, 75%, 90%, 95%, 99% occupancy
        val total = 256
        for (i in 0 until total) {
            map.insert("key-$i", "val-$i")
            val occ = ((i + 1) * 100) / total
            if (occ in setOf(50, 75, 90, 95, 99)) {
                probeCounts[occ] = map.lastProbeCount
            }
        }

        // Probe counts must be finite and recorded.
        assertTrue(probeCounts.containsKey(50), "probe count at 50% must be recorded")
        assertTrue(probeCounts.containsKey(75), "probe count at 75% must be recorded")
        assertTrue(probeCounts.containsKey(90), "probe count at 90% must be recorded")
        assertTrue(probeCounts.containsKey(95), "probe count at 95% must be recorded")
        assertTrue(probeCounts.containsKey(99), "probe count at 99% must be recorded")
        assertTrue(probeCounts.values.all { it >= 0 }, "probe counts must be non-negative")
    }

    /**
     * C13: Elastic Hashing implements the paper algorithm (arXiv:2501.02305).
     * Positive-query probe counts recorded.
     */
    @Test
    fun elasticHashIndexPositiveProbeCounts() {
        val idx = ElasticHashIndex.Builder<Int>(capacity = 256, seed = 42L)
            .insert((0 until 200).toList()) { it.toString() }
            .build()

        val probes = idx.probeStatistics()
        assertTrue(probes.positiveProbeCounts.isNotEmpty(),
            "Elastic positive probe counts must be recorded at occupancy points")
    }

    /**
     * C13: Funnel hashing positive/negative probe counts recorded.
     */
    @Test
    fun funnelHashIndexProbeCounts() {
        val idx = FunnelHashIndex.Builder<Int>(capacity = 256, seed = 42L)
            .insert((0 until 200).toList()) { it.toString() }
            .build()

        val probes = idx.probeStatistics()
        assertTrue(probes.positiveProbeCounts.isNotEmpty())
        assertTrue(probes.negativeProbeCounts.isNotEmpty())
    }

    /**
     * Elastic and Funnel layouts are deterministic for same canonical keys, seed, capacity, insertion order.
     */
    @Test
    fun elasticLayoutIsDeterministicAcrossTargets() {
        data class Layout(val capacity: Int, val seed: Long, val fingerprints: List<Int>)

        val idx1 = ElasticHashIndex.Builder<Int>(capacity = 128, seed = 42L)
            .insert((0 until 64).toList()) { it.toString() }
            .build()

        val idx2 = ElasticHashIndex.Builder<Int>(capacity = 128, seed = 42L)
            .insert((0 until 64).toList()) { it.toString() }
            .build()

        assertEquals(idx1.layoutHash, idx2.layoutHash,
            "same canonical keys, seed, capacity, insertion order must produce same layout")
    }
}
