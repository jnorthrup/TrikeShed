package borg.trikeshed.miniduck

import borg.trikeshed.cursor.ColumnMeta
import borg.trikeshed.cursor.`ColumnMeta↻`
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.lib.*

// ═══════════════════════════════════════════════════════════════════════════════
// RowVec families — inline value classes over pure Joins
//
// Design: every family with ≤3 components is a @JvmInline value class whose
// single field is a Join (or nested Join).  This gives zero-allocation
// construction while preserving RowVec interface dispatch.
//
// DocRowVec is a typealias for JsonRowVec (Confix synonym).
// ═══════════════════════════════════════════════════════════════════════════════

// ── Series shorthand ───────────────────────────────────────────────────────────
/** Inline Series constructor — s_["a", "b", "c"] creates Series<String>. */
fun <T> s_(vararg elements: T): Series<T> = seriesOf(elements.toList())

// ── JsonRowVec / DocRowVec ────────────────────────────────────────────────────
// Two-component family: nodeType + rawValue, with optional lazy child.
// DocRowVec is a synonym (Confix naming).

class JsonRowVec private constructor(
    private val capture: Join<String, Join<String?, (() -> Series<RowVec>)?>>,
) : RowVec {
    // ── primary constructor ──
    constructor(
        nodeType: String,
        rawValue: String? = null,
        childFactory: (() -> Series<RowVec>)? = null,
    ) : this(nodeType j (rawValue j childFactory))

    // ── accessors ──
    val nodeType: String get() = capture.a
    val rawValue: String? get() = capture.b.a
    private val childFactory: (() -> Series<RowVec>)? get() = capture.b.b

    // ── RowVec ──
    override val a: Int get() = 2
    override val b: (Int) -> Join<Any?, `ColumnMeta↻`>
        get() = { i: Int ->
            val v: Any? = when (i) { 0 -> nodeType; else -> rawValue }
            val colName = if (i == 0) "nodeType" else "rawValue"
            v j { ColumnMeta(colName, IOMemento.IoString) }
        }

    val child: Series<RowVec>? get() = childFactory?.invoke()
    val isShell: Boolean get() = false
    val size: Int get() = 2

    operator fun get(index: Int): Any? = when (index) { 0 -> nodeType; else -> rawValue }

    fun getValue(name: String): Any? = when (name) {
        "nodeType" -> nodeType
        "rawValue" -> rawValue
        else -> null
    }
}

// ── DocRowVec ──────────────────────────────────────────────────────────────────
// Three-component family: keys + cells + optional child, dynamic column access.
// Inline value class over nested Joins — zero-allocation construction.

class DocRowVec private constructor(
    private val capture: Join<Series<String>, Join<Series<Any?>, Series<RowVec>?>>,
    private val keysList: List<String>?, /* cached for linear-scan getValue */
) : RowVec {
    constructor(keys: Series<String>, cells: Series<Any?>, child: Series<RowVec>? = null) : this(
        keys j (cells j child),
        if (keys.size <= 8) null else (0 until keys.size).map { keys[it] }
    )

    @Suppress("UNCHECKED_CAST")
    constructor(keys: List<String>, cells: List<Any?>, child: Series<RowVec>? = null) : this(
        (keys.size j { i: Int -> keys[i] }) j ((cells.size j { i: Int -> cells[i] }) j child),
        keys /* already a List — cache it directly */
    )

    val keys: Series<String> get() = capture.a
    val cells: Series<Any?> get() = capture.b.a
    val child: Series<RowVec>? get() = capture.b.b

    override val a: Int get() = keys.size
    override val b: (Int) -> Join<Any?, `ColumnMeta↻`>
        get() = { i: Int ->
            cells[i] j { ColumnMeta(keys[i], IOMemento.IoString) }
        }

    val size: Int get() = keys.size
    val isShell: Boolean get() = keys.size == 0

    operator fun get(index: Int): Any? = if (index in 0 until keys.size) cells[index] else null

    operator fun get(name: String): Any? = getValue(name)

    fun getValue(name: String): Any? {
        val cached = keysList
        if (cached != null) {
            for (i in cached.indices) {
                if (cached[i] == name) return cells[i]
            }
            return null
        }
        for (i in 0 until keys.size) {
            if (keys[i] == name) return cells[i]
        }
        return null
    }
}

