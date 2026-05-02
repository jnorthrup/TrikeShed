package borg.trikeshed.miniduck

import borg.trikeshed.cursor.RowVec
import borg.trikeshed.cursor.cellsToRowVec
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j

// ── Typealiases ──────────────────────────────────────────────────────────────

/** MiniRowVec - unified contract, exposed from cursor package */
typealias MiniRowVec = RowVec

/** MiniCursor - unified contract, exposed from cursor package */
typealias MiniCursor = borg.trikeshed.cursor.Cursor

/** Cursor - unified contract, exposed from cursor package */
typealias Cursor = borg.trikeshed.cursor.Cursor

/** BlockRowVec - RowVec for block storage (e.g., CouchDB row blocks) */
typealias BlockRowVec = RowVec

/** DocRowVec - RowVec for document rows */
typealias DocRowVec = RowVec

/** ViewRowVec - RowVec for view query results */
typealias ViewRowVec = RowVec

/** JsonRowVec - RowVec for JSON documents */
typealias JsonRowVec = RowVec

/** YamlRowVec - RowVec for YAML documents */
typealias YamlRowVec = RowVec

/** BlobRowVec - RowVec for binary/blob data */
typealias BlobRowVec = RowVec

// ── Factory functions ────────────────────────────────────────────────────────

/**
 * Create a DocRowVec from keys and cells Series.
 */
fun DocRowVec(keys: Series<String>, cells: Series<Any?>): RowVec {
    require(keys.a == cells.a) { "Keys and cells must have same size" }
    return cellsToRowVec(cells = cells, keys = keys)
}

/**
 * Create a DocRowVec from column name-value pairs.
 */
fun DocRowVec(vararg columns: Pair<String, Any?>): RowVec = cellsToRowVec(
    cells = columns.size j { columns[it].second },
    keys = columns.size j { columns[it].first },
)

/**
 * List convenience — wraps into Series internally.
 */
fun DocRowVec(keys: List<String>, cells: List<Any?>): RowVec {
    require(keys.size == cells.size) { "Keys and cells must have same size" }
    return cellsToRowVec(
        cells = cells.size j { cells[it] },
        keys = keys.size j { keys[it] },
    )
}

/**
 * Create a ViewRowVec from view query result components.
 */
fun ViewRowVec(
    id: String = "",
    key: Any? = null,
    value: Any? = null,
    docLoader: (() -> RowVec)? = null,
): RowVec = cellsToRowVec(
    cells = 3 j { arrayOf(id, key, value)[it] },
    keys = 3 j { arrayOf("id", "key", "value")[it] },
)

/**
 * Create a BlobRowVec from a ByteArray.
 */
fun BlobRowVec(bytes: ByteArray, mimeType: String = "application/octet-stream"): RowVec =
    cellsToRowVec(
        cells = 2 j { arrayOf(bytes, mimeType)[it] },
        keys = 2 j { arrayOf("bytes", "mimeType")[it] },
    )

/**
 * Create a JsonRowVec from node type and raw value.
 */
fun JsonRowVec(nodeType: String, rawValue: String): RowVec = cellsToRowVec(
    cells = 2 j { arrayOf(nodeType, rawValue)[it] },
    keys = 2 j { arrayOf("nodeType", "rawValue")[it] },
)

/**
 * Create a YamlRowVec from tag and value.
 */
fun YamlRowVec(tag: String, value: String?): RowVec = cellsToRowVec(
    cells = 2 j { arrayOf(tag, value)[it] },
    keys = 2 j { arrayOf("tag", "value")[it] },
)

// ── Extension functions ──────────────────────────────────────────────────────

/**
 * Identity conversion — DocRowVec/RowVec is already a RowVec.
 */
fun RowVec.toRowVec(): RowVec = this

/**
 * getValue is available via import borg.trikeshed.cursor.getValue
 */

// ── Block builder ────────────────────────────────────────────────────────────

/**
 * A mutable builder for accumulating RowVec rows and sealing them into an immutable BlockRowVec.
 */
class BlockBuilder {
    private val rows: MutableList<RowVec> = mutableListOf()
    private var sealed: Boolean = false

    val size: Int get() = rows.size

    fun append(row: RowVec) {
        check(!sealed) { "Cannot append to sealed block" }
        rows.add(row)
    }

    fun seal(): RowVec {
        check(!sealed) { "Already sealed" }
        sealed = true
        return cellsToRowVec(
            cells = rows.size j { null },
            keys = rows.size j { "_block" },
        )
    }
}

/**
 * Create a mutable block builder.
 */
fun mutable(): BlockBuilder = BlockBuilder()

// ── Query Plan types ─────────────────────────────────────────────────────────

enum class RelationKind {
    DOCS,
    VIEW,
    INDEX,
    LOCAL,
}

data class RelationRef(
    val database: String,
    val name: String,
    val kind: RelationKind,
)

interface QueryPlan {
    val source: RelationRef
}

data class ViewQueryPlan(
    override val source: RelationRef,
    val designDocument: String = "",
    val viewName: String = "",
    val parameters: Map<String, String> = emptyMap(),
) : QueryPlan

fun ViewQueryPlan.withParameter(key: String, value: Any?): ViewQueryPlan =
    copy(parameters = parameters + (key to value.toString()))
