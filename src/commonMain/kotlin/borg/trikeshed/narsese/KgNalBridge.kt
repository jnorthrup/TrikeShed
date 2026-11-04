package borg.trikeshed.narsese

import borg.trikeshed.job.*
import borg.trikeshed.kif.*
import borg.trikeshed.lib.*
import borg.trikeshed.ontology.SumoOntology
import borg.trikeshed.parse.reifyStrict
import borg.trikeshed.rdf.*

/**
 * KgNalBridge — minimize the gap between interchange KR (Turtle/RDF, KIF, …)
 * and NAL causality. A triple is not "a MATCH": its predicate names a COPULA,
 * and the copula decides truth semantics, temporal grade, and which
 * RelationKind the belief carries. This bridge is where that survives the
 * crossing instead of being flattened.
 *
 * Predicate → copula ladder (NAL levels, NalTerm.kt):
 *   rdf:type / a / instance(-of)      → INHERITANCE  (s --> C)
 *   rdfs:subClassOf / subclass        → INHERITANCE  (A --> B)
 *   owl:sameAs / equivalent / KIF <=> → SIMILARITY   (a <-> b)
 *   causes / leadsTo                  → IMPLICATION  (A ==> C)   [CAUSALITY]
 *   implies / entails / KIF =>        → IMPLICATION  (A ==> C)   [MATCH]
 *   before / during / predicts       → PRODUCT-framed inheritance, preserving the predicate
 *   anything else                     → PRODUCT-framed inheritance ((s×o) --> p)
 *
 * Parsers are deliberately minimal (statement-level Turtle with prefix/;/,
 * support; s-expression KIF) — the bridge's value is the MAPPING, not a
 * conformance suite. The legacy KIF triplet crossing accepts only bounded, ground
 * binary scalar predicates; unsupported scope/arity refuses the entire crossing.
 */
object KgNalBridge {

    /** One statement carried across the gap with its copula intact. */
    data class NalMapped(
        val triplet: KgTriplet,
        val copula: NalCopula,
        val relation: RelationKind,
    ) {
        /** Human gloss in Narsese surface syntax — render-ready. */
        fun gloss(): String = when (copula) {
            NalCopula.PRODUCT -> "(*, ${triplet.subject}, ${triplet.obj}) --> ${triplet.predicate}"
            else -> "${triplet.subject} ${copula.symbol} ${triplet.obj}"
        }

        private fun temporal(sourceCid: String): TemporalSignal? = triplet.temporal
            ?: if (copula.isTemporal) TemporalSignal(TemporalGrade.RELATIVE, sourceCid = sourceCid) else null

        /**
         * Exact lexical proposition in a DECLARED source context, independent of confidence,
         * evidence, observation receipts and attention. Different documents are conservatively
         * distinct contexts; this does not authenticate the source or retain this payload in CAS.
         * Raw triple terms retain the existing parser's limits (including RDF term-kind fidelity).
         */
        fun propositionCid(sourceCid: String): ContentId {
            val qualification = temporal(sourceCid)
            for (value in s_[sourceCid, triplet.subject, triplet.predicate, triplet.obj, triplet.subjectCid,
                triplet.objectCid, qualification?.validFrom, qualification?.validUntil, qualification?.sourceCid].view)
                value?.encodeToByteArray(throwOnInvalidSequence = true)
            return ContentId.of(CanonicalCbor.encodeMap(_m[
                "kind" j "kg-source-proposition/1", "sourceContext" j sourceCid,
                "predicate" j triplet.predicate, "subject" j triplet.subject, "object" j triplet.obj,
                "subjectCid" j triplet.subjectCid, "objectCid" j triplet.objectCid,
                "copula" j copula.name, "relation" j relation.name,
                "temporal" j qualification?.let { _m["grade" j it.grade.name, "validFrom" j it.validFrom,
                    "validUntil" j it.validUntil, "sourceCid" j it.sourceCid] },
            ]))
        }

        /**
         * The belief: copula-aware coordinate, confidence evidence, temporal from the copula.
         *
         * Polarity is honest at the mint: a CONTRADICTION relation (contradicts /
         * disjointWith) is a NEGATIVE assertion about the association, so its
         * confidence weight lands as w− (EvidenceCoord(0, w)), not w+. Same
         * magnitude the confidence gives — only the polarity flips. This puts
         * Nal.truthOf(evidence).frequency below 0.5, which is what makes such
         * statements surface on the refutation front of resonate(); the relation
         * tag still drives the Contradicted event and render exclusion.
         */
        fun signal(sourceCid: String, provenanceCid: String? = null): SemanticSignal = SemanticSignal(
            angular = AngularCodec.encode(
                relation = relation,
                taxonomyKey = "kg/${copula.name.lowercase()}",
                subjectTerm = triplet.subject,
                objectTerm = triplet.obj,
                grade = triplet.temporal?.grade ?: if (copula.isTemporal) TemporalGrade.RELATIVE else TemporalGrade.NONE,
            ),
            evidence = triplet.evidence().let { e ->
                if (relation == RelationKind.CONTRADICTION) EvidenceCoord(0L, e.positive) else e
            },
            relation = relation,
            subjectCid = triplet.subjectCid ?: sourceCid,
            objectCid = triplet.objectCid,
            temporal = temporal(sourceCid),
            provenanceCid = provenanceCid,
            propositionCid = propositionCid(sourceCid).value,
        )
    }

