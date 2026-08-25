package borg.trikeshed.narsese

/**
 * ForgeKgIngest — the Forge-observable trigger: when a formal knowledge graph
 * appears in ingest, decompose it into SemanticSignals and CAS receipts.
 *
 * Data-only shapes here; the actual ingest pipeline lives in the daemon
 * (CCEK element), not in this module. This file defines the contract: what a
 * detected KG looks like, what signals it produces, and what the board sees.
 */

/**
 * KgDetection — evidence that an ingested document is a formal knowledge graph.
 *
 * Detection is by content shape, not file extension: RDF/XML root elements,
 * Turtle @prefix declarations, JSON-LD @context with graph keywords, or a
 * TripletStore-shaped JSON array with subject/predicate/object fields.
 */
enum class KgFormat(val marker: String) {
    RDF_XML("<rdf:RDF"),
    TURTLE("@prefix"),
    JSON_LD("\"@context\""),
    TRIPLET_JSON("\"subject\""),
    N_TRIPLES("_:"),

    /** KIF / SUO-KIF s-expressions — bridged by KgNalBridge (=>, <=>, instance, subclass). */
    KIF("(=>"),
}

/**
 * KgIngestReceipt — the CAS receipt emitted when a KG is detected and decomposed.
 *
 * @param sourceCid ContentId of the ingested document
 * @param format detected format
 * @param tripleCount number of triplets extracted
 * @param signalCids ContentIds of the SemanticSignals produced (one per triplet)
 * @param provenanceCid ContentId of the ingest pipeline stage that ran detection
 */
data class KgIngestReceipt(
    val sourceCid: String,
    val format: KgFormat,
    val tripleCount: Int,
    val signalCids: List<String>,
    val provenanceCid: String? = null,
)

/**
 * Decompose a detected KG document into SemanticSignals.
 *
 * Each triplet becomes one signal: angular identity from the subject-predicate
 * hash, evidence from the extraction method's grade, temporal qualification
 * from the temporal rubric if the extractor produced temporal bounds.
 *
 * This is a pure function: (triplets, sourceCid) → signals. The caller
 * (a CCEK element in the daemon) owns the CAS writes and channel fan-out.
 */
fun decomposeKg(
    triplets: List<KgTriplet>,
    sourceCid: String,
    provenanceCid: String? = null,
): List<SemanticSignal> = triplets.map { triplet ->
    SemanticSignal(
        angular = triplet.angularIdentity(),
        evidence = triplet.evidence(),
        relation = RelationKind.MATCH,
        subjectCid = triplet.subjectCid ?: sourceCid,
        objectCid = triplet.objectCid,
        temporal = triplet.temporal,
        provenanceCid = provenanceCid,
    )
}

/**
 * KgTriplet — one extracted triplet with optional CAS anchoring.
 *
 * Unlike Semantica's Triplet (plain strings), every field that can be
 * CAS-anchored carries a ContentId. Unanchored mentions are explicitly
 * null — never fabricated spans.
 */
data class KgTriplet(
    val subject: String,
    val predicate: String,
    val obj: String,
    val subjectCid: String? = null,
    val objectCid: String? = null,
    val confidence: Float = 0.9f,
    val temporal: TemporalSignal? = null,
) {
    /** Angular identity: FNV-1a hash of subject+predicate, stable across sessions. */
    fun angularIdentity(): Long {
        var h = -3750763034362895579L  // FNV-1a offset basis
        for (c in subject) { h = h xor c.code.toLong(); h *= 1099511628211L }
        for (c in predicate) { h = h xor c.code.toLong(); h *= 1099511628211L }
        return h
    }

    /** Evidence from confidence: confidence as positive evidence weight. */
    fun evidence(): EvidenceCoord =
        EvidenceCoord((confidence.coerceIn(0f, 1f) * 1000).toLong(), 0L)
}
