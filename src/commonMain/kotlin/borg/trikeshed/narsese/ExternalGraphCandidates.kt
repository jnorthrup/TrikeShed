package borg.trikeshed.narsese

import borg.trikeshed.job.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.reifyStrict

/** A row supplied by an external decoder. Its membership in the shard is a claim, not a verified fact. */
data class ExternalGraphRow(val ordinal: Int, val entryId: String, val record: ByteArray, val recordSha256: String)

data class ExternalGraphCandidateBatch(val receipt: ContentId, val manifest: ContentId, val shard: ContentId, val rows: Series<ContentId>)

/**
 * Retain externally decoded graph records as isolated, unreviewed candidates. This does not
 * populate a QuadStore or establish that a supplied row was actually decoded from the shard.
 * The expected manifest digest is supplied by the caller; it establishes byte identity only.
 */
object ExternalGraphCandidates {
    private const val MANIFEST_LIMIT = 4 * 1024 * 1024
    private const val SHARD_LIMIT = 32 * 1024 * 1024
    private const val ROW_LIMIT = 10_000
    private const val ROW_BYTES = 1 * 1024 * 1024
    private const val TOTAL_ROW_BYTES = 16 * 1024 * 1024
    private const val JSON_DEPTH = 64

    /** Bound nesting before the recursive JSON parser; quoted brackets do not count. */
    private fun boundedJson(text: String) {
        var depth = 0
        var quoted = false
        var escaped = false
        for (ch in text) {
            if (quoted) {
                if (escaped) escaped = false
                else if (ch == '\\') escaped = true
                else if (ch == '"') quoted = false
            } else when (ch) {
                '"' -> quoted = true
                '{', '[' -> require(++depth <= JSON_DEPTH) { "JSON nesting limit" }
                '}', ']' -> require(--depth >= 0) { "Malformed JSON" }
            }
        }
        require(depth == 0 && !quoted) { "Malformed JSON" }
    }

    fun retain(cas: CasStore, manifestBytes: ByteArray, expectedManifest: ContentId, shardPath: String,
        shardBytes: ByteArray, decoder: String, rows: Series<ExternalGraphRow>): ExternalGraphCandidateBatch {
        require(manifestBytes.size <= MANIFEST_LIMIT && shardBytes.size <= SHARD_LIMIT && rows.size in 0..ROW_LIMIT)
        // Snapshot caller-owned mutable bytes before evaluating the caller's lazy row Series.
        val manifestSnapshot = manifestBytes.copyOf()
        val shardSnapshot = shardBytes.copyOf()
        require(ContentId.of(manifestSnapshot) == expectedManifest) { "Manifest digest mismatch" }
        require(decoder.isNotBlank() && decoder.length <= 256) { "Decoder identity is required" }
        require(shardPath.isNotBlank() && shardPath.length <= 1024 && !shardPath.startsWith('/') &&
            shardPath.split('/').none { it.isEmpty() || it == "." || it == ".." }) { "Invalid shard path" }
        val manifestText = manifestSnapshot.decodeToString(throwOnInvalidSequence = true)
        boundedJson(manifestText)
        val manifest = reifyStrict(manifestText) as? Map<*, *>
            ?: throw IllegalArgumentException("Manifest must be an object")
        val descriptors = (manifest["artifacts"] as? List<*>)?.filterIsInstance<Map<*, *>>()
            ?.filter { it["relative_path"] == shardPath } ?: emptyList()
        require(descriptors.size == 1) { "Expected one shard descriptor" }
        val descriptor = descriptors.single()
        fun integer(key: String, max: Long): Long {
            val value = (descriptor[key] as? Number)?.toDouble()
                ?: throw IllegalArgumentException("Missing or invalid $key")
            require(value.isFinite() && value >= 0.0 && value <= max.toDouble() && value % 1.0 == 0.0) {
                "Invalid $key"
            }
            return value.toLong()
        }
        val count = integer("row_count", Int.MAX_VALUE.toLong())
        val size = integer("size_bytes", SHARD_LIMIT.toLong())
        val digest = descriptor["sha256"] as? String ?: error("Missing shard digest")
        val schema = (descriptor["schema_id"] as? String)?.takeIf { it.isNotBlank() && it.length <= 256 }
            ?: error("Missing shard schema")
        require(size == shardSnapshot.size.toLong() && ContentId.of(shardSnapshot).hex == digest) { "Shard descriptor mismatch" }
        var total = 0L
        val ordinals = HashSet<Int>()
        val snapshot = ArrayList<ExternalGraphRow>(rows.size)
        for (i in 0 until rows.size) {
            // A Series can compute a different value each time it is read. Read it once,
            // cap its size, and retain a private copy of its mutable record bytes.
            val supplied = rows[i]
            require(supplied.record.size <= ROW_BYTES) { "Invalid row" }
            total += supplied.record.size
            require(total <= TOTAL_ROW_BYTES) { "Row size limit" }
            val row = supplied.copy(record = supplied.record.copyOf())
            require(row.ordinal >= 0 && row.ordinal.toLong() < count && ordinals.add(row.ordinal)) { "Invalid or duplicate row ordinal" }
            require(row.entryId.isNotBlank() && row.entryId.length <= 1024 && row.record.size <= ROW_BYTES) { "Invalid row" }
            require(ContentId.of(row.record).hex == row.recordSha256) { "Row digest mismatch" }
            val recordText = row.record.decodeToString(throwOnInvalidSequence = true)
            boundedJson(recordText)
            require(reifyStrict(recordText) is Map<*, *>) { "Row record must be a JSON object" }
            snapshot.add(row)
        }
        // Validation completes before retaining any candidate. Row IDs and ordinals are not verified membership proofs.
        val manifestCid = cas.put(manifestSnapshot)
        val shardCid = cas.put(shardSnapshot)
        val retained = snapshot.map { cas.put(it.record) }
        val entries = snapshot.mapIndexed { i, row -> _m[
            "ordinal" j row.ordinal, "entryIdClaim" j row.entryId, "recordCid" j retained[i].value
        ] }
        val bytes = CanonicalCbor.encodeMap(_m[
            "kind" j "external-graph-candidates-v1", "manifestCid" j manifestCid.value,
            "shardCid" j shardCid.value, "shardPath" j shardPath, "schema" j schema,
            "decoder" j decoder, "membership" j "unverified", "reviewStatus" j "unreviewed", "rows" j entries
        ])
        cas.sync()
        val receipt = cas.put(bytes)
        cas.sync()
        return ExternalGraphCandidateBatch(receipt, manifestCid, shardCid, retained.toSeries())
    }
}
