package borg.trikeshed.cursor

import borg.trikeshed.lib.*

// ── Type Memento ────────────────────────────────────────────────

/**
 * TypeMemento — the type evidence carried by column metadata.
 * IOMemento enum dispatch is the sealed hierarchy for wire format.
 */
interface TypeMemento {
    val networkSize: Int?
}

/** Standard IO mementos — fixed-width types enable O(1) random access. */
enum class IOMemento(override val networkSize: Int?) : TypeMemento {
    IoBoolean(1),
    IoInt(4),
    IoLong(8),
    IoFloat(4),
    IoDouble(8),
    IoString(null),     // variable width — requires offset index
    IoLocalDate(10),
    IoInstant(null),
    IoNothing(0),

    /** Container types — fixed networkSize=null (variable width). */
    IoObject(null),     // JSON object / YAML mapping / CBOR map
    IoArray(null),      // JSON array / YAML sequence / CBOR array

    /** Binary — CBOR byte string, zero-copy slice. */
    IoBytes(null),

    // ── Kind dispatch — collapses 8+ cases to 3-way ─────────────
    ;

    val kind: Int get() = when (this) {
        IoObject -> 0; IoArray -> 1; else -> 2
    }
}

// ── Column Metadata ─────────────────────────────────────────────

/** ColumnMeta = Join<CharSequence, Join<TypeMemento, ColumnMeta?>> — name × type × child.
 *
 *  Row 0 of a Cursor is the idempotent meta exemplar: its ColumnMeta↻ suppliers define the
 *  schema shape for all rows by convention. Each row independently retains its own ColumnMeta↻
 *  generator — lazy, not shared — so per-row specialization is possible without breaking the
 *  exemplar contract.
 *
 *  Row length is likewise a singleton derived from row 0 unless functionally plumbed to
 *  another source.
 *
 *  The child slot is the multi-staged serde type resolver. When a column's TypeMemento is
 *  itself a Cursor type, child carries the nested ColumnMeta chain that describes it — making
 *  that cell a sub-document root. This allows every row to become a hierarchy capable of
 *  tracing a JSON document or specializing a taxonomy within the row composition, with depth
 *  determined by the child chain rather than any external schema registry.
 */
/** ColumnMeta = name × type × child. Child enables nested schema descent. */
interface ColumnMeta : Join<CharSequence, Join<TypeMemento, ColumnMeta?>> {
    val name: CharSequence get() = a
    val type: TypeMemento  get() = b.a
    val child: ColumnMeta? get() = b.b

    companion object {
        @Suppress("EXPOSED_TYPEALIAS_EXPANSION")
        operator fun invoke(name: CharSequence, type: TypeMemento, child: ColumnMeta? = null): ColumnMeta =
            Impl(name, type, child)
    }
}

private class Impl(
    override val a: CharSequence,
    type: TypeMemento,
    override val child: ColumnMeta?,
) : ColumnMeta {
    override val b: Join<TypeMemento, ColumnMeta?> = type j child
}

/** Lazy column metadata supplier — metadata is part of the algebra, not an afterthought. */
typealias `ColumnMeta↻` = () -> ColumnMeta

// ── RowVec ──────────────────────────────────────────────────────

/**
 * RowVec = Series2<Any?, ColumnMeta↻>
 *
 * Row-shaped value view plus metadata supplier.
 * The cursor's row is a split series: values separated from metadata.
 * This is the columnar storage format.
 */
typealias RowVec = Series2<Any?, `ColumnMeta↻`>

// ── Cursor ──────────────────────────────────────────────────────

/**
 * Cursor = Series<RowVec>
 *
 * Indexed composition of RowVec.
 * The dataframe-shaped specialization of the same Join/Series algebra.
 */
typealias Cursor = Series<RowVec>