    // ── the mapping (the gap itself) ──────────────────────────────────

    // ── pre-LLM corpus → EternalRule pipeline ──────────────────────────

    /**
     * Emit the SUMO upper spine as EternalRules. The decedent's will,
     * inscribed in the Suggested Upper Merged Ontology — each subclass
     * axiom becomes an INHERITANCE eternal truth.
     */
    fun emitSumoSpine(kifText: String = SumoOntology.emitUpperKif()): Series<EternalRule> {
        val exprs = KifExpr.parseAll(kifText)
        val out = mutableListOf<EternalRule>()
        for (expr in exprs) {
            EternalRule.fromKif(expr)?.let { out.add(it) }
        }
        return out.size j { out[it] }
    }

    /**
     * Transcribe CycL text to EternalRules. The will translated from
     * one dead language to another — CycL → KIF → NAL.
     */
    fun cyclToEternalRules(cyclText: String): Series<EternalRule> {
        val cyclExprs = KifExpr.parseAll(cyclText)
        val out = mutableListOf<EternalRule>()
        for (expr in cyclExprs) {
            EternalRule.fromCycl(expr)?.let { out.add(it) }
        }
        return out.size j { out[it] }
    }

    /**
     * Bridge pre-LLM RDF triples to EternalRules. Each triple's predicate
     * is mapped to a NalCopula via [mapPredicate]. The rete currently labels
     * every firing causal and its rule type does not preserve the relation.
     * Until it does, logical implications cannot safely enter that rete: keep
     * them non-eternal here, while [map] retains their implication semantics.
     */
    fun tripletsToEternalRules(
        triplets: List<KgTriplet>,
        evidenceWeight: Long = Nal.UNIT,
    ): Series<EternalRule> {
        val out = mutableListOf<EternalRule>()
        for (t in triplets) {
            val (copula, relation) = mapPredicate(t.predicate)
            out.add(EternalRule.fromTriplet(t, copula, relation, evidenceWeight))
        }
        return out.size j { out[it] }
    }

    /**
     * Parse + bridge the whole crossing: pre-LLM text → NalMapped signals.
     * The will of the decedent, crossed into NAL — one statement at a time.
     * Unparseable statements are skipped, never guessed.
     */
    fun bridgeToRules(text: String, confidence: Float = 0.9f): Series<EternalRule> {
        val triplets = when (sniff(text)) {
            KgFormat.KIF -> parseKif(text, confidence)
            KgFormat.TURTLE, KgFormat.N_TRIPLES -> parseTurtle(text, confidence)
            else -> return emptySeriesOf()
        }
        return tripletsToEternalRules(triplets)
    }

    // ── the mapping (the gap itself) ──────────────────────────────────

    /** Predicate → (copula, relation) as a Join — heterogeneous pair, destructured at binding site. */
    fun mapPredicate(rawPredicate: String): Join<NalCopula, RelationKind> {
        val p = rawPredicate.substringAfterLast('/').substringAfterLast('#').substringAfterLast(':')
            .trim().lowercase().replace('_', '-')
        return when (p) {
            "type", "a", "instance", "instance-of", "isa", "is-a" -> NalCopula.INHERITANCE j RelationKind.MATCH
            "subclassof", "subclass", "subclass-of", "subpropertyof" -> NalCopula.INHERITANCE j RelationKind.MATCH
            "sameas", "same-as", "equivalent", "equivalentclass", "equal", "<=>" -> NalCopula.SIMILARITY j RelationKind.MATCH
            "causes", "cause", "caused", "leadsto", "leads-to", "results-in" ->
                NalCopula.IMPLICATION j RelationKind.CAUSALITY
            "implies", "entails", "=>" -> NalCopula.IMPLICATION j RelationKind.MATCH
            "contradicts", "disjointwith", "disjoint-with", "not" -> NalCopula.SIMILARITY j RelationKind.CONTRADICTION
            else -> NalCopula.PRODUCT j RelationKind.MATCH
        }
    }

