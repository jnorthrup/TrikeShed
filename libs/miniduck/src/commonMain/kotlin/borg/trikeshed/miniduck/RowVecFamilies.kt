package borg.trikeshed.miniduck

import borg.trikeshed.cursor.ColumnMeta
import borg.trikeshed.cursor.ColumnMeta as ColumnMetaFactory
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.cursor.joins
import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.lib.*

// Minimal concrete row types used across tests. These are lightweight data holders
// with helper conversion to the root RowVec type via toRowVec().

// DocRowVec: simple key/value row with optional deferred or series child (MiniRowVec)
class DocRowVec(
    val keys: Series<String>,
    val cells: Series<Any?>,
    child: Any? = null
) {
    private val childSource: Any? = child
    val child: Series<RowVec>?
        get() = childSeries(childSource)
    val isShell: Boolean get() = keys.isEmpty() && cells.isEmpty()
    operator fun get(index: Int): Any? = cells.getOrNull(index)
    operator fun get(name: String): Any? = keys.view.indexOfFirst { it == name }.let { if (it >= 0) cells[it] else null }
}

// ViewRowVec used for CouchDB view rows
class ViewRowVec(
    val id: String?,
    val key: Any?,
    val value: Any?,
    val docLoader: (() -> RowVec)? = null,
) {
    val child: Series<RowVec>?
        get() = docLoader?.let { 1 j { loaderIndex: Int ->
            require(loaderIndex == 0) { "ViewRowVec child only has one row" }
            it.invoke()
        } }

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
    abstract val child: MutableSeries<RowVec>?

    companion object {
        fun mutable(): MutableBlockRowVec = MutableBlockRowVec(emptySeries<RowVec>().cow)
    }
}

class MutableBlockRowVec(override val child: MutableSeries<RowVec>) : BlockRowVec() {
    override val state: State = State.MUTABLE
    override val rowCount: Int get() = child.size

    fun append(row: Any?) {
        val rv: RowVec = when (row) {
            is Join<*,*> -> row as RowVec
            is DocRowVec -> row.toRowVec()
            is ViewRowVec -> row.toRowVec()
            is BlockRowVec -> DocRowVec(singletonKey("block"), singletonCell(row.toString())).toRowVec()
            else -> DocRowVec(emptySeries<String>(), singletonCell(row)).toRowVec()
        }
        child.add(rv)
    }

    fun seal(): BlockRowVec = SealedBlockRowVec(child α {it})
}

class SealedBlockRowVec(private val rows: Series<RowVec>) : BlockRowVec() {
    override val state: State = State.SEALED
    override val rowCount: Int get() = rows.size
    override val child: MutableSeries<RowVec>? = rows.cow
}

// Top-level helper to match existing imports (`borg.trikeshed.miniduck.mutable`)
fun mutable(): MutableBlockRowVec = MutableBlockRowVec(emptySeries<RowVec>().cow)

// Convert DocRowVec to the root RowVec type used across the codebase.
fun DocRowVec.toRowVec(): RowVec {
    val values: Series<Any?> = cells.size j { index: Int -> cells[index] }
    val meta: Series<() -> ColumnMeta> = keys.size j { index: Int -> { ColumnMetaFactory(keys[index], IOMemento.IoString) } }
    return values.joins(meta)
}

fun ViewRowVec.toRowVec(): RowVec {
    val values: Series<Any?> = 3 j { index: Int ->
        when (index) {
            0 -> id
            1 -> key
            else -> value
        }
    }
    val meta: Series<() -> ColumnMeta> = 3 j { index: Int -> { ColumnMetaFactory("col$index", IOMemento.IoString) } }
    return values.joins(meta)
}

// Utility extension used by tests
fun Series<RowVec>?.toJson(): String {
    val rows = this ?: return ""
    if (rows.isEmpty()) return ""

    val json = StringBuilder()
    for (rowIndex in 0 until rows.size) {
        if (rowIndex > 0) json.append('\n')
        appendRowJson(json, rows[rowIndex])
    }
    return json.toString()
}

@Suppress("UNCHECKED_CAST")
private fun childSeries(source: Any?): Series<RowVec>? = when (source) {
    null -> null
    is Join<*, *> -> if (source.a is Int) source as Series<RowVec> else null
    is List<*> -> source.toSeries() as Series<RowVec>
    is Function0<*> -> childSeries(source.invoke())
    else -> null
}

private fun singletonKey(name: String): Series<String> = 1 j { _: Int -> name }

private fun singletonCell(value: Any?): Series<Any?> = 1 j { _: Int -> value }

private fun appendRowJson(json: StringBuilder, row: RowVec) {
    json.append('{')
    for (columnIndex in 0 until row.size) {
        if (columnIndex > 0) json.append(',')
        val cell = row[columnIndex]
        appendJsonString(json, cell.b().a)
        json.append(": ")
        appendJsonValue(json, cell.a)
    }
    json.append('}')
}

private fun appendJsonValue(json: StringBuilder, value: Any?) {
    when (value) {
        null -> json.append("null")
        is String -> appendJsonString(json, value)
        else -> json.append(value)
    }
}

private fun appendJsonString(json: StringBuilder, value: String) {
    json.append('"')
    for (index in value.indices) {
        when (val ch = value[index]) {
            '\\' -> json.append("\\\\")
            '"' -> json.append("\\\"")
            '\n' -> json.append("\\n")
            '\r' -> json.append("\\r")
            '\t' -> json.append("\\t")
            else -> json.append(ch)
        }
    }
    json.append('"')
}
