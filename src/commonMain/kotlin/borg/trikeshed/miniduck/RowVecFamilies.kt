package borg.trikeshed.miniduck

import borg.trikeshed.cursor.ColumnMeta
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.cursor.TypeMemento
import borg.trikeshed.cursor.`ColumnMeta↻`
import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.lib.ReifiedSplitSeries2
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.lib.toSeries

/**
 * DocRowVec: flat document fields with optional lazy nested children.
 *
 * cells: ordered list of field values (scalars, nulls)
 * keys:  parallel list of field names (same length as cells)
 * child: lazy nested expansion (embedded objects/arrays), or null for leaf docs
 */
class DocRowVec(
    val keys: List<String>,
    val cells: List<Any?>,
    override val child: Series<MiniRowVec>? = null,
) : MiniRowVec() {
    init { require(keys.size == cells.size) { "keys and cells must have same length" } }

    override val size: Int get() = cells.size
    override fun get(index: Int): Any? = cells[index]

    /** Look up a field by name. Returns null if not found. */
    operator fun get(key: String): Any? = cells.getOrNull(keys.indexOf(key))
}

fun DocRowVec.toRowVec(): RowVec {
    val values: Series<Any?> = cells.toSeries()
    val meta: Series<`ColumnMeta↻`> = keys.size j { index: Int ->
        { @Suppress("UNCHECKED_CAST") (keys[index] j cells.getOrNull(index).toIOMemento() as TypeMemento) }
    }
    return ReifiedSplitSeries2(values, meta)
}

private fun Any?.toIOMemento(): IOMemento = when (this) {
    is Double -> IOMemento.IoDouble
    is Float -> IOMemento.IoFloat
    is Long -> IOMemento.IoLong
    is Int -> IOMemento.IoInt
    is Boolean -> IOMemento.IoBoolean
    is ByteArray -> IOMemento.IoByteArray
    null -> IOMemento.IoNothing
    else -> IOMemento.IoString
}

/**
 * ViewRowVec: Couch-style view row: id / key / value / doc.
 *
 * doc expansion is deferred -- child is null until traversed.
 */
class ViewRowVec(
    val id: String,
    val key: Any?,
    val value: Any?,
   val docLoader: (() -> MiniRowVec)? = null,
) : MiniRowVec() {
   var loadedChild: Series<MiniRowVec>? = null

    // scalar surface: [id, key, value]
    override val size: Int get() = 3
    override fun get(index: Int): Any? = when (index) {
        0 -> id
        1 -> key
        2 -> value
        else -> throw IndexOutOfBoundsException(index.toString())
    }

    /** Lazy doc expansion as a single-child Series. */
    override val child: Series<MiniRowVec>?
        get() {
            loadedChild?.let { return it }
            val loader = docLoader ?: return null
            val row = loader()
            val childSeries: Series<MiniRowVec> = 1 j { _: Int -> row }
            return childSeries.also { loadedChild = it }
        }
}

/**
 * BlobRowVec: zero-length shell for opaque payloads.
 *
 * No scalar cells. All meaning is deferred into children:
 *   metadata, MIME/type effects, JSON/YAML projections, decode previews.
 */
class BlobRowVec(
    val bytes: ByteArray,
    val mimeType: String? = null,
   val childFactory: ((ByteArray) -> Series<MiniRowVec>)? = null,
) : MiniRowVec() {
    override val size: Int get() = 0
    override fun get(index: Int): Any? = throw IndexOutOfBoundsException("BlobRowVec is a shell")

    override val child: Series<MiniRowVec>?
        get() = childFactory?.invoke(bytes)
}

/**
 * JsonRowVec: parse-tree row over a JSON string blob.
 *
 * Represents one JSON node. Scalar cells: [nodeType, rawValue].
 * Children: sub-nodes for object keys / array elements.
 *
 * Full JSON parsing is NOT done here -- deferred to childFactory.
 */
class JsonRowVec(
    val nodeType: String,   // "object", "array", "string", "number", "boolean", "null"
    val rawValue: String,
   val childFactory: (() -> Series<MiniRowVec>)? = null,
) : MiniRowVec() {
    override val size: Int get() = 2
    override fun get(index: Int): Any? = when (index) {
        0 -> nodeType
        1 -> rawValue
        else -> throw IndexOutOfBoundsException(index.toString())
    }
    override val child: Series<MiniRowVec>? get() = childFactory?.invoke()
}

/**
 * YamlRowVec: parse-tree row over a YAML string blob.
 *
 * Scalar cells: [nodeKind, scalarValue?].
 * Children: sub-nodes for mapping entries / sequence items.
 */
class YamlRowVec(
    val nodeKind: String,    // "mapping", "sequence", "scalar"
    val scalarValue: String? = null,
   val childFactory: (() -> Series<MiniRowVec>)? = null,
) : MiniRowVec() {
    override val size: Int get() = 2
    override fun get(index: Int): Any? = when (index) {
        0 -> nodeKind
        1 -> scalarValue
        else -> throw IndexOutOfBoundsException(index.toString())
    }
    override val child: Series<MiniRowVec>? get() = childFactory?.invoke()
}

/**
 * CsvRowVec: parse-tree row over a CSV string blob.
 *
 * Scalar cells: [nodeKind, rawValue].
 * Children: sub-nodes for nested rows or column groups.
 *
 * nodeKind: "header", "row", or "cell"
 * rawValue: the raw CSV line or cell value
 */
