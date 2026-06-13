@file:Suppress("UNCHECKED_CAST")

package borg.trikeshed.miniduck

import borg.trikeshed.cursor.ColumnMeta
import borg.trikeshed.cursor.IOMemento
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.cursor.`ColumnMeta↻`
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size

private fun metaFor(name: String, value: Any?): ColumnMeta = ColumnMeta(
    name,
    when (value) {
        is Boolean -> IOMemento.IoBoolean
        is Int -> IOMemento.IoInt
        is Long -> IOMemento.IoLong
        is Float -> IOMemento.IoFloat
        is Double -> IOMemento.IoDouble
        is ByteArray -> IOMemento.IoBytes
        is String -> IOMemento.IoString
        null -> IOMemento.IoNothing
        else -> IOMemento.IoObject
    },
)

private fun cell(name: String, value: Any?): Join<Any?, `ColumnMeta↻`> =
    value j { metaFor(name, value) }

private fun rowsOf(rows: List<RowVec>): Series<RowVec> = rows.size j { i: Int -> rows[i] }

private fun valuesRow(keys: List<String>, values: List<Any?>): RowVec =
    values.size j { i: Int -> cell(keys.getOrElse(i) { "c$i" }, values[i]) }

typealias MiniCursor = Series<RowVec>

val RowVec.valueCount: Int get() = this.size

abstract class MiniRowVec : RowVec {
    abstract val keys: List<String>
    abstract val cells: List<Any?>
    open val child: Series<RowVec>? = null
    open val isShell: Boolean get() = cells.isEmpty()

    override val a: Int get() = cells.size
    override val b: (Int) -> Join<Any?, `ColumnMeta↻`>
        get() = { i: Int -> cell(keys.getOrElse(i) { "c$i" }, cells[i]) }

    operator fun get(index: Int): Any? = cells[index]
    operator fun get(name: String): Any? = keys.indexOf(name).takeIf { it >= 0 }?.let { cells.getOrNull(it) }
    fun asSeries(): Series<Any?> = cells.size j { i: Int -> cells[i] }
}

open class DocRowVec(
    override val keys: List<String>,
    override val cells: List<Any?>,
    override val child: Series<RowVec>? = null,
) : MiniRowVec() {
    override val isShell: Boolean get() = cells.isEmpty()
}

class WrappedRowVec(val inner: RowVec) : RowVec by inner

class ViewRowVec(
    val id: String,
    val key: Any?,
    val value: Any?,
    private val docLoader: (() -> RowVec?)? = null,
) : MiniRowVec() {
    override val keys: List<String> = listOf("id", "key", "value")
    override val cells: List<Any?> = listOf(id, key, value)
    override val child: Series<RowVec>?
        get() = docLoader?.invoke()?.let { doc -> 1 j { _: Int -> doc } }
    override val isShell: Boolean get() = false
}

class BlobRowVec(
    val bytes: ByteArray,
    val mimeType: String? = null,
    private val childFactory: ((ByteArray) -> Series<RowVec>?)? = null,
) : MiniRowVec() {
    override val keys: List<String> = emptyList()
    override val cells: List<Any?> = emptyList()
    override val child: Series<RowVec>? get() = childFactory?.invoke(bytes)
    override val isShell: Boolean get() = true
}

class JsonRowVec(
    val nodeType: String,
    val rawValue: String?,
    private val children: (() -> Series<RowVec>?)? = null,
) : MiniRowVec() {
    override val keys: List<String> = listOf("nodeType", "rawValue")
    override val cells: List<Any?> = listOf(nodeType, rawValue)
    override val child: Series<RowVec>? get() = children?.invoke()
}

class YamlRowVec(
    val nodeKind: String,
    val scalarValue: String?,
    private val children: (() -> Series<RowVec>?)? = null,
) : MiniRowVec() {
    override val keys: List<String> = listOf("nodeKind", "scalarValue")
    override val cells: List<Any?> = listOf(nodeKind, scalarValue)
    override val child: Series<RowVec>? get() = children?.invoke()
}

class BlockRowVec private constructor(
    private val rows: MutableList<RowVec>,
) : MiniRowVec() {
    enum class State { MUTABLE, SEALED }

    var state: State = State.MUTABLE
        private set

    override val keys: List<String> = emptyList()
    override val cells: List<Any?> = emptyList()
    override val child: Series<RowVec> get() = rowsOf(rows)
    override val isShell: Boolean get() = true
    val rowCount: Int get() = rows.size

    fun append(row: RowVec) {
        check(state == State.MUTABLE) { "BlockRowVec is sealed" }
        rows.add(row)
    }

    fun seal(): BlockRowVec {
        state = State.SEALED
        return this
    }

    companion object {
        fun mutable(): BlockRowVec = BlockRowVec(mutableListOf())
        fun sealed(rows: List<RowVec>): BlockRowVec = BlockRowVec(rows.toMutableList()).seal()
    }
}

enum class ObjectStoreProvider { GCS, S3, ALIBABA }

abstract class ObjectStoreRowVec(
    val provider: ObjectStoreProvider,
    val bucket: String,
    val key: String,
    val byteSize: Long,
    val contentType: String?,
    val etag: String? = null,
    val lastModified: String? = null,
    val versionId: String? = null,
    val metadata: Map<String, String>? = null,
    private val blob: Series<RowVec>? = null,
) : MiniRowVec() {
    override val keys: List<String> = emptyList()
    override val cells: List<Any?> = emptyList()
    override val child: Series<RowVec>? get() = blob
    override val isShell: Boolean get() = true
}

class GcsRowVec(
    bucket: String,
    key: String,
    byteSize: Long,
    contentType: String?,
    etag: String? = null,
    lastModified: String? = null,
    versionId: String? = null,
    metadata: Map<String, String>? = null,
    blob: Series<RowVec>? = null,
) : ObjectStoreRowVec(ObjectStoreProvider.GCS, bucket, key, byteSize, contentType, etag, lastModified, versionId, metadata, blob)

class S3RowVec(
    bucket: String,
    key: String,
    byteSize: Long,
    contentType: String?,
    etag: String? = null,
    lastModified: String? = null,
    versionId: String? = null,
    metadata: Map<String, String>? = null,
    blob: Series<RowVec>? = null,
) : ObjectStoreRowVec(ObjectStoreProvider.S3, bucket, key, byteSize, contentType, etag, lastModified, versionId, metadata, blob)

class AlibabaRowVec(
    bucket: String,
    key: String,
    byteSize: Long,
    contentType: String?,
    etag: String? = null,
    lastModified: String? = null,
    versionId: String? = null,
    metadata: Map<String, String>? = null,
    blob: Series<RowVec>? = null,
) : ObjectStoreRowVec(ObjectStoreProvider.ALIBABA, bucket, key, byteSize, contentType, etag, lastModified, versionId, metadata, blob)

fun RowVec.getValue(name: String): Any? = when (this) {
    is MiniRowVec -> this[name]
    else -> null
}

fun RowVec.materializedValues(): List<Any?> = when (this) {
    is MiniRowVec -> cells
    else -> (0 until size).map { i -> this.b(i).a }
}

fun RowVec.materializedKeys(): List<String> = when (this) {
    is MiniRowVec -> keys
    else -> (0 until size).map { i -> "c$i" }
}

internal fun docFromValues(keys: List<String>, values: List<Any?>, child: Series<RowVec>? = null): DocRowVec =
    DocRowVec(keys, values, child)

internal fun singleRowCursor(row: RowVec): MiniCursor = 1 j { _: Int -> row }

internal fun listCursor(rows: List<RowVec>): MiniCursor = rows.size j { i: Int -> rows[i] }
