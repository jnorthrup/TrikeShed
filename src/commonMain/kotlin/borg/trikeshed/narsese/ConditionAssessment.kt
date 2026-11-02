package borg.trikeshed.narsese

import borg.trikeshed.job.ContentId
import borg.trikeshed.job.CanonicalCbor
import borg.trikeshed.lcnc.CurationReviewSelection
import borg.trikeshed.lib.*

/** A person's proposed assessment, not a conclusion made by the parser. */
enum class ConditionAssessmentState { UNRESOLVED, UNCONDITIONAL, CONDITIONAL }

/** Exact [begin, end) UTF-16 offsets into the selected extracted text. */
data class ConditionSpan(val textCid: ContentId, val begin: Int, val end: Int, val quote: String)

/** The caller must bind this proposal to the authenticated reviewer and retained snapshot. */
data class ConditionAssessment(
    val snapshotCid: ContentId,
    val selectionId: String,
    val state: ConditionAssessmentState,
    val spans: List<ConditionSpan> = emptyList(),
)

enum class ConditionAssessmentStatus { COMPLETE, UNRESOLVED, INVALID, UNAVAILABLE, INTEGRITY_MISMATCH, LIMIT }
data class ConditionAssessmentResult(val status: ConditionAssessmentStatus, val unresolved: List<String> = emptyList())

data class ConditionAssessmentBatch(val snapshotCid: ContentId, val assessments: List<ConditionAssessment>)

/** Canonical, bounded draft bytes. These bytes are data and do not establish review authority. */
object ConditionAssessmentCodec {
    const val MAX_BYTES = 1_048_576
    private const val MAX_CHARS = 262_144
    private const val MAX_SELECTIONS = 256
    private const val MAX_SPANS = 16

    fun encode(snapshotCid: ContentId, assessments: Iterable<ConditionAssessment>): ByteArray {
        val rows = ArrayList<Map<String, Any?>>()
        val ids = HashSet<String>()
        var chars = 0L
        fun text(value: String, limit: Int): String {
            require(value.length <= limit && value.encodeToByteArray().decodeToString(throwOnInvalidSequence = true) == value) {
                "Invalid assessment text"
            }
            chars += value.length.toLong()
            require(chars <= MAX_CHARS) { "Assessment text limit" }
            return value
        }
        for (assessment in assessments) {
            require(rows.size < MAX_SELECTIONS) { "Assessment selection limit" }
            val id = text(assessment.selectionId, 256)
            require(id.isNotBlank() && ids.add(id) && assessment.snapshotCid == snapshotCid) { "Invalid assessment identity" }
            val spans = ArrayList<Map<String, Any?>>()
            for (span in assessment.spans) {
                require(spans.size < MAX_SPANS) { "Assessment span limit" }
                spans.add(_m["textCid" j span.textCid.value, "begin" j span.begin, "end" j span.end,
                    "quote" j text(span.quote, 32768)])
            }
            rows.add(_m["selectionId" j id, "state" j assessment.state.name, "spans" j spans])
        }
        val encoded = CanonicalCbor.encodeMap(_m["kind" j "condition-assessment-draft/1",
            "snapshotCid" j snapshotCid.value, "assessments" j rows])
        require(encoded.size <= MAX_BYTES) { "Assessment byte limit" }
        return encoded
    }

    fun decode(bytes: ByteArray): ConditionAssessmentBatch {
        require(bytes.size <= MAX_BYTES) { "Assessment byte limit" }
        val snapshot = bytes.copyOf()
        val root = CanonicalCbor.decodeMapBounded(snapshot, MAX_BYTES, maxDepth = 6, maxItems = 65_536)
        require(root.keys == setOf("kind", "snapshotCid", "assessments") && root["kind"] == "condition-assessment-draft/1")
        val snapshotCid = ContentId(root["snapshotCid"] as? String ?: error("Invalid assessment snapshot"))
        val rows = root["assessments"] as? List<*> ?: error("Invalid assessment rows")
        require(rows.size <= MAX_SELECTIONS)
        fun str(value: Map<*, *>, key: String) = value[key] as? String ?: error("Invalid assessment $key")
        fun offset(value: Map<*, *>, key: String): Int {
            val n = value[key] as? Long ?: error("Invalid assessment $key")
            require(n in 0..Int.MAX_VALUE.toLong())
            return n.toInt()
        }
        val decoded = rows.map { raw ->
            val row = raw as? Map<*, *> ?: error("Invalid assessment row")
            require(row.keys == setOf("selectionId", "state", "spans"))
            val state = ConditionAssessmentState.entries.singleOrNull { it.name == str(row, "state") }
                ?: error("Invalid assessment state")
            val rawSpans = row["spans"] as? List<*> ?: error("Invalid assessment spans")
            require(rawSpans.size <= MAX_SPANS)
            val spans = rawSpans.map { value ->
                val span = value as? Map<*, *> ?: error("Invalid assessment span")
                require(span.keys == setOf("textCid", "begin", "end", "quote"))
                ConditionSpan(ContentId(str(span, "textCid")), offset(span, "begin"), offset(span, "end"), str(span, "quote"))
            }
            ConditionAssessment(snapshotCid, str(row, "selectionId"), state, spans)
        }
        require(encode(snapshotCid, decoded).contentEquals(snapshot)) { "Noncanonical assessment" }
        return ConditionAssessmentBatch(snapshotCid, decoded)
    }
}

