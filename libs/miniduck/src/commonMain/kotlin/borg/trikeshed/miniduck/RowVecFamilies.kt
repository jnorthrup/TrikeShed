@file:Suppress("INLINE_CLASS_DEPRECATED")

package borg.trikeshed.miniduck

import borg.trikeshed.cursor.ColumnMeta
import borg.trikeshed.cursor.`ColumnMeta↻`
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.lib.*

// ═══════════════════════════════════════════════════════════════════════════════
// RowVec families — value classes over pure Joins (Kotlin 2).
//
// Every family with a single-capture backing stores its state in one Join field.
// Kotlin 2  inline class elides the wrapper at callsites where the exact type is
// known, falling back to the interface row view when cast to RowVec.
//
// RowVec = Series2 = MetaSeries<Int,Join> = interface with a: Int, b: (Int)->Join.
// Value classes implement RowVec by overriding a/b and delegating where possible.
// ═══════════════════════════════════════════════════════════════════════════════

// ── JsonRowVec ─────────────────────────────────────────────────────────────────
// Two-component family: nodeType + rawValue, with optional lazy child.


 inline class JsonRowVec(
    private val capture: Join<CharSequence, Join<CharSequence?, (() -> Series<RowVec>)?>>,
) : RowVec {
    constructor(
        nodeType: CharSequence,
        rawValue: CharSequence? = null,
        childFactory: (() -> Series<RowVec>)? = null,
    ) : this(nodeType j (rawValue j childFactory))

    val nodeType: CharSequence get() = capture.a
    val rawValue: CharSequence? get() = capture.b.a
    private val childFactory: (() -> Series<RowVec>)? get() = capture.b.b

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

    fun getValue(name: CharSequence): Any? = when (name.toString()) {
        "nodeType" -> nodeType
        "rawValue" -> rawValue
        else -> null
    }
}

// ── DocRowVec ──────────────────────────────────────────────────────────────────
// Three-component family: keys + cells + optional child, dynamic column access.
// Stays as class — secondary keysList field for cached linear-scan getValue.
// ( Inline class requires exactly one property.)

class DocRowVec private constructor(
    private val capture: Join<Series<CharSequence>, Join<Series<Any?>, Series<RowVec>?>>,
    private val keysList: List<CharSequence>?,
) : RowVec {
    constructor(keys: Series<CharSequence>, cells: Series<Any?>, child: Series<RowVec>? = null) : this(
        keys j (cells j child),
        if (keys.size <= 8) null else (0 until keys.size).map { keys[it] }
    )

    constructor(keys: List<CharSequence>, cells: List<Any?>, child: Series<RowVec>? = null) : this(
        (keys.size j { i: Int -> keys[i] }) j ((cells.size j { i: Int -> cells[i] }) j child),
        keys
    )

    val keys: Series<CharSequence> get() = capture.a
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

    operator fun get(name: CharSequence): Any? = getValue(name)

    fun getValue(name: CharSequence): Any? {
        val cached = keysList
        if (cached != null) {
            for (i in cached.indices) {
                if (cached[i].contentEquals(name)) return cells[i]
            }
            return null
        }
        for (i in 0 until keys.size) {
            if (keys[i].contentEquals(name)) return cells[i]
        }
        return null
    }
}


// ── ViewRowVec ─────────────────────────────────────────────────────────────────
// Three-component family: id + key + value, with optional lazy doc child.


 inline class ViewRowVec(
    private val capture: Join<CharSequence?, Join<Any?, Join<Any?, (() -> RowVec)?>>>,
) : RowVec {
    constructor(
        id: CharSequence?,
        key: Any?,
        value: Any?,
        docLoader: (() -> RowVec)? = null,
    ) : this(id j (key j (value j docLoader)))

    val id: CharSequence? get() = capture.a
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

    fun getValue(name: CharSequence): Any? = when (name.toString()) {
        "id", "_id" -> id
        "key" -> key
        "value" -> value
        else -> null
    }
}


