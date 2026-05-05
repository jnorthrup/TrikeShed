package borg.trikeshed.miniduck

import borg.trikeshed.cursor.RowVec
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.manifold.BudgetCoord
import borg.trikeshed.manifold.ManifoldConcept
import borg.trikeshed.parse.json.JsonParser

/**
 * NDJSON codec for BlockRowVec families.
 *
 * Format:
 *   Line 0:  `{"_type":"_block"}`
 *   Line N+1: one JSON object per row, with `_type` field for family dispatch.
 *
 * Scalar values use type tagging:
 *   `{"_k":"n"}` = null
 *   `{"_k":"s","v":"..."}` = String
 *   `{"_k":"i","v":30}` = Int
 *   `{"_k":"l","v":9876543210}` = Long
 *   `{"_k":"d","v":3.14}` = Double
 *   `{"_k":"b","v":true}` = Boolean
 *   `{"_k":"m","v":{...}}` = Map<String,Any?>
 *   `{"_k":"a","v":[...]}` = List<Any?>
 */
object MiniDuckBlockCodec {

    fun encode(block: BlockRowVec): String {
        val sb = StringBuilder()
        sb.append("""{"_type":"_block"}""")
        val rows = block.child ?: return sb.toString()
        for (i in 0 until rows.size) {
            sb.append('\n')
            appendRow(sb, rows[i])
        }
        return sb.toString()
    }

    fun decode(text: String): BlockRowVec {
        val lines = text.split('\n').filter { it.isNotBlank() }
        val block = BlockRowVec.mutable()
        for (idx in 1 until lines.size) {
            val row = decodeRow(lines[idx]) ?: continue
            block.child.add(row)
        }
        return block.seal()
    }

    // ── Encoding ─────────────────────────────────────────────────────────────

    private fun appendRow(sb: StringBuilder, row: RowVec) {
        when (row) {
            is JsonRowVec -> appendJsonRow(sb, row)
            is ViewRowVec -> appendView(sb, row)
            is YamlRowVec -> appendYaml(sb, row)
            is BlobRowVec -> appendBlob(sb, row)
            is DocRowVec -> appendKeyed(sb, row)
            is ManifoldConcept<*> -> appendManifoldConcept(sb, row)
            is GcsRowVec -> appendObjStore(sb, row, "gcs", ObjectStoreProvider.GCS)
            is S3RowVec -> appendObjStore(sb, row, "s3", ObjectStoreProvider.S3)
            is AlibabaRowVec -> appendObjStore(sb, row, "alibaba", ObjectStoreProvider.ALIBABA)
            is BlockRowVec -> appendNestedBlock(sb, row)
            else -> appendGeneric(sb, row)
        }
    }

    private fun appendView(sb: StringBuilder, view: ViewRowVec) {
        sb.append("""{"_type":"view","id":""")
        appendTaggedValue(sb, view.id)
        sb.append(""","key":""")
        appendTaggedValue(sb, view.key)
        sb.append(""","value":""")
        appendTaggedValue(sb, view.value)
        val ch = view.child
        if (ch != null) {
            sb.append(""","_ch":""")
            appendRowArray(sb, ch)
        }
        sb.append('}')
    }

    private fun appendManifoldConcept(sb: StringBuilder, concept: ManifoldConcept<*>) {
        sb.append("""{"_type":"manifold","angular":${concept.angular},"budget":${concept.budget.packed}}""")
    }

    private fun appendJsonRow(sb: StringBuilder, node: JsonRowVec) {
        // DocRowVec is a synonym for JsonRowVec; both encode as "_type":"json"
        sb.append("""{"_type":"json","nodeType":""")
        appendJsonString(sb, node.nodeType)
        sb.append(""","rawValue":""")
        val rv = node.rawValue
        if (rv == null) sb.append("null") else appendJsonString(sb, rv)
        val ch = node.child
        if (ch != null) {
            sb.append(""","_ch":""")
            appendRowArray(sb, ch)
        }
        sb.append('}')
    }

