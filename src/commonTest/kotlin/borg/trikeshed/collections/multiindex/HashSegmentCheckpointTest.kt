package borg.trikeshed.collections.multiindex

import borg.trikeshed.job.ContentId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * S2 — Hash segment checkpoint RED tests.
 *
 * Elastic/Funnel segment bytes are CAS objects.
 * Checkpoint records algorithm, seed, occupancy, segment CID.
 */
class HashSegmentCheckpointTest {

    @Test
    fun elasticSegmentCheckpointRoundTrip() {
        val builder = ElasticHashIndex.Builder<Int>(capacity = 128, seed = 42L)
            .insert((0 until 64).toList()) { it.toString() }
        val idx = builder.build()
        val manifest = idx.checkpoint()

        assertEquals("elastic", manifest.algorithm)
        assertEquals(42L, manifest.seed)
        assertEquals(128, manifest.capacity)
        assertNotNull(manifest.segmentCid)
        assertTrue(manifest.segmentCid!!.value.startsWith("sha256:"))
    }

    @Test
    fun funnelSegmentCheckpointRoundTrip() {
        val builder = FunnelHashIndex.Builder<Int>(capacity = 128, seed = 99L)
            .insert((0 until 64).toList()) { it.toString() }
        val idx = builder.build()
        val manifest = idx.checkpoint()

        assertEquals("funnel", manifest.algorithm)
        assertEquals(99L, manifest.seed)
    }

    @Test
    fun checkpointDeterministicAcrossRuns() {
        val idx1 = ElasticHashIndex.Builder<Int>(capacity = 128, seed = 42L)
            .insert((0 until 64).toList()) { it.toString() }
            .build()
        val idx2 = ElasticHashIndex.Builder<Int>(capacity = 128, seed = 42L)
            .insert((0 until 64).toList()) { it.toString() }
            .build()

        assertEquals(idx1.checkpoint().segmentCid, idx2.checkpoint().segmentCid,
            "checkpoint must be deterministic")
    }
}
