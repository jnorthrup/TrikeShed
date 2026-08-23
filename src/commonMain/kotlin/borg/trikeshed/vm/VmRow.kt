package borg.trikeshed.vm

import borg.trikeshed.cursor.ColumnMeta
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.IOMemento
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.lib.j

/** The VM as a dataframe row: what every Forge view already knows how to draw. */
val VM_COLUMNS: List<Pair<String, IOMemento>> = listOf(
    "id" to IOMemento.IoString,
    "facet" to IOMemento.IoString,
    "trust" to IOMemento.IoString,
    "tier" to IOMemento.IoString,
    "phase" to IOMemento.IoString,
    "statements" to IOMemento.IoLong,
    "wallMs" to IOMemento.IoLong,
    "calls" to IOMemento.IoLong,
    "heat" to IOMemento.IoLong,
    "receipts" to IOMemento.IoLong,
)

data class VmRow(
    val id: String,
    val facet: String,
    val trust: String,
    val tier: String,
    /** live | dead | revoked | fenced (process tier) */
    val phase: String,
    val statements: Long,
    val wallMs: Long,
    val calls: Long,
    val heat: Long,
    val receipts: Long,
)

fun VmRow.asRowVec(): RowVec {
    val values = listOf<Any?>(id, facet, trust, tier, phase, statements, wallMs, calls, heat, receipts)
    return VM_COLUMNS.size j { col: Int ->
        values[col] j { ColumnMeta(VM_COLUMNS[col].first, VM_COLUMNS[col].second) }
    }
}

fun List<VmRow>.asCursor(): Cursor = size j { row: Int -> this[row].asRowVec() }