    private fun appendYaml(sb: StringBuilder, node: YamlRowVec) {
        sb.append("""{"_type":"yaml","nodeKind":""")
        appendJsonString(sb, node.nodeKind)
        sb.append(""","scalarValue":""")
        val sv = node.scalarValue
        if (sv == null) sb.append("null") else appendJsonString(sb, sv)
        val ch = node.child
        if (ch != null) {
            sb.append(""","_ch":""")
            appendRowArray(sb, ch)
        }
        sb.append('}')
    }

    private fun appendBlob(sb: StringBuilder, blob: BlobRowVec) {
        sb.append("""{"_type":"blob","mime":""")
        appendJsonString(sb, blob.mimeType)
        sb.append(""","bytes":""")
        appendJsonString(sb, blob.bytes.toHex())
        sb.append('}')
    }

    private fun appendKeyed(sb: StringBuilder, row: DocRowVec) {
        sb.append("""{"_type":"keyed","keys":""")
        appendStrArray(sb, (0 until row.keys.size).map { row.keys[it] })
        sb.append(""","cells":""")
        appendTaggedArray(sb, (0 until row.cells.size).map { row.cells[it] })
        sb.append('}')
    }

    private fun appendObjStore(
        sb: StringBuilder,
        row: RowVec,
        typeTag: String,
        @Suppress("UNUSED_PARAMETER") provider: ObjectStoreProvider,
    ) {
        when (row) {
            is GcsRowVec -> appendObjStoreFields(sb, typeTag, row.bucket, row.key, row.byteSize, row.contentType, row.etag, row.lastModified, row.versionId, row.metadata)
            is S3RowVec -> appendObjStoreFields(sb, typeTag, row.bucket, row.key, row.byteSize, row.contentType, row.etag, row.lastModified, row.versionId, row.metadata)
            is AlibabaRowVec -> appendObjStoreFields(sb, typeTag, row.bucket, row.key, row.byteSize, row.contentType, row.etag, row.lastModified, row.versionId, row.metadata)
            else -> appendGeneric(sb, row)
        }
    }

    private fun appendObjStoreFields(
        sb: StringBuilder, typeTag: String,
        bucket: String, key: String, byteSize: Long,
        contentType: String?, etag: String?, lastModified: String?,
        versionId: String?, metadata: Map<String, String>?,
    ) {
        sb.append("""{"_type":""")
        appendJsonString(sb, typeTag)
        sb.append(""","bucket":""")
        appendJsonString(sb, bucket)
        sb.append(""","key":""")
        appendJsonString(sb, key)
        sb.append(""","byteSize":$byteSize,"contentType":""")
        if (contentType == null) sb.append("null") else appendJsonString(sb, contentType)
        sb.append(""","etag":""")
        if (etag == null) sb.append("null") else appendJsonString(sb, etag)
        sb.append(""","lastModified":""")
        if (lastModified == null) sb.append("null") else appendJsonString(sb, lastModified)
        sb.append(""","versionId":""")
        if (versionId == null) sb.append("null") else appendJsonString(sb, versionId)
        sb.append(""","metadata":""")
        if (metadata == null) sb.append("null")
        else {
            sb.append('{')
            var first = true
            for ((k, v) in metadata) {
                if (!first) sb.append(',')
                first = false
                appendJsonString(sb, k)
                sb.append(':')
                appendJsonString(sb, v)
            }
            sb.append('}')
        }
        sb.append('}')
    }

    private fun appendNestedBlock(sb: StringBuilder, block: BlockRowVec) {
        sb.append("""{"_type":"block","_ch":""")
        val ch = block.child
        if (ch != null) appendRowArray(sb, ch)
        else sb.append("[]")
        sb.append('}')
    }

    private fun appendGeneric(sb: StringBuilder, row: RowVec) {
        sb.append("""{"_type":"generic"}""")
    }

