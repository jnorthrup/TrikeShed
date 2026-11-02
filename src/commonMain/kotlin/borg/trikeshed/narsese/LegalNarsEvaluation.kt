package borg.trikeshed.narsese

import borg.trikeshed.job.CanonicalCbor
import borg.trikeshed.job.ContentId

/** Labels are judgments about an excerpt, never legal conclusions. */
enum class LegalEvalLabel { SUPPORTED, REFUTED, UNKNOWN, CONFLICTED }
enum class LegalEvalWitnessRole { SUPPORTS, REFUTES, CONTEXT }

/** Offsets are [begin, end) UTF-16 code units in the identified extracted text. */
data class LegalEvalWitness(val excerptCid: ContentId, val begin: Int, val end: Int,
    val quote: String, val role: LegalEvalWitnessRole)

/** Expected labels and anchors are provisional human annotations, not authenticated authority. */
data class LegalEvalCase(val name: String, val proposition: String, val claimedOriginalCid: ContentId,
    val excerptCid: ContentId, val expected: LegalEvalLabel, val anchors: List<LegalEvalWitness>) {
    /** Source-scoped identity does not include the expected answer or human witness annotations. */
    val propositionCid: ContentId get() = ContentId.of(CanonicalCbor.encodeMap(mapOf(
        "kind" to "legal-eval-proposition/1", "original" to claimedOriginalCid.value,
        "excerpt" to excerptCid.value, "proposition" to proposition)))
}

/** A result supplied by an external system. An omitted prediction is not UNKNOWN. */
data class LegalEvalPrediction(val propositionCid: ContentId, val label: LegalEvalLabel,
    val witnesses: List<LegalEvalWitness>)

enum class LegalEvalStatus { MISSING, INVALID_FIXTURE, INVALID_PREDICTION, SCORED }
data class LegalEvalRow(val name: String, val status: LegalEvalStatus, val predicted: LegalEvalLabel? = null,
    val labelAgrees: Boolean? = null, val witnessIntegrity: Boolean? = null,
    val acceptedWitness: Boolean? = null, val groundedAgreement: Boolean? = null)
data class LegalEvalReport(val rows: List<LegalEvalRow>, val missing: Int, val invalidFixtures: Int,
    val invalidPredictions: Int, val scored: Int, val labelAgreements: Int, val groundedAgreements: Int)

/**
 * Offline comparison of supplied answers to provisional annotations. Exact text only verifies
 * a quote's location; agreement with an annotated role is not proof of semantic or legal truth.
 * Claimed original identities and source-to-excerpt extraction lineage are not verified here.
 * No result is inserted into a bag, aggregated as independent evidence, or treated as a rule.
 */
object LegalNarsEvaluation {
    private const val MAX_CASES = 1_024
    private const val MAX_PREDICTIONS = 4_096
    private const val MAX_WITNESSES = 64
    private const val MAX_EXCERPT_BYTES = 16 * 1024 * 1024
    private const val MAX_TOTAL_EXCERPT_BYTES = 64 * 1024 * 1024
    private const val MAX_TEXT_CHARS = 32_768

    fun score(cases: List<LegalEvalCase>, excerpts: Map<ContentId, ByteArray>,
        predictions: List<LegalEvalPrediction>): LegalEvalReport {
        require(cases.size <= MAX_CASES && predictions.size <= MAX_PREDICTIONS) { "Evaluation item limit" }
        require(cases.all { it.name.isNotBlank() && it.name.length <= 256 &&
            it.proposition.isNotBlank() && it.proposition.length <= MAX_TEXT_CHARS &&
            runCatching { it.proposition.encodeToByteArray(throwOnInvalidSequence = true) }.isSuccess }) { "Invalid case identity" }
        require(cases.map { it.name }.distinct().size == cases.size &&
            cases.map { it.propositionCid }.distinct().size == cases.size) { "Duplicate case identity" }
        val byProposition = cases.associateBy { it.propositionCid }
        val submitted = predictions.groupBy { it.propositionCid }
        var invalidPredictions = predictions.count { it.propositionCid !in byProposition }
        val textCache = HashMap<ContentId, String?>()
        var totalExcerptBytes = 0
        fun text(cid: ContentId): String? {
            if (textCache.containsKey(cid)) return textCache[cid]
            val input = excerpts[cid]
            val decoded = if (input == null || input.size > MAX_EXCERPT_BYTES ||
                input.size > MAX_TOTAL_EXCERPT_BYTES - totalExcerptBytes) null else {
                totalExcerptBytes += input.size
                val bytes = input.copyOf()
                if (ContentId.of(bytes) != cid) null
                else try { bytes.decodeToString(throwOnInvalidSequence = true) } catch (_: Exception) { null }
            }
            textCache[cid] = decoded
            return decoded
        }
        fun exact(w: LegalEvalWitness, cid: ContentId, content: String): Boolean =
            w.excerptCid == cid && w.quote.length <= MAX_TEXT_CHARS &&
                DocumentQuotationCheck.exactSpan(content, w.begin, w.end, w.quote)
        fun sufficient(label: LegalEvalLabel, witnesses: List<LegalEvalWitness>): Boolean = when (label) {
            LegalEvalLabel.SUPPORTED -> witnesses.any { it.role == LegalEvalWitnessRole.SUPPORTS }
            LegalEvalLabel.REFUTED -> witnesses.any { it.role == LegalEvalWitnessRole.REFUTES }
            LegalEvalLabel.CONFLICTED -> witnesses.any { it.role == LegalEvalWitnessRole.SUPPORTS } &&
                witnesses.any { it.role == LegalEvalWitnessRole.REFUTES }
            LegalEvalLabel.UNKNOWN -> witnesses.all { it.role == LegalEvalWitnessRole.CONTEXT }
        }
        val rows = cases.map { c ->
            val content = text(c.excerptCid)
            if (content == null || c.anchors.size > MAX_WITNESSES || c.anchors.distinct().size != c.anchors.size ||
                !c.anchors.all { exact(it, c.excerptCid, content) } || !sufficient(c.expected, c.anchors)) {
                LegalEvalRow(c.name, LegalEvalStatus.INVALID_FIXTURE)
            } else {
                val entries = submitted[c.propositionCid].orEmpty()
                when {
                    entries.isEmpty() -> LegalEvalRow(c.name, LegalEvalStatus.MISSING)
                    entries.size != 1 -> {
                        invalidPredictions += entries.size
                        LegalEvalRow(c.name, LegalEvalStatus.INVALID_PREDICTION)
                    }
                    else -> {
                        val p = entries.single()
                        val integrity = p.witnesses.size <= MAX_WITNESSES && p.witnesses.distinct().size == p.witnesses.size &&
                            p.witnesses.all { exact(it, c.excerptCid, content) }
                        // Role is a predicted claim. Matching both span and role to an annotated
                        // anchor is a separate test from whether the excerpt substring is exact.
                        val accepted = integrity && sufficient(p.label, p.witnesses) && p.witnesses.all { it in c.anchors }
                        val agrees = p.label == c.expected
                        LegalEvalRow(c.name, LegalEvalStatus.SCORED, p.label, agrees, integrity, accepted, agrees && accepted)
                    }
                }
            }
        }
        return LegalEvalReport(rows, rows.count { it.status == LegalEvalStatus.MISSING },
            rows.count { it.status == LegalEvalStatus.INVALID_FIXTURE }, invalidPredictions,
            rows.count { it.status == LegalEvalStatus.SCORED }, rows.count { it.labelAgrees == true },
            rows.count { it.groundedAgreement == true })
    }
}
