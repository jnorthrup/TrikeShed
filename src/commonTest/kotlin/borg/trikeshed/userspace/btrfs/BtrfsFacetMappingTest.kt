package borg.trikeshed.userspace.btrfs

import borg.trikeshed.classfile.slab.SlabFacet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * B1 RED — Btrfs facet mapping to SlabFacet.
 *
 * The plan defines specific facet tags for physical storage policy:
 *   CAS blob: IMMUTABLE
 *   active WAL: WAL_ACTIVE
 *   COW tree root: INDEXED | PERSISTENT
 *   checkpoint subvolume: SNAPSHOT_ANCHOR
 *   verified duplicate extents: DEDUP_CANDIDATE
 */
class BtrfsFacetMappingTest {

    @Test
    fun casBlobMappedToImmutable() {
        val facet = BtrfsFacetMapper.forCasBlob()
        assertTrue(facet.has(SlabFacet.IMMUTABLE),
            "CAS blob must be tagged IMMUTABLE")
    }

    @Test
    fun activeWalMappedToWalActive() {
        val facet = BtrfsFacetMapper.forWal()
        assertTrue(facet.has(SlabFacet.WAL_ACTIVE),
            "active WAL must be tagged WAL_ACTIVE")
    }

    @Test
    fun cowTreeRootMappedToIndexedPersistent() {
        val facet = BtrfsFacetMapper.forCowTreeRoot()
        assertTrue(facet.has(SlabFacet.INDEXED),
            "COW tree root must be tagged INDEXED")
        assertTrue(facet.has(SlabFacet.PERSISTENT),
            "COW tree root must be tagged PERSISTENT")
    }

    @Test
    fun checkpointSubvolumeMappedToSnapshotAnchor() {
        val facet = BtrfsFacetMapper.forCheckpoint()
        assertTrue(facet.has(SlabFacet.SNAPSHOT_ANCHOR),
            "checkpoint must be tagged SNAPSHOT_ANCHOR")
    }

    @Test
    fun verifiedDuplicateExtentsMappedToDedupCandidate() {
        val facet = BtrfsFacetMapper.forDedupCandidate()
        assertTrue(facet.has(SlabFacet.DEDUP_CANDIDATE),
            "verified duplicate extents must be tagged DEDUP_CANDIDATE")
    }
}
