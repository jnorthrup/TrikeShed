package borg.trikeshed.miniduck

import borg.trikeshed.cursor.cellsToRowVec
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.lib.j
import borg.trikeshed.lib.Series

// Minimal, canonical factory shims for MiniDuck RowVec families exposed from root project.
// These are intentionally small and delegate to the cursor package for underlying representation.

/** BlockRowVec - alias to the cursor RowVec used for block storage */
typealias BlockRowVec = RowVec

/** DocRowVec - alias for document-style RowVec */
typealias DocRowVec = RowVec

/** ViewRowVec - alias for view query result rows */
typealias ViewRowVec = RowVec

/** BlobRowVec - alias for blob rows */
typealias BlobRowVec = RowVec

/** JsonRowVec - alias for JSON row */
typealias JsonRowVec = RowVec

/** YamlRowVec - alias for YAML row */
typealias YamlRowVec = RowVec

/** Create a DocRowVec from keys and cells Series. */
fun DocRowVec(keys: Series<String>, cells: Series<Any?>): RowVec {
    require(keys.a == cells.a) { "Keys and cells must have same size" }
    return cellsToRowVec(cells = cells, keys = keys)
}

/** Create a DocRowVec from column name-value pairs. */
fun DocRowVec(vararg columns: Pair<String, Any?>): RowVec = cellsToRowVec(
    cells = columns.size j { columns[it].second },
    keys = columns.size j { columns[it].first },
)

/** Create a DocRowVec from Lists */
fun DocRowVec(keys: List<String>, cells: List<Any?>): RowVec {
    require(keys.size == cells.size) { "Keys and cells must have same size" }
    return cellsToRowVec(
        cells = cells.size j { cells[it] },
        keys = keys.size j { keys[it] },
    )
}

fun ViewRowVec(
    id: String = "",
    key: Any? = null,
    value: Any? = null,
    docLoader: (() -> RowVec)? = null,
): RowVec = cellsToRowVec(
    cells = 3 j { arrayOf(id, key, value)[it] },
    keys = 3 j { arrayOf("id", "key", "value")[it] },
)

fun BlobRowVec(bytes: ByteArray, mimeType: String = "application/octet-stream"): RowVec =
    cellsToRowVec(
        cells = 2 j { arrayOf(bytes, mimeType)[it] },
        keys = 2 j { arrayOf("bytes", "mimeType")[it] },
    )

fun JsonRowVec(nodeType: String, rawValue: String): RowVec = cellsToRowVec(
    cells = 2 j { arrayOf(nodeType, rawValue)[it] },
    keys = 2 j { arrayOf("nodeType", "rawValue")[it] },
)

fun YamlRowVec(tag: String, value: String?): RowVec = cellsToRowVec(
    cells = 2 j { arrayOf(tag, value)[it] },
    keys = 2 j { arrayOf("tag", "value")[it] },
)

// Mutable block builder factory — small helper for tests
class BlockBuilder {
    private val rows: MutableList<RowVec> = mutableListOf()
    private var sealed = false
    val size: Int get() = rows.size
    fun append(row: RowVec) { check(!sealed); rows.add(row) }
    fun seal(): RowVec {
        check(!sealed)
        sealed = true
        return cellsToRowVec(cells = rows.size j { null }, keys = rows.size j { "_block" })
    }
}

fun mutable(): BlockBuilder = BlockBuilder()
