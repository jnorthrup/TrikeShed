package borg.trikeshed.classfile.slab

import borg.trikeshed.context.BitMasked
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j

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
value class SlabFacet(override val mask: Long) : BitMasked<Long> {
    infix fun or(other: SlabFacet) = SlabFacet(mask or other.mask)
    infix fun and(other: SlabFacet) = SlabFacet(mask and other.mask)
    fun has(facet: SlabFacet): Boolean = (mask and facet.mask) != 0L
}

enum class SlabFacetFlag(override val mask: Long) : BitMasked<Long> {
    NONE(0L),
    HOT(1L shl 0),
    COLD(1L shl 1),
    IMMUTABLE(1L shl 2),
    DEDUP_CANDIDATE(1L shl 3),
    COMPRESSED_ZSTD(1L shl 4),
    S3_TIERED(1L shl 5),
    SNAPSHOT_ANCHOR(1L shl 6),
    WAL_ACTIVE(1L shl 7),
    PERSISTENT(1L shl 8),
    WAL_BUFFER(1L shl 9),
    COLUMNAR_EXPORT(1L shl 10),
    EPHEMERAL(1L shl 11),
    COMPUTED(1L shl 12),
    INDEXED(1L shl 13);

    val facet: SlabFacet get() = SlabFacet(mask)
}

/** Slab cursor: indexed composition of slab extents */
typealias SlabCursor = Series<SlabExtent>

/** Logical slab view: projection over physical extents with predicate */
typealias SlabView = SlabCursor  // lazy view — materialize via explicit map

/** RecordMeta = column metadata supplier */
typealias RecordMeta = () -> ColumnMeta

data class ColumnMeta(
    val name: String,
    val typeId: Int,
    val nullable: Boolean,
    val isPrimaryKey: Boolean = false,
    val isIndexed: Boolean = false
)

/** Faceted Cursor: RowVec + facet per row for LCNC bridge */
typealias FacetedRowVec = Join<Any, Join<RecordMeta, SlabFacet>>
typealias FacetedCursor = Series<FacetedRowVec>

/** Empty faceted cursor for test stubs */
val EMPTY_FACETED_CURSOR: FacetedCursor = 0 j { throw IndexOutOfBoundsException() }
