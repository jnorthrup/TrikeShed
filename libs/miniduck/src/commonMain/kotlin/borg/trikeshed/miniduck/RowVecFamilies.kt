package borg.trikeshed.miniduck

import borg.trikeshed.cursor.RowVec
import borg.trikeshed.cursor.ColumnMeta
import borg.trikeshed.cursor.ColumnMeta as ColumnMetaFactory
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.cursor.joins
import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.lib.Series

// Minimal concrete row types used across tests. These are lightweight data holders
// with helper conversion to the root RowVec type via toRowVec().

// DocRowVec: simple key/value row with optional deferred child loader (MiniRowVec)
class DocRowVec(
    val keys: List<String>,
    val cells: List<Any?>,
    val childProvider: (() -> MutableList<MiniRowVec>)? = null
) {
    val child: List<MiniRowVec>?
        get() = childProvider?.invoke()
    val isShell: Boolean get() = keys.isEmpty() && cells.isEmpty()
    operator fun get(index: Int): Any? = cells.getOrNull(index)
    operator fun get(name: String): Any? = keys.indexOf(name).let { if (it >= 0) cells[it] else null }
}

// ViewRowVec used for CouchDB view rows
class ViewRowVec(
    val id: String?,
    val key: Any?,
    val value: Any?,
    val docLoader: (() -> MiniRowVec)? = null,
) {
    val child: List<MiniRowVec>?
        get() = docLoader?.let { listOf(it.invoke()) }

    operator fun get(index: Int): Any? = when (index) {
        0 -> id
        1 -> key
        2 -> value
        else -> null
    }
}

// BlockRowVec family: mutable builder and sealed representation
sealed class BlockRowVec {
    enum class State { MUTABLE, SEALED }
    abstract val state: State
    abstract val rowCount: Int
    abstract val child: MutableList<RowVec>?

    companion object {
        fun mutable(): MutableBlockRowVec = MutableBlockRowVec(mutableListOf())
    }
}

class MutableBlockRowVec(override val child: MutableList<RowVec>) : BlockRowVec() {
    override val state: BlockRowVec.State = BlockRowVec.State.MUTABLE
    override val rowCount: Int get() = child.size

    fun append(row: Any?) {
        val rv: RowVec = when (row) {
            is borg.trikeshed.lib.Join<*,*> -> row as RowVec
            is DocRowVec -> row.toRowVec()
            is ViewRowVec -> row.toRowVec()
            is BlockRowVec -> DocRowVec(listOf("block"), listOf(row.toString())).toRowVec()
            else -> DocRowVec(emptyList(), listOf(row)).toRowVec()
        }
        child.add(rv)
    }

    fun seal(): BlockRowVec = SealedBlockRowVec(child.toList())
}

class SealedBlockRowVec(private val rows: List<RowVec>) : BlockRowVec() {
    override val state: BlockRowVec.State = BlockRowVec.State.SEALED
    override val rowCount: Int get() = rows.size
    override val child: MutableList<RowVec>? = rows.toMutableList()
}

// Top-level helper to match existing imports (`borg.trikeshed.miniduck.mutable`)
fun mutable(): MutableBlockRowVec = MutableBlockRowVec(mutableListOf())

// Convert DocRowVec to the root RowVec type used across the codebase.
fun DocRowVec.toRowVec(): RowVec {
    val values: Series<Any?> = cells.size j { index: Int -> cells[index] }
    val meta: Series<() -> ColumnMeta> = keys.size j { index: Int -> { ColumnMetaFactory(keys[index], IOMemento.IoString) } }
    return values.joins(meta)
}

fun ViewRowVec.toRowVec(): RowVec {
    val vals = arrayOf<Any?>(id, key, value)
    val values: Series<Any?> = vals.size j { index: Int -> vals[index] }
    val meta: Series<() -> ColumnMeta> = vals.size j { index: Int -> { ColumnMetaFactory("col$index", IOMemento.IoString) } }
    return values.joins(meta)
}

// Utility extension used by tests
fun MutableList<DocRowVec>.toJson(): String = this.joinToString(separator = "\n") { doc ->
    val pairs = doc.keys.mapIndexed { i, k -> "\"$k\": ${when (val v = doc.cells[i]) {
        is String -> "\"$v\""
        else -> v.toString()
    }}" }
    "{${pairs.joinToString(",")}}"
}