    internal fun causalRuleCopula(copula: NalCopula, relation: RelationKind): NalCopula =
        if (copula == NalCopula.IMPLICATION && relation != RelationKind.CAUSALITY) NalCopula.PRODUCT else copula

    fun map(triplet: KgTriplet): NalMapped {
        val (copula, relation) = mapPredicate(triplet.predicate)
        return NalMapped(triplet, copula, relation)
    }

    // ── format sniff ──────────────────────────────────────────────────

    fun sniff(text: String): KgFormat? {
        var offset = 0
        while (offset < text.length) {
            while (offset < text.length && text[offset].isWhitespace()) offset++
            if (offset == text.length || text[offset] != '#') break
            while (offset < text.length && text[offset] != '\n' && text[offset] != '\r') offset++
        }
        val t = text.substring(offset)
        return when {
            t.startsWith("(") -> KgFormat.KIF
            t.startsWith(KgFormat.TURTLE.marker) -> KgFormat.TURTLE
            t.startsWith("{") || t.startsWith("[") -> {
                // Confix validates the complete JSON; only root-object keys select a format.
                // Literal values and keys nested inside another property are source data.
                val root = reifyStrict(t)
                val objects = when (root) {
                    is Map<*, *> -> s_[root]
                    is List<*> -> root.toSeries() α { it as? Map<*, *> }
                    else -> return null
                }
                when {
                    objects.view.any { it?.containsKey("@context") == true } -> KgFormat.JSON_LD
                    objects.view.any { it?.containsKey("subject") == true } -> KgFormat.TRIPLET_JSON
                    else -> null
                }
            }
            t.startsWith(KgFormat.RDF_XML.marker) && t.getOrNull(KgFormat.RDF_XML.marker.length)?.let {
                it.isWhitespace() || it == '>' || it == '/'
            } == true || t.startsWith("<?xml") && KgFormat.RDF_XML.marker in t -> KgFormat.RDF_XML
            t.startsWith(KgFormat.N_TRIPLES.marker) -> KgFormat.N_TRIPLES
            Regex("""^\S+\s+\S+\s+.+\s*\.\s*$""", RegexOption.MULTILINE).containsMatchIn(t) -> KgFormat.TURTLE
            else -> null
        }
    }

    /** Parse + map: the whole crossing in one call. Unparseable statements are skipped. */
    fun bridge(text: String, confidence: Float = 0.9f): List<NalMapped> = when (sniff(text)) {
        KgFormat.KIF -> parseKif(text, confidence).map(::map)
        KgFormat.TURTLE, KgFormat.N_TRIPLES -> parseTurtle(text, confidence).map(::map)
        else -> emptyList()
    }

    // ── Turtle-lite ───────────────────────────────────────────────────

    fun parseTurtle(text: String, confidence: Float = 0.9f): List<KgTriplet> =
        TurtleRdf.parseStrict(text).triples.map { triple ->
            // Predicate remains its expanded IRI for copula mapping. Role terms retain their
            // RDF kind, language/datatype and lexical value in a canonical Turtle spelling.
            KgTriplet(canonicalTerm(triple.s), triple.p.iri, canonicalTerm(triple.o), confidence = confidence)
        }

    private fun canonicalTerm(term: RdfTerm): String {
        val lexical = buildString {
            for (ch in term.toTurtle()) if (ch.code < 32)
                append("\\u").append(ch.code.toString(16).padStart(4, '0')) else append(ch)
        }
        require(TurtleRdf.parseTerm(lexical) == term) { "RDF term cannot round-trip without loss" }
        return lexical
    }

    // ── KIF-lite ──────────────────────────────────────────────────────

    fun parseKif(text: String, confidence: Float = 0.9f): List<KgTriplet> {
        // Decode/bound the COMPLETE input before returning any row. No negative/modal/conditional
        // wrapper, additional argument or malformed tail may silently become a positive triplet.
        return KifSemantic.decodeAll(text).map { decoded ->
            val predicate = decoded.formula as? KifFormula.Predicate
                ?: throw IllegalArgumentException("Legacy KG triplets do not interpret scoped KIF formulas")
            require(predicate.arguments.size == 2 && decoded.freeVariables.isEmpty() &&
                predicate.arguments.all { it is KifTerm.Constant || it is KifTerm.Literal }) {
                "Legacy KG triplets require ground binary scalar KIF predicates"
            }
            val syntax = decoded.syntax as KifExpr.ListExpr
            KgTriplet(syntax.elements[1].toKifString(), predicate.name, syntax.elements[2].toKifString(), confidence = confidence)
        }
    }

}
