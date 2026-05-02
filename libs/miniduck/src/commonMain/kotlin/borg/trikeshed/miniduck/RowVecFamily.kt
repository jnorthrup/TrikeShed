package borg.trikeshed.miniduck

import borg.trikeshed.cursor.RowVec
import borg.trikeshed.cursor.cellsToRowVec
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
 * Create a DocRowVec from keys and cells lists.
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
): RowVec {
    val keys = listOf("id", "key", "value")
    val cells = listOf(id, key, value)
    return cellsToRowVec(
        cells = cells.size j { cells[it] },
        keys = keys.size j { keys[it] },
    )
}

/**
 * Create a BlobRowVec from a ByteArray.
 */
fun BlobRowVec(bytes: ByteArray, mimeType: String = "application/octet-stream"): RowVec {
    val keys = listOf("bytes", "mimeType")
    val cells = listOf(bytes, mimeType)
    return cellsToRowVec(
        cells = cells.size j { cells[it] },
        keys = keys.size j { keys[it] },
    )
}

/**
 * Create a JsonRowVec from node type and raw value.
 */
fun JsonRowVec(nodeType: String, rawValue: String): RowVec {
    val keys = listOf("nodeType", "rawValue")
    val cells = listOf(nodeType, rawValue)
    return cellsToRowVec(
        cells = cells.size j { cells[it] },
        keys = keys.size j { keys[it] },
    )
}

/**
 * Create a YamlRowVec from tag and value.
 */
fun YamlRowVec(tag: String, value: String?): RowVec {
    val keys = listOf("tag", "value")
    val cells = listOf(tag, value)
    return cellsToRowVec(
        cells = cells.size j { cells[it] },
        keys = keys.size j { keys[it] },
    )
}

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
        // Return a minimal RowVec representing the sealed block (zero-width, row-count rows)
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

/**
 * The kind of relation being referenced.
 */
enum class RelationKind {
    DOCS,
    VIEW,
    INDEX,
    LOCAL,
}

/**
 * A reference to a database relation (table, view, etc.).
 */
data class RelationRef(
    val database: String,
    val name: String,
    val kind: RelationKind,
)

/**
 * Base interface for all query plans.
 */
interface QueryPlan {
    val source: RelationRef
}

/**
 * A query plan for CouchDB view queries.
 */
data class ViewQueryPlan(
    override val source: RelationRef,
    val designDocument: String = "",
    val viewName: String = "",
    val parameters: Map<String, String> = emptyMap(),
) : QueryPlan

/**
 * Add or replace a query parameter, returning a new ViewQueryPlan.
 */
fun ViewQueryPlan.withParameter(key: String, value: Any?): ViewQueryPlan =
    copy(parameters = parameters + (key to value.toString()))

/** Stub exception for compile */
class NotImplementedError(msg: String) : Error(msg)
