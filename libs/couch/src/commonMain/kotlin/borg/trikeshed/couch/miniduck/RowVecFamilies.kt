package borg.trikeshed.couch.miniduck

import borg.trikeshed.lib.*

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

/**
 * ViewRowVec: Couch-style view row: id / key / value / doc.
 *
 * doc expansion is deferred -- child is null until traversed.
 */
class ViewRowVec(
    val id: String,
    val key: Any?,
    val value: Any?,
    private val docLoader: (() -> MiniRowVec)? = null,
) : MiniRowVec() {
    private var loadedChild: Series<MiniRowVec>? = null

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
    private val childFactory: ((ByteArray) -> Series<MiniRowVec>)? = null,
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
    private val childFactory: (() -> Series<MiniRowVec>)? = null,
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
    private val childFactory: (() -> Series<MiniRowVec>)? = null,
) : MiniRowVec() {
    override val size: Int get() = 2
    override fun get(index: Int): Any? = when (index) {
        0 -> nodeKind
        1 -> scalarValue
        else -> throw IndexOutOfBoundsException(index.toString())
    }
    override val child: Series<MiniRowVec>? get() = childFactory?.invoke()
}