class CsvRowVec(
    val nodeKind: String,    // "header", "row", "cell"
    val rawValue: String,
   val childFactory: (() -> Series<MiniRowVec>)? = null,
) : MiniRowVec() {
    override val size: Int get() = 2
    override fun get(index: Int): Any? = when (index) {
        0 -> nodeKind
        1 -> rawValue
        else -> throw IndexOutOfBoundsException(index.toString())
    }
    override val child: Series<MiniRowVec>? get() = childFactory?.invoke()
}

/* ═══════════════════════════════════════════════════════════════════════════
 * Object store RowVec family — shell rows that carry cloud blob metadata.
 *
 * GcsRowVec   — Google Cloud Storage
 * S3RowVec    — AWS S3
 * AlibabaRowVec — Alibaba Cloud OSS
 *
 * These are shells (size=0): scalar surface is empty, meaning lives in
 * the lazy child (a BlobRowVec holding the actual bytes).  Metadata fields
 * are stored as constructor parameters and serialised through the codec.
 *
 * NOTE: the blob's byte-size is stored in field `byteSize` (not `size`)
 * to avoid conflicting with MiniRowVec.size: Int.
 * The blob bytes live in `blob: Series<MiniRowVec>?` (not `child`)
 * to avoid conflicting with MiniRowVec.child: Series<MiniRowVec>?.
 * ═══════════════════════════════════════════════════════════════════════════ */

/** Cloud provider identity for object-store adapters. */
enum class ObjectStoreProvider {
    GCS,
    S3,
    ALIBABA,
}

/** Base shell row for object-store blobs.  All fields are constructor params. */
sealed class ObjectStoreRowVec(
    open val bucket: String,
    open val key: String,
    open val byteSize: Long,
    open val contentType: String?,
    open val etag: String?,
    open val lastModified: String?,
    open val versionId: String?,
    open val metadata: Map<String, String>?,
    open val blob: Series<MiniRowVec>?,
) : MiniRowVec() {
    override val size: Int get() = 0
    override fun get(index: Int): Any? = throw IndexOutOfBoundsException("ObjectStoreRowVec is a shell")
    override val child: Series<MiniRowVec>? get() = blob
    abstract val provider: ObjectStoreProvider

    companion object {
        /** Factory for GCS blobs. */
        fun gcs(
            bucket: String,
            key: String,
            byteSize: Long,
            contentType: String?,
            etag: String? = null,
            lastModified: String? = null,
            versionId: String? = null,
            metadata: Map<String, String>? = null,
            blob: Series<MiniRowVec>? = null,
        ): ObjectStoreRowVec = GcsRowVec(bucket, key, byteSize, contentType, etag, lastModified, versionId, metadata, blob)

        /** Factory for AWS S3 blobs. */
        fun s3(
            bucket: String,
            key: String,
            byteSize: Long,
            contentType: String?,
            etag: String? = null,
            lastModified: String? = null,
            versionId: String? = null,
            metadata: Map<String, String>? = null,
            blob: Series<MiniRowVec>? = null,
        ): ObjectStoreRowVec = S3RowVec(bucket, key, byteSize, contentType, etag, lastModified, versionId, metadata, blob)

        /** Factory for Alibaba Cloud OSS blobs. */
        fun alibaba(
            bucket: String,
            key: String,
            byteSize: Long,
            contentType: String?,
            etag: String? = null,
            lastModified: String? = null,
            versionId: String? = null,
            metadata: Map<String, String>? = null,
            blob: Series<MiniRowVec>? = null,
        ): ObjectStoreRowVec = AlibabaRowVec(bucket, key, byteSize, contentType, etag, lastModified, versionId, metadata, blob)
    }
}

/** GCS blob metadata row. */
class GcsRowVec(
    override val bucket: String,
    override val key: String,
    override val byteSize: Long,
    override val contentType: String?,
    override val etag: String? = null,
    override val lastModified: String? = null,
    override val versionId: String? = null,
    override val metadata: Map<String, String>? = null,
    override val blob: Series<MiniRowVec>? = null,
) : ObjectStoreRowVec(bucket, key, byteSize, contentType, etag, lastModified, versionId, metadata, blob) {
    override val provider: ObjectStoreProvider get() = ObjectStoreProvider.GCS
}

/** AWS S3 blob metadata row. */
class S3RowVec(
    override val bucket: String,
    override val key: String,
    override val byteSize: Long,
    override val contentType: String?,
    override val etag: String? = null,
    override val lastModified: String? = null,
    override val versionId: String? = null,
    override val metadata: Map<String, String>? = null,
    override val blob: Series<MiniRowVec>? = null,
) : ObjectStoreRowVec(bucket, key, byteSize, contentType, etag, lastModified, versionId, metadata, blob) {
    override val provider: ObjectStoreProvider get() = ObjectStoreProvider.S3
}

/** Alibaba Cloud OSS blob metadata row. */
class AlibabaRowVec(
    override val bucket: String,
    override val key: String,
    override val byteSize: Long,
    override val contentType: String?,
    override val etag: String? = null,
    override val lastModified: String? = null,
    override val versionId: String? = null,
    override val metadata: Map<String, String>? = null,
    override val blob: Series<MiniRowVec>? = null,
) : ObjectStoreRowVec(bucket, key, byteSize, contentType, etag, lastModified, versionId, metadata, blob) {
    override val provider: ObjectStoreProvider get() = ObjectStoreProvider.ALIBABA
}
