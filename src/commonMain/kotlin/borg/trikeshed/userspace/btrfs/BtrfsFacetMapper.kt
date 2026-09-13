package borg.trikeshed.userspace.btrfs

import borg.trikeshed.classfile.slab.SlabFacet

/**
 * Physical-storage-policy facet mapping for btrfs object classes. Each
 * btrfs-managed artifact carries the runtime intent tag its storage layout
 * promises (PRELOAD slab taxonomy: facet = runtime intent, not domain).
 */
object BtrfsFacetMapper {
    /** CAS blob: content-addressed, never mutated in place. */
    fun forCasBlob(): SlabFacet = SlabFacet.IMMUTABLE

    /** Active WAL: append-only until checkpoint. */
    fun forWal(): SlabFacet = SlabFacet.WAL_ACTIVE

    /** COW tree root: indexed for lookup, persistent across mounts. */
    fun forCowTreeRoot(): SlabFacet = SlabFacet.INDEXED or SlabFacet.PERSISTENT

    /** Checkpoint subvolume: snapshot anchor for restart replay. */
    fun forCheckpoint(): SlabFacet = SlabFacet.SNAPSHOT_ANCHOR

    /** Verified duplicate extent: dedup candidate for background scrub. */
    fun forDedupCandidate(): SlabFacet = SlabFacet.DEDUP_CANDIDATE
}