/**
 * Pure validation over caller-supplied selections and extracted bytes. COMPLETE means only that
 * each selection has an explicit, structurally valid assessment and exact supporting text where
 * required. It does not authenticate the reviewer or snapshot, establish source authority,
 * determine whether an assessment captures every qualification, or authorize a release. No
 * result is a rule premise. The host must obtain the exact current snapshot and authenticate its
 * reviewer before using this check; missing legacy assessments always remain unresolved.
 * The result does not authorize later use of mutable input objects: a host must freeze the
 * assessments and bind their exact bytes to the authenticated decision at the same boundary.
 */
object ConditionAssessmentCheck {
    private const val MAX_SELECTIONS = 256
    private const val MAX_SPANS = 16
    private const val MAX_QUOTE_CHARS = 32768
    private const val MAX_TEXT_BYTES = 16 * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 32 * 1024 * 1024

    fun validate(snapshotCid: ContentId, selections: Iterable<CurationReviewSelection>,
        assessments: Iterable<ConditionAssessment>, extractedTexts: Map<ContentId, ByteArray>): ConditionAssessmentResult {
        val selected = LinkedHashMap<String, CurationReviewSelection>()
        for (item in selections) {
            if (selected.size >= MAX_SELECTIONS) return ConditionAssessmentResult(ConditionAssessmentStatus.LIMIT)
            if (item.kind != "relationship" || item.id.isBlank() || item.id.length > 256 || selected.containsKey(item.id))
                return ConditionAssessmentResult(ConditionAssessmentStatus.INVALID)
            selected[item.id] = item
        }
        if (selected.isEmpty()) return ConditionAssessmentResult(ConditionAssessmentStatus.INVALID)

        // Copy bounded caller-owned lists before checking and never derive an assessment from
        // the parser's nullable `condition` field.
        val byId = LinkedHashMap<String, ConditionAssessment>()
        for (input in assessments) {
            if (byId.size >= MAX_SELECTIONS) return ConditionAssessmentResult(ConditionAssessmentStatus.LIMIT)
            if (input.snapshotCid != snapshotCid || input.selectionId !in selected || input.selectionId in byId)
                return ConditionAssessmentResult(ConditionAssessmentStatus.INVALID)
            val spans = ArrayList<ConditionSpan>()
            for (span in input.spans) {
                if (spans.size >= MAX_SPANS) return ConditionAssessmentResult(ConditionAssessmentStatus.LIMIT)
                if (span.quote.length > MAX_QUOTE_CHARS) return ConditionAssessmentResult(ConditionAssessmentStatus.LIMIT)
                spans.add(span)
            }
            if (input.state == ConditionAssessmentState.CONDITIONAL && spans.isEmpty() ||
                input.state == ConditionAssessmentState.UNCONDITIONAL && spans.isNotEmpty())
                return ConditionAssessmentResult(ConditionAssessmentStatus.INVALID)
            byId[input.selectionId] = input.copy(spans = spans)
        }

        val texts = HashMap<ContentId, String>()
        var totalBytes = 0L
        for ((id, item) in selected) {
            val q = item.quotation
            val text = texts[q.extractedTextCid] ?: run {
                val supplied = extractedTexts[q.extractedTextCid] ?: return ConditionAssessmentResult(ConditionAssessmentStatus.UNAVAILABLE)
                if (supplied.size > MAX_TEXT_BYTES) return ConditionAssessmentResult(ConditionAssessmentStatus.LIMIT)
                totalBytes += supplied.size.toLong()
                if (totalBytes > MAX_TOTAL_BYTES) return ConditionAssessmentResult(ConditionAssessmentStatus.LIMIT)
                val bytes = supplied.copyOf()
                if (ContentId.of(bytes) != q.extractedTextCid)
                    return ConditionAssessmentResult(ConditionAssessmentStatus.INTEGRITY_MISMATCH)
                val decoded = try { bytes.decodeToString(throwOnInvalidSequence = true) }
                    catch (_: Exception) { return ConditionAssessmentResult(ConditionAssessmentStatus.INVALID) }
                texts[q.extractedTextCid] = decoded
                decoded
            }
            if (q.quote.length > MAX_QUOTE_CHARS) return ConditionAssessmentResult(ConditionAssessmentStatus.LIMIT)
            if (q.quote.isBlank() || !DocumentQuotationCheck.exactSpan(text, q.begin, q.end, q.quote))
                return ConditionAssessmentResult(ConditionAssessmentStatus.INVALID)
            for (span in byId[id]?.spans.orEmpty()) {
                if (span.quote.isBlank() || span.textCid != q.extractedTextCid ||
                    !DocumentQuotationCheck.exactSpan(text, span.begin, span.end, span.quote))
                    return ConditionAssessmentResult(ConditionAssessmentStatus.INVALID)
            }
        }
        val unresolved = selected.keys.filter { byId[it]?.state == null || byId[it]?.state == ConditionAssessmentState.UNRESOLVED }
        return if (unresolved.isEmpty()) ConditionAssessmentResult(ConditionAssessmentStatus.COMPLETE)
        else ConditionAssessmentResult(ConditionAssessmentStatus.UNRESOLVED, unresolved)
    }
}