fun DocRowVec.toRowVec(): RowVec = this

// ── ViewRowVec ─────────────────────────────────────────────────────────────────
// Three-component family: id + key + value, with optional lazy doc child.

class ViewRowVec private constructor(
    private val capture: Join<String?, Join<Any?, Join<Any?, (() -> RowVec)?>>>,
) : RowVec {
    constructor(
        id: String?,
        key: Any?,
        value: Any?,
        docLoader: (() -> RowVec)? = null,
    ) : this(id j (key j (value j docLoader)))

    val id: String? get() = capture.a
    val key: Any? get() = capture.b.a
    val value: Any? get() = capture.b.b.a
    private val docLoader: (() -> RowVec)? get() = capture.b.b.b

    override val a: Int get() = 3
    override val b: (Int) -> Join<Any?, () -> ColumnMeta>
        get() = { index: Int ->
            get(index) j { ColumnMeta("col$index", IOMemento.IoString) }
        }

    val child: Series<RowVec>?
        get() = docLoader?.invoke()?.let { doc -> 1 j { doc } }

    val size: Int get() = 3
    val isShell: Boolean get() = false

    operator fun get(index: Int): Any? = when (index) {
        0 -> id; 1 -> key; 2 -> value; else -> null
    }

    fun getValue(name: String): Any? = when (name) {
        "id", "_id" -> id
        "key" -> key
        "value" -> value
        else -> null
    }
}

fun ViewRowVec.toRowVec(): RowVec = this

// ── YamlRowVec ─────────────────────────────────────────────────────────────────
// Two-component family: nodeKind + scalarValue, with optional lazy child.

class YamlRowVec private constructor(
    private val capture: Join<String, Join<String?, (() -> Series<RowVec>)?>>,
) : RowVec {
    constructor(
        nodeKind: String,
        scalarValue: String? = null,
        childFactory: (() -> Series<RowVec>)? = null,
    ) : this(nodeKind j (scalarValue j childFactory))

    val nodeKind: String get() = capture.a
    val scalarValue: String? get() = capture.b.a
    private val childFactory: (() -> Series<RowVec>)? get() = capture.b.b

    override val a: Int get() = 2
    override val b: (Int) -> Join<Any?, `ColumnMeta↻`>
        get() = { i: Int ->
            val v: Any? = when (i) { 0 -> nodeKind; else -> scalarValue }
            val colName = if (i == 0) "nodeKind" else "scalarValue"
            v j { ColumnMeta(colName, IOMemento.IoString) }
        }

    val child: Series<RowVec>? get() = childFactory?.invoke()
    val isShell: Boolean get() = false
    val size: Int get() = 2

    operator fun get(index: Int): Any? = when (index) { 0 -> nodeKind; else -> scalarValue }
}

fun YamlRowVec.toRowVec(): RowVec = this

// ── BlobRowVec ─────────────────────────────────────────────────────────────────
// Two-component family: bytes + mimeType, with optional child factory.

class BlobRowVec private constructor(
    private val capture: Join<ByteArray, Join<String, ((ByteArray) -> Series<RowVec>)?>>,
) : RowVec {
    constructor(
        bytes: ByteArray,
        mimeType: String = "",
        childFactory: ((ByteArray) -> Series<RowVec>)? = null,
    ) : this(bytes j (mimeType j childFactory))

    val bytes: ByteArray get() = capture.a
    val mimeType: String get() = capture.b.a
    private val childFactory: ((ByteArray) -> Series<RowVec>)? get() = capture.b.b

    override val a: Int get() = 0
    override val b: (Int) -> Join<Any?, `ColumnMeta↻`>
        get() = { _ -> throw IndexOutOfBoundsException("BlobRowVec has no columns") }

    val isShell: Boolean get() = true
    val child: Series<RowVec>? get() = childFactory?.invoke(bytes)
}