// ── YamlRowVec ─────────────────────────────────────────────────────────────────
// Two-component family: nodeKind + scalarValue, with optional lazy child.
// Identical capture shape to JsonRowVec, different accessor names.


 inline class YamlRowVec(
    private val capture: Join<CharSequence, Join<CharSequence?, (() -> Series<RowVec>)?>>,
) : RowVec {
    constructor(
        nodeKind: CharSequence,
        scalarValue: CharSequence? = null,
        childFactory: (() -> Series<RowVec>)? = null,
    ) : this(nodeKind j (scalarValue j childFactory))

    val nodeKind: CharSequence get() = capture.a
    val scalarValue: CharSequence? get() = capture.b.a
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


// ── BlobRowVec ─────────────────────────────────────────────────────────────────
// Two-component family: bytes + mimeType, with optional child factory.

inline class BlobRowVec(
    val capture: Join<ByteArray, Join<CharSequence, ((ByteArray) -> Series<RowVec>)?>>,
) : RowVec {
    constructor(
        bytes: ByteArray,
                childFactory: ((ByteArray) -> Series<RowVec>)? = null,
    ) : this(bytes j ("blob" j childFactory))

    val bytes: ByteArray get() = capture.a
    val mimeType: CharSequence get() = CharSeries( capture.b.a)
    private val childFactory: ((ByteArray) -> Series<RowVec>)? get() = capture.b.b

    override val a: Int get() = 0
    override val b: (Int) -> Join<Any?, `ColumnMeta↻`>
        get() = { _ -> throw IndexOutOfBoundsException("BlobRowVec has no columns") }

    val isShell: Boolean get() = true
    val child: Series<RowVec>? get() = childFactory?.invoke(bytes)
}

fun BlobRowVec.toRowVec(childFactory: ((ByteArray) -> Series<RowVec>)? = null): BlobRowVec =
    if (childFactory == null) this else BlobRowVec(bytes,   childFactory)

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
        child.add(row as? RowVec ?: JsonRowVec(row.toString(), null))
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
fun BlockRowVec.getValue(name: CharSequence): Any? = null
fun Any?.blockRows(): Series<RowVec>? = when (this) {
    is BlockRowVec -> child
    else -> childSeries(this)
}

fun Any?.blockRowCount(): Int? = blockRows()?.size

// ── Series<RowVec> JSON helper ────────────────────────────────────────────────

fun Series<RowVec>?.toJson(): CharSequence {
    val rows = this ?: return ""
    if (rows.isEmpty()) return ""
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

internal fun singletonKey(name: CharSequence): Series<CharSequence> = 1 j { _: Int -> name }
internal fun singletonCell(value: Any?): Series<Any?> = 1 j { _: Int -> value }

private fun appendRowJson(json: StringBuilder, row: RowVec) {
    json.append('{')
    for (columnIndex in 0 until row.size) {
        if (columnIndex > 0) json.append(',')
        val cell = row[columnIndex]
        appendJsonString(json, cell.b().a.toString())
        json.append(": ")
        appendJsonValue(json, cell.a)
    }
    json.append('}')
}

private fun appendJsonValue(json: StringBuilder, value: Any?) {
    when (value) {
        null -> json.append("null")
        is CharSequence -> appendJsonString(json, value)
        else -> json.append(value)
    }
}

private fun appendJsonString(json: StringBuilder, value: CharSequence) {
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

// ── Object store row types ───────────────────────────────────────────────────
// Three distinct value classes — GCS, S3, Alibaba have different provider
// semantics and are not interchangeable.  Named intermediates cut the .b chain.

enum class ObjectStoreProvider { GCS, S3, ALIBABA }

// Shared capture shape — each class holds (bucket j (key j (byteSize j (contentType j (etag j (lastModified j (versionId j metadata)))))))
typealias ObjStorePayload = Join<CharSequence, Join<CharSequence, Join<Long, Join<CharSequence?, Join<CharSequence?, Join<CharSequence?, Join<CharSequence?, Map<CharSequence, CharSequence>?>>>>>>>


 inline class GcsRowVec(private val capture: ObjStorePayload) : RowVec {
    constructor(
        bucket: CharSequence, key: CharSequence, byteSize: Long,
        contentType: CharSequence? = null, etag: CharSequence? = null, lastModified: CharSequence? = null,
        versionId: CharSequence? = null, metadata: Map<CharSequence, CharSequence>? = null,
    ) : this(bucket j (key j (byteSize j (contentType j (etag j (lastModified j (versionId j metadata)))))))

    val provider: ObjectStoreProvider  get() = ObjectStoreProvider.GCS
    private val fromKey                get() = capture.b
    private val fromSize               get() = fromKey.b
    private val fromCT                 get() = fromSize.b
    private val fromEtag               get() = fromCT.b
    private val fromLM                 get() = fromEtag.b
    private val fromVer                get() = fromLM.b
    val bucket: CharSequence                 get() = capture.a
    val key: CharSequence                    get() = fromKey.a
    val byteSize: Long                 get() = fromSize.a
    val contentType: CharSequence?           get() = fromCT.a
    val etag: CharSequence?                  get() = fromEtag.a
    val lastModified: CharSequence?          get() = fromLM.a
    val versionId: CharSequence?             get() = fromVer.a
    val metadata: Map<CharSequence, CharSequence>? get() = fromVer.b

    override val a: Int get() = 8
    override val b: (Int) -> Join<Any?, `ColumnMeta↻`>
        get() = { i: Int -> colValue(i) j { ColumnMeta(colName(i), IOMemento.IoString) } }
    private fun colValue(i: Int): Any? = when (i) {
        0 -> bucket; 1 -> key; 2 -> byteSize; 3 -> contentType
        4 -> etag; 5 -> lastModified; 6 -> versionId; 7 -> metadata; else -> null
    }
    private fun colName(i: Int): CharSequence = when (i) {
        0 -> "bucket"; 1 -> "key"; 2 -> "byteSize"; 3 -> "contentType"
        4 -> "etag"; 5 -> "lastModified"; 6 -> "versionId"; 7 -> "metadata"; else -> "?"
    }
}



 inline class S3RowVec(private val capture: ObjStorePayload) : RowVec {
    constructor(
        bucket: CharSequence, key: CharSequence, byteSize: Long,
        contentType: CharSequence? = null, etag: CharSequence? = null, lastModified: CharSequence? = null,
        versionId: CharSequence? = null, metadata: Map<CharSequence, CharSequence>? = null,
    ) : this(bucket j (key j (byteSize j (contentType j (etag j (lastModified j (versionId j metadata)))))))

    val provider: ObjectStoreProvider  get() = ObjectStoreProvider.S3
    private val fromKey                get() = capture.b
    private val fromSize               get() = fromKey.b
    private val fromCT                 get() = fromSize.b
    private val fromEtag               get() = fromCT.b
    private val fromLM                 get() = fromEtag.b
    private val fromVer                get() = fromLM.b
    val bucket: CharSequence                 get() = capture.a
    val key: CharSequence                    get() = fromKey.a
    val byteSize: Long                 get() = fromSize.a
    val contentType: CharSequence?           get() = fromCT.a
    val etag: CharSequence?                  get() = fromEtag.a
    val lastModified: CharSequence?          get() = fromLM.a
    val versionId: CharSequence?             get() = fromVer.a
    val metadata: Map<CharSequence, CharSequence>? get() = fromVer.b

    override val a: Int get() = 8
    override val b: (Int) -> Join<Any?, `ColumnMeta↻`>
        get() = { i: Int -> colValue(i) j { ColumnMeta(colName(i), IOMemento.IoString) } }
    private fun colValue(i: Int): Any? = when (i) {
        0 -> bucket; 1 -> key; 2 -> byteSize; 3 -> contentType
        4 -> etag; 5 -> lastModified; 6 -> versionId; 7 -> metadata; else -> null
    }
    private fun colName(i: Int): CharSequence = when (i) {
        0 -> "bucket"; 1 -> "key"; 2 -> "byteSize"; 3 -> "contentType"
        4 -> "etag"; 5 -> "lastModified"; 6 -> "versionId"; 7 -> "metadata"; else -> "?"
    }
}



 inline class AlibabaRowVec(private val capture: ObjStorePayload) : RowVec {
    constructor(
        bucket: CharSequence, key: CharSequence, byteSize: Long,
        contentType: CharSequence? = null, etag: CharSequence? = null, lastModified: CharSequence? = null,
        versionId: CharSequence? = null, metadata: Map<CharSequence, CharSequence>? = null,
    ) : this(bucket j (key j (byteSize j (contentType j (etag j (lastModified j (versionId j metadata)))))))

    val provider: ObjectStoreProvider  get() = ObjectStoreProvider.ALIBABA
    private val fromKey                get() = capture.b
    private val fromSize               get() = fromKey.b
    private val fromCT                 get() = fromSize.b
    private val fromEtag               get() = fromCT.b
    private val fromLM                 get() = fromEtag.b
    private val fromVer                get() = fromLM.b
    val bucket: CharSequence                 get() = capture.a
    val key: CharSequence                    get() = fromKey.a
    val byteSize: Long                 get() = fromSize.a
    val contentType: CharSequence?           get() = fromCT.a
    val etag: CharSequence?                  get() = fromEtag.a
    val lastModified: CharSequence?          get() = fromLM.a
    val versionId: CharSequence?             get() = fromVer.a
    val metadata: Map<CharSequence, CharSequence>? get() = fromVer.b

    override val a: Int get() = 8
    override val b: (Int) -> Join<Any?, `ColumnMeta↻`>
        get() = { i: Int -> colValue(i) j { ColumnMeta(colName(i), IOMemento.IoString) } }
    private fun colValue(i: Int): Any? = when (i) {
        0 -> bucket; 1 -> key; 2 -> byteSize; 3 -> contentType
        4 -> etag; 5 -> lastModified; 6 -> versionId; 7 -> metadata; else -> null
    }
    private fun colName(i: Int): CharSequence = when (i) {
        0 -> "bucket"; 1 -> "key"; 2 -> "byteSize"; 3 -> "contentType"
        4 -> "etag"; 5 -> "lastModified"; 6 -> "versionId"; 7 -> "metadata"; else -> "?"
    }
}

