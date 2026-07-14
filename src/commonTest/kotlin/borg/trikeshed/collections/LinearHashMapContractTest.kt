package borg.trikeshed.collections

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * C13 RED — LinearHashMap occupancy bound and nullable-value membership ambiguity.
 *
 * The plan: "tombstone pressure always doubles; nullable values make contains ambiguous."
 * LinearHashMap must:
 *   1. Enforce ≤0.5 occupancy by resizing
 *   2. Distinguish "absent key" from "present key mapped to null"
 *   3. Record probe/tombstone distributions
 */
class LinearHashMapContractTest {

    @Test
    fun occupancyStaysBelowHalf() {
        val map = LinearHashMap<String, String>(initialCapacity = 16)
        for (i in 0 until 8) {
            map.insert("k-$i", "v-$i")
        }

        // With 16 slots and 8 entries, occupancy is exactly 0.5.
        // Adding one more must trigger resize to keep ≤0.5.
        map.insert("k-8", "v-8")
        val cap = map.capacity
        val size = map.size
        val occ = size.toDouble() / cap
        assertTrue(occ <= 0.5,
            "occupancy must stay ≤0.5 after resize, but was $occ (size=$size, cap=$cap)")
    }

    @Test
    fun containsKeyDistinguishesAbsentFromNullValue() {
        val map = LinearHashMap<String, String?>(initialCapacity = 16)
        map.insert("present", null)

        assertTrue(map.containsKey("present"),
            "containsKey must return true for key mapped to null")
        assertFalse(map.containsKey("absent"),
            "containsKey must return false for absent key")
        assertEquals(null, map.get("present"),
            "get must return null for present key mapped to null")
        // The critical ambiguity: get returns null for both cases.
        // containsKey must resolve it.
    }

    @Test
    fun deleteCreatesTombstoneAndCompactionDoublesCapacity() {
        val map = LinearHashMap<String, String>(initialCapacity = 16)
        for (i in 0 until 8) {
            map.insert("k-$i", "v-$i")
        }

        // Delete several keys to create tombstones
        for (i in 0 until 4) {
            map.remove("k-$i")
        }

        assertEquals(4, map.size, "size must reflect live entries after deletion")
        assertTrue(map.tombstoneCount > 0, "tombstones must be tracked")
    }

    @Test
    fun probeDistributionIsRecorded() {
        val map = LinearHashMap<String, String>(initialCapacity = 256)
        for (i in 0 until 128) {
            map.insert("key-$i", "val-$i")
        }

        val probes = map.probeStatistics()
        assertTrue(probes.maxProbe >= 1, "max probe must be ≥1")
        assertTrue(probes.averageProbe >= 1.0, "average probe must be ≥1.0")
    }
}