fun BlobRowVec.toRowVec(childFactory: ((ByteArray) -> Series<RowVec>)? = null): BlobRowVec =
    if (childFactory == null) this else BlobRowVec(bytes, mimeType, childFactory)

// ── KeyedRowVec ─────────────────────────────────────────────────────────────────
// Alias for DocRowVec — use DocRowVec directly for new code.

typealias KeyedRowVec = DocRowVec

// ── BlockRowVec ────────────────────────────────────────────────────────────────
// Container family — stays as a class (mutable state + sealing).

class BlockRowVec private constructor(
    val child: MutableSeries<RowVec>,
) : RowVec {
    enum class State { MUTABLE, SEALED }

    override val a: Int get() = 0
    override val b: (Int) -> Join<Any?, `ColumnMeta↻`>
        get() = { throw IndexOutOfBoundsException("BlockRowVec has no columns") }

    private var _state: State = State.MUTABLE
    val state: State get() = _state
    val rowCount: Int get() = child.size
    val size: Int get() = rowCount
    val isShell: Boolean get() = rowCount == 0

    fun append(row: Any?) {
        check(_state == State.MUTABLE) { "Cannot append to sealed BlockRowVec" }
        val rv: RowVec = when (row) {
            is DocRowVec -> row
            is ViewRowVec -> row
            is JsonRowVec -> row
            is YamlRowVec -> row
            is BlobRowVec -> row
            is GcsRowVec -> row
            is S3RowVec -> row
            is AlibabaRowVec -> row
            is BlockRowVec -> row
            is Join<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                row as RowVec
            }
            else -> JsonRowVec(row.toString(), null)
        }
        child.add(rv)
    }

    fun seal(): BlockRowVec {
        _state = State.SEALED
        return this
    }

    companion object {
        fun mutable(): BlockRowVec = BlockRowVec(emptySeries<RowVec>().cow)
    }
}

fun RowVec.toRowVec(): RowVec = this
fun BlockRowVec.getValue(name: String): Any? = null
fun Any?.blockRows(): Series<RowVec>? = when (this) {
    is BlockRowVec -> child
    else -> childSeries(this)
}

fun Any?.blockRowCount(): Int? = blockRows()?.size

// ── Series<RowVec> JSON helper ────────────────────────────────────────────────

fun Series<RowVec>?.toJson(): String {
    val rows = this ?: return ""
    if (rows.isEmpty()) return ""
    // Pre-size StringBuilder based on estimated row bytes (128 chars/row)
    val json = StringBuilder(rows.size * 128)
    for (rowIndex in 0 until rows.size) {
        if (json.isNotEmpty()) json.append('\n')
        val row = rows[rowIndex]
        appendRowJson(json, row)
        if (row is DocRowVec) {
            val childJson = row.child.toJson()
            if (childJson.isNotBlank()) json.append('\n').append(childJson)
        }
    }
    return json.toString()
}

@Suppress("UNCHECKED_CAST")
internal fun childSeries(source: Any?): Series<RowVec>? = when (source) {
    null -> null
    is Function0<*> -> childSeries(source.invoke())
    is Join<*, *> -> when {
        source.a !is Int -> null
        source.b is Function0<*> -> {
            val size = source.a as Int
            val factory = source.b as Function0<*>
            size j { factory.invoke() as RowVec }
        }
        source.b is Function1<*, *> -> source as Series<RowVec>
        else -> null
    }
    is List<*> -> source.size j { (source[it] as RowVec) }
    else -> null
}

internal fun singletonKey(name: String): Series<String> = 1 j { _: Int -> name }
internal fun singletonCell(value: Any?): Series<Any?> = 1 j { _: Int -> value }

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

// ── Object store row types ────────────────────────────────────────────────────

enum class ObjectStoreProvider { GCS, S3, ALIBABA }

