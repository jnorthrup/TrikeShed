package borg.trikeshed.narsese

import borg.trikeshed.job.CanonicalCbor
import borg.trikeshed.job.ContentId
import borg.trikeshed.rdf.RdfQuad
import borg.trikeshed.rdf.RdfTerm

/**
 * A transported claim. Source and receipt identifiers and quotation have not been verified.
 * Version 1 offsets claim UTF-16 code-unit positions in extracted text, in [begin, end) order.
 */
data class LegalQuadCandidate(
    val quad: RdfQuad,
    val originalCid: ContentId,
    val extractedTextCid: ContentId,
    val curationReceiptCid: ContentId,
    val quote: String,
    val begin: Int,
    val end: Int,
)

/**
 * Bounded content-addressed interchange bytes for source-linked quad candidates.
 * This codec does not read referenced blocks, verify quotations, authenticate authors,
 * establish review status, contact IPFS, or import anything into a quad store.
 * ContentId is the local SHA-256 identifier, not an IPFS CID. IRI checks do not provide
 * full RFC 3987 validation.
 */
object LegalQuadSnapshot {
    private const val KIND = "legal-quad-candidates/1"
    private const val MAX_ROWS = 512
    private const val MAX_CHARS = 1_000_000
    private const val MAX_FIELD = 8192
    private const val MAX_BYTES = 4_000_000

    private fun text(value: String): String {
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c in '\uD800'..'\uDBFF') {
                require(i + 1 < value.length && value[i + 1] in '\uDC00'..'\uDFFF') { "Unpaired surrogate" }
                i += 2
            } else {
                require(c !in '\uDC00'..'\uDFFF') { "Unpaired surrogate" }
                i++
            }
        }
        return value
    }

    private fun iri(value: String): String {
        text(value)
        require(value.length in 1..MAX_FIELD && value.substringBefore(':').matches(Regex("[A-Za-z][A-Za-z0-9+.-]*")) &&
            value.contains(':') && value.none { it <= ' ' || it in '\u007f'..'\u009f' || it in "<>\\\"{}|^`" }) { "Invalid IRI" }
        return value
    }

    private fun fields(candidate: LegalQuadCandidate): Map<String, Any?> {
        val q = candidate.quad
        val subject = q.s as? RdfTerm.Iri ?: error("Blank node subjects are unsupported")
        val objectFields: Map<String, Any?> = when (val obj = q.o) {
            is RdfTerm.Iri -> mapOf("kind" to "iri", "value" to iri(obj.iri))
            is RdfTerm.Literal -> {
                require(obj.lexical.length <= MAX_FIELD && (obj.lang == null || obj.datatype == null))
                obj.datatype?.let(::iri)
                obj.lang?.let { require(it.length <= 128 && it.matches(Regex("[A-Za-z]+(-[A-Za-z0-9]+)*"))) }
                mapOf("kind" to "literal", "value" to text(obj.lexical), "datatype" to obj.datatype, "lang" to obj.lang)
            }
            is RdfTerm.BlankNode -> error("Blank node objects are unsupported")
        }
        require(candidate.quote.length in 1..MAX_FIELD && candidate.begin >= 0 && candidate.end > candidate.begin)
        return mapOf("subject" to iri(subject.iri), "predicate" to iri(q.p.iri), "object" to objectFields,
            "graph" to q.g?.iri?.let(::iri), "originalCid" to candidate.originalCid.value,
            "textCid" to candidate.extractedTextCid.value, "receiptCid" to candidate.curationReceiptCid.value,
            "quote" to text(candidate.quote), "begin" to candidate.begin, "end" to candidate.end)
    }

    fun encode(rows: List<LegalQuadCandidate>): ByteArray {
        require(rows.size <= MAX_ROWS)
        var chars = 0L
        val values = rows.map { row ->
            val map = fields(row)
            val obj = map.getValue("object") as Map<*, *>
            chars += listOf(map["subject"], map["predicate"], map["graph"], map["quote"],
                obj["value"], obj["datatype"], obj["lang"]).sumOf { (it as? String)?.length?.toLong() ?: 0L }
            require(chars <= MAX_CHARS) { "Snapshot character limit" }
            map
        }
        val bytes = CanonicalCbor.encodeMap(mapOf("kind" to KIND, "rows" to values))
        require(bytes.size <= MAX_BYTES) { "Snapshot byte limit" }
        return bytes
    }

    fun decode(bytes: ByteArray, expected: ContentId): List<LegalQuadCandidate> {
        require(bytes.size <= MAX_BYTES) { "Snapshot byte limit" }
        val snapshot = bytes.copyOf()
        require(ContentId.of(snapshot) == expected) { "Snapshot digest mismatch" }
        val root = CanonicalCbor.decodeMapBounded(snapshot, MAX_BYTES, maxDepth = 8, maxItems = 20_000)
        require(root.keys == setOf("kind", "rows") && root["kind"] == KIND) { "Invalid snapshot" }
        val rows = root["rows"] as? List<*> ?: error("Invalid snapshot rows")
        require(rows.size <= MAX_ROWS)
        fun string(map: Map<*, *>, key: String): String = map[key] as? String ?: error("Invalid $key")
        fun optional(map: Map<*, *>, key: String): String? = map[key]?.let { it as? String ?: error("Invalid $key") }
        fun offset(map: Map<*, *>, key: String): Int {
            val value = map[key] as? Long ?: error("Invalid $key")
            require(value in 0..Int.MAX_VALUE.toLong())
            return value.toInt()
        }
        val decoded = rows.map { raw ->
            val row = raw as? Map<*, *> ?: error("Invalid row")
            require(row.keys == setOf("subject", "predicate", "object", "graph", "originalCid", "textCid", "receiptCid", "quote", "begin", "end"))
            val obj = row["object"] as? Map<*, *> ?: error("Invalid object")
            val term = when (string(obj, "kind")) {
                "iri" -> { require(obj.keys == setOf("kind", "value")); RdfTerm.Iri(string(obj, "value")) }
                "literal" -> { require(obj.keys == setOf("kind", "value", "datatype", "lang")); RdfTerm.Literal(string(obj, "value"), optional(obj, "datatype"), optional(obj, "lang")) }
                else -> error("Unsupported object kind")
            }
            LegalQuadCandidate(RdfQuad(RdfTerm.Iri(string(row, "subject")), RdfTerm.Iri(string(row, "predicate")),
                term, optional(row, "graph")?.let { RdfTerm.Iri(it) }), ContentId(string(row, "originalCid")),
                ContentId(string(row, "textCid")), ContentId(string(row, "receiptCid")), string(row, "quote"),
                offset(row, "begin"), offset(row, "end"))
        }
        // Enforce both semantic limits and one canonical encoding, including unique map keys.
        require(encode(decoded).contentEquals(snapshot)) { "Noncanonical snapshot" }
        return decoded
    }
}