    private fun appendRowArray(sb: StringBuilder, rows: Series<RowVec>) {
        sb.append('[')
        for (i in 0 until rows.size) {
            if (i > 0) sb.append(',')
            appendRow(sb, rows[i])
        }
        sb.append(']')
    }

    private fun appendStrArray(sb: StringBuilder, list: List<String>) {
        sb.append('[')
        list.forEachIndexed { i, s -> if (i > 0) sb.append(','); appendJsonString(sb, s) }
        sb.append(']')
    }

    private fun appendTaggedArray(sb: StringBuilder, list: List<Any?>) {
        sb.append('[')
        list.forEachIndexed { i, v -> if (i > 0) sb.append(','); appendTaggedValue(sb, v) }
        sb.append(']')
    }

    private fun appendTaggedValue(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("""{"_k":"n"}""")
            is String -> { sb.append("""{"_k":"s","v":"""); appendJsonString(sb, v); sb.append('}') }
            is Int -> sb.append("""{"_k":"i","v":$v}""")
            is Long -> sb.append("""{"_k":"l","v":$v}""")
            is Double -> sb.append("""{"_k":"d","v":$v}""")
            is Float -> sb.append("""{"_k":"f","v":$v}""")
            is Boolean -> sb.append("""{"_k":"b","v":$v}""")
            is Map<*, *> -> {
                sb.append("""{"_k":"m","v":{""")
                var first = true
                for ((mk, mv) in v) {
                    if (!first) sb.append(',')
                    first = false
                    appendJsonString(sb, mk.toString())
                    sb.append(':')
                    appendTaggedValue(sb, mv)
                }
                sb.append("}}")
            }
            is List<*> -> {
                sb.append("""{"_k":"a","v":[""")
                v.forEachIndexed { i, it -> if (i > 0) sb.append(','); appendTaggedValue(sb, it) }
                sb.append("]}")
            }
            else -> { sb.append("""{"_k":"s","v":"""); appendJsonString(sb, v.toString()); sb.append('}') }
        }
    }

    private fun appendJsonString(sb: StringBuilder, value: String) {
        sb.append('"')
        for (ch in value) when (ch) {
            '\\' -> sb.append("\\\\")
            '"' -> sb.append("\\\"")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> sb.append(ch)
        }
        sb.append('"')
    }

    // ── Decoding ─────────────────────────────────────────────────────────────

    @Suppress("UNCHECKED_CAST")
    private fun decodeRow(line: String): RowVec? {
        val m = JsonParser.parse(line)
        return when (m["_type"]) {
            "doc" -> decodeJsonRow(m, docMode = true)
            "json" -> decodeJsonRow(m, docMode = false)
            "view" -> decodeView(m)
            "yaml" -> decodeYamlNode(m)
            "blob" -> decodeBlob(m)
            "keyed" -> decodeKeyed(m)
            "manifold" -> decodeManifold(m)
            "gcs" -> decodeGcs(m)
            "s3" -> decodeS3(m)
            "alibaba" -> decodeAlibaba(m)
            "block" -> decodeNestedBlock(m)
            else -> null
        }
    }

    /**
     * Decode a "doc" or "json" row.
     * Doc mode: extracts keys/cells from the JSON and stores them in nodeType/rawValue.
     * Json mode: direct nodeType/rawValue mapping.
     */
    @Suppress("UNCHECKED_CAST")
    private fun decodeJsonRow(m: Map<String, Any?>, docMode: Boolean): JsonRowVec {
        val nodeType: String
        val rawValue: String?
        if (docMode) {
            // Doc format: store keys+cells as a compact JSON string in rawValue
            val keys = (m["keys"] as? List<*>)?.map { it as String } ?: emptyList()
            val rawCells = (m["cells"] as? List<*>) ?: emptyList<Any?>()
            val cells: List<Any?> = rawCells.map { decodeTagged(it) }
            nodeType = "doc"
            rawValue = if (keys.isEmpty() && cells.isEmpty()) null
                else keys.zip(cells).joinToString(",", "{", "}") { (k, v) -> "$k:$v" }
        } else {
            nodeType = m["nodeType"] as? String ?: ""
            rawValue = m["rawValue"] as? String
        }
        val chRaw = m["_ch"] as? List<*>
        val childFactory: (() -> Series<RowVec>)? = chRaw?.let { list ->
            { list.size j { i: Int -> decodeRow(encodeRowToString(list[i]!!)) ?: JsonRowVec("empty", null) } }
        }
        return JsonRowVec(nodeType, rawValue, childFactory)
    }

    @Suppress("UNCHECKED_CAST")
    private fun decodeView(m: Map<String, Any?>): ViewRowVec {
        val id = decodeTagged(m["id"]) as? String
        val key = decodeTagged(m["key"])
        val value = decodeTagged(m["value"])
        val chRaw = m["_ch"] as? List<*>
        val docLoader: (() -> RowVec)? = if (chRaw != null && chRaw.isNotEmpty()) {
            { decodeRow(encodeRowToString(chRaw[0]!!)) ?: JsonRowVec("empty", null) }
        } else null
        return ViewRowVec(id, key, value, docLoader)
    }

    private fun decodeManifold(m: Map<String, Any?>): ManifoldConcept<Nothing?> {
        val angular = (m["angular"] as? Long) ?: (m["angular"] as? Number)?.toLong() ?: 0L
        val budget = (m["budget"] as? Long) ?: (m["budget"] as? Number)?.toLong() ?: 0L
        return ManifoldConcept(angular, BudgetCoord.unpack(budget), null)
    }

    @Suppress("UNCHECKED_CAST")
    private fun decodeYamlNode(m: Map<String, Any?>): YamlRowVec {
        val nodeKind = m["nodeKind"] as? String ?: ""
        val scalarValue = m["scalarValue"] as? String
        val chRaw = m["_ch"] as? List<*>
        val childFactory: (() -> Series<RowVec>)? = chRaw?.let { list ->
            { list.size j { i: Int -> decodeRow(encodeRowToString(list[i]!!)) ?: JsonRowVec("empty", null) } }
        }
        return YamlRowVec(nodeKind, scalarValue, childFactory)
    }

    private fun decodeBlob(m: Map<String, Any?>): BlobRowVec {
        val mime = m["mime"] as? String ?: ""
        val hex = m["bytes"] as? String ?: ""
        return BlobRowVec(hex.fromHex(), mime)
    }

    @Suppress("UNCHECKED_CAST")
     private fun decodeKeyed(m: Map<String, Any?>): DocRowVec {
        val rawKeys = m["keys"] as? List<*> ?: emptyList<Any?>()
        val rawCells = m["cells"] as? List<*> ?: emptyList<Any?>()
        val keys: Series<String> = rawKeys.size j { i -> rawKeys[i] as? String ?: "" }
        val cells: Series<Any?> = rawCells.size j { i -> decodeTagged(rawCells[i]) }
        return DocRowVec(keys, cells)
    }

    @Suppress("UNCHECKED_CAST")
    private fun decodeGcs(m: Map<String, Any?>): GcsRowVec = GcsRowVec(
        bucket = m["bucket"] as? String ?: "",
        key = m["key"] as? String ?: "",
        byteSize = (m["byteSize"] as? Long) ?: (m["byteSize"] as? Number)?.toLong() ?: 0L,
        contentType = m["contentType"] as? String,
        etag = m["etag"] as? String,
        lastModified = m["lastModified"] as? String,
        versionId = m["versionId"] as? String,
        metadata = (m["metadata"] as? Map<String, Any?>)?.mapValues { it.value as? String ?: it.value.toString() },
    )

    @Suppress("UNCHECKED_CAST")
    private fun decodeS3(m: Map<String, Any?>): S3RowVec = S3RowVec(
        bucket = m["bucket"] as? String ?: "",
        key = m["key"] as? String ?: "",
        byteSize = (m["byteSize"] as? Long) ?: (m["byteSize"] as? Number)?.toLong() ?: 0L,
        contentType = m["contentType"] as? String,
        etag = m["etag"] as? String,
        lastModified = m["lastModified"] as? String,
        versionId = m["versionId"] as? String,
        metadata = (m["metadata"] as? Map<String, Any?>)?.let { if (it.isEmpty()) null else it.mapValues { e -> e.value as? String ?: e.value.toString() } },
    )

    @Suppress("UNCHECKED_CAST")
    private fun decodeAlibaba(m: Map<String, Any?>): AlibabaRowVec = AlibabaRowVec(
        bucket = m["bucket"] as? String ?: "",
        key = m["key"] as? String ?: "",
        byteSize = (m["byteSize"] as? Long) ?: (m["byteSize"] as? Number)?.toLong() ?: 0L,
        contentType = m["contentType"] as? String,
        etag = m["etag"] as? String,
        lastModified = m["lastModified"] as? String,
        versionId = m["versionId"] as? String,
        metadata = (m["metadata"] as? Map<String, Any?>)?.let { if (it.isEmpty()) null else it.mapValues { e -> e.value as? String ?: e.value.toString() } },
    )

    @Suppress("UNCHECKED_CAST")
    private fun decodeNestedBlock(m: Map<String, Any?>): BlockRowVec {
        val chRaw = m["_ch"] as? List<*> ?: emptyList<Any?>()
        val block = BlockRowVec.mutable()
        for (item in chRaw) {
            val row = decodeRow(encodeRowToString(item!!))
            if (row != null) block.child.add(row)
        }
        return block.seal()
    }

    /** Re-encode a decoded JSON Map/value back to a JSON string for recursive row parsing. */
    private fun encodeRowToString(value: Any): String {
        val sb = StringBuilder()
        appendAnyJson(sb, value)
        return sb.toString()
    }

    @Suppress("UNCHECKED_CAST")
    private fun appendAnyJson(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("null")
            is String -> appendJsonString(sb, value)
            is Number -> sb.append(value.toString())
            is Boolean -> sb.append(value.toString())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) sb.append(',')
                    first = false
                    appendJsonString(sb, k.toString())
                    sb.append(':')
                    appendAnyJson(sb, v)
                }
                sb.append('}')
            }
            is List<*> -> {
                sb.append('[')
                value.forEachIndexed { i, it -> if (i > 0) sb.append(','); appendAnyJson(sb, it) }
                sb.append(']')
            }
            else -> appendJsonString(sb, value.toString())
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun decodeTagged(raw: Any?): Any? {
        val m = raw as? Map<String, Any?> ?: return raw
        return when (m["_k"]) {
            "n" -> null
            "s" -> m["v"] as? String
            "i" -> (m["v"] as? Long)?.toInt() ?: (m["v"] as? Number)?.toInt()
            "l" -> (m["v"] as? Long) ?: (m["v"] as? Number)?.toLong()
            "d" -> (m["v"] as? Double) ?: (m["v"] as? Number)?.toDouble()
            "f" -> ((m["v"] as? Double) ?: (m["v"] as? Number)?.toDouble())?.toFloat()
            "b" -> m["v"] as? Boolean
            "m" -> (m["v"] as? Map<String, Any?>)?.mapValues { decodeTagged(it.value) }
            "a" -> (m["v"] as? List<*>)?.map { decodeTagged(it) }
            else -> raw
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xFF
            sb.append("0123456789abcdef"[v ushr 4])
            sb.append("0123456789abcdef"[v and 0xF])
        }
        return sb.toString()
    }

    private fun String.fromHex(): ByteArray {
        val len = length / 2
        val out = ByteArray(len)
        for (i in 0 until len) {
            out[i] = ((hexVal(this[i * 2]) shl 4) or hexVal(this[i * 2 + 1])).toByte()
        }
        return out
    }

    private fun hexVal(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> 0
    }
}