class GcsRowVec(
    val bucket: String,
    val key: String,
    val byteSize: Long,
    val contentType: String? = null,
    val etag: String? = null,
    val lastModified: String? = null,
    val versionId: String? = null,
    val metadata: Map<String, String>? = null,
) : RowVec {
    val provider: ObjectStoreProvider get() = ObjectStoreProvider.GCS
    override val a: Int get() = 8
    override val b: (Int) -> Join<Any?, `ColumnMeta↻`>
        get() = { i: Int -> colValue(i) j { ColumnMeta(colName(i), IOMemento.IoString) } }
    private fun colValue(i: Int): Any? = when (i) {
        0 -> bucket; 1 -> key; 2 -> byteSize; 3 -> contentType
        4 -> etag; 5 -> lastModified; 6 -> versionId; 7 -> metadata; else -> null
    }
    private fun colName(i: Int): String = when (i) {
        0 -> "bucket"; 1 -> "key"; 2 -> "byteSize"; 3 -> "contentType"
        4 -> "etag"; 5 -> "lastModified"; 6 -> "versionId"; 7 -> "metadata"; else -> "?"
    }
}

fun GcsRowVec.toRowVec(): RowVec = this

class S3RowVec(
    val bucket: String,
    val key: String,
    val byteSize: Long,
    val contentType: String? = null,
    val etag: String? = null,
    val lastModified: String? = null,
    val versionId: String? = null,
    val metadata: Map<String, String>? = null,
) : RowVec {
    val provider: ObjectStoreProvider get() = ObjectStoreProvider.S3
    override val a: Int get() = 8
    override val b: (Int) -> Join<Any?, `ColumnMeta↻`>
        get() = { i: Int -> colValue(i) j { ColumnMeta(colName(i), IOMemento.IoString) } }
    private fun colValue(i: Int): Any? = when (i) {
        0 -> bucket; 1 -> key; 2 -> byteSize; 3 -> contentType
        4 -> etag; 5 -> lastModified; 6 -> versionId; 7 -> metadata; else -> null
    }
    private fun colName(i: Int): String = when (i) {
        0 -> "bucket"; 1 -> "key"; 2 -> "byteSize"; 3 -> "contentType"
        4 -> "etag"; 5 -> "lastModified"; 6 -> "versionId"; 7 -> "metadata"; else -> "?"
    }
}

fun S3RowVec.toRowVec(): RowVec = this

class AlibabaRowVec(
    val bucket: String,
    val key: String,
    val byteSize: Long,
    val contentType: String? = null,
    val etag: String? = null,
    val lastModified: String? = null,
    val versionId: String? = null,
    val metadata: Map<String, String>? = null,
) : RowVec {
    val provider: ObjectStoreProvider get() = ObjectStoreProvider.ALIBABA
    override val a: Int get() = 8
    override val b: (Int) -> Join<Any?, `ColumnMeta↻`>
        get() = { i: Int -> colValue(i) j { ColumnMeta(colName(i), IOMemento.IoString) } }
    private fun colValue(i: Int): Any? = when (i) {
        0 -> bucket; 1 -> key; 2 -> byteSize; 3 -> contentType
        4 -> etag; 5 -> lastModified; 6 -> versionId; 7 -> metadata; else -> null
    }
    private fun colName(i: Int): String = when (i) {
        0 -> "bucket"; 1 -> "key"; 2 -> "byteSize"; 3 -> "contentType"
        4 -> "etag"; 5 -> "lastModified"; 6 -> "versionId"; 7 -> "metadata"; else -> "?"
    }
}

fun AlibabaRowVec.toRowVec(): RowVec = this

object ObjectStoreRowVec {
    fun gcs(bucket: String, key: String, byteSize: Long, contentType: String? = null): GcsRowVec =
        GcsRowVec(bucket, key, byteSize, contentType)
    fun s3(bucket: String, key: String, byteSize: Long, contentType: String? = null): S3RowVec =
        S3RowVec(bucket, key, byteSize, contentType)
    fun alibaba(bucket: String, key: String, byteSize: Long, contentType: String? = null): AlibabaRowVec =
        AlibabaRowVec(bucket, key, byteSize, contentType)
}

// ── asSeries extension for DocRowVec ─────────────────────────────────────────
/** Returns the cells of a DocRowVec as a Series<Any?>. */
fun DocRowVec.asSeries(): Series<Any?> = cells
