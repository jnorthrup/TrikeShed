package io.trikeshed.cursor.slab

import io.trikeshed.kernel.Join
import io.trikeshed.kernel.Series
import io.trikeshed.kernel.Twin
import io.trikeshed.kernel.j
import io.trikeshed.kernel.α

/**
 * Slab = the atomic unit of analytical storage, mapped to kernel algebra.
 *
 * btrfs: subvolume/extent range → miniduck: Parquet row group / table partition
 * Both collapse to: Series<Join<Offset, Length>> with metadata facet
 */
typealias SlabId = Long
typealias SlabOffset = Long
typealias SlabLength = Long

/** Physical slab descriptor: [offset, length] + facet tags */
typealias SlabExtent = Join<SlabOffset, Join<SlabLength, SlabFacet>>

/** Facet = runtime intent tag (not domain) — e.g., HOT, COLD, IMMUTABLE, DEDUP_CANDIDATE */
@JvmInline
value class SlabFacet(private val bits: Long) : Comparable<SlabFacet> {
    companion object {
        val NONE = SlabFacet(0L)
        val HOT = SlabFacet(1L shl 0)
        val COLD = SlabFacet(1L shl 1)
        val IMMUTABLE = SlabFacet(1L shl 2)
        val DEDUP_CANDIDATE = SlabFacet(1L shl 3)
        val COMPRESSED_ZSTD = SlabFacet(1L shl 4)
        val S3_TIERED = SlabFacet(1L shl 5)
        val SNAPSHOT_ANCHOR = SlabFacet(1L shl 6)
        val WAL_ACTIVE = SlabFacet(1L shl 7)
        inline operator fun invoke(bits: Long) = SlabFacet(bits)
    }
    operator fun or(other: SlabFacet) = SlabFacet(bits or other.bits)
    operator fun and(other: SlabFacet) = SlabFacet(bits and other.bits)
    override fun compareTo(other: SlabFacet) = bits.compareTo(other.bits)
}

/** Slab cursor: indexed composition of slab extents */
typealias SlabCursor = Series<SlabExtent>

/** Logical slab view: projection over physical extents with predicate */
typealias SlabView = SlabCursor.α { it }  // lazy map, materializes on demand

/** Faceted Cursor: RowVec + facet per row for LCNC bridge */
typealias FacetedRowVec = Join<Any, Join<() -> RecordMeta, SlabFacet>>
typealias FacetedCursor = Series<FacetedRowVec>

/** RecordMeta from kernel — supplier for column metadata */
typealias RecordMeta = io.trikeshed.kernel.RecordMeta