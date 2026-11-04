package borg.trikeshed.narsese

import borg.trikeshed.job.*
import borg.trikeshed.kif.*
import borg.trikeshed.lib.*
import borg.trikeshed.ontology.SumoOntology

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
        val t = text.trimStart()
        return when {
            t.startsWith("(") -> KgFormat.KIF
            KgFormat.TURTLE.marker in text -> KgFormat.TURTLE
            KgFormat.RDF_XML.marker in text -> KgFormat.RDF_XML
            KgFormat.JSON_LD.marker in text -> KgFormat.JSON_LD
            KgFormat.TRIPLET_JSON.marker in text -> KgFormat.TRIPLET_JSON
            KgFormat.N_TRIPLES.marker in text -> KgFormat.N_TRIPLES
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

    fun parseTurtle(text: String, confidence: Float = 0.9f): List<KgTriplet> {
        val prefixes = HashMap<String, String>()
        val out = ArrayList<KgTriplet>()
        // strip comments, gather prefixes
        val body = StringBuilder()
        for (line in text.lines()) {
            val l = stripComment(line).trim()
            if (l.isEmpty()) continue
            val prefixMatch = Regex("""@prefix\s+(\S*):\s*<([^>]*)>\s*\.""").find(l)
            if (prefixMatch != null) {
                prefixes[prefixMatch.groupValues[1]] = prefixMatch.groupValues[2]
                continue
            }
            body.append(l).append(' ')
        }
        fun term(raw: String): String {
            val r = raw.trim()
            if (r.startsWith('<') && r.endsWith('>')) return r.substring(1, r.length - 1)
            if (r.startsWith('"')) return r.trim('"')
            val colon = r.indexOf(':')
            if (colon > -1) {
                val pre = r.substring(0, colon)
                prefixes[pre]?.let { return it + r.substring(colon + 1) }
            }
            return r
        }
        for (statement in body.toString().split(Regex("""\.\s""")).map { it.trim() }.filter { it.isNotEmpty() }) {
            var subject: String? = null
            for (clause in statement.split(';').map { it.trim() }.filter { it.isNotEmpty() }) {
                val tokens = tokenize(clause)
                if (subject == null) {
                    if (tokens.size < 3) break
                    subject = term(tokens[0])
                    emitObjects(out, subject, term(expandA(tokens[1])), tokens.drop(2), ::term, confidence)
                } else {
                    if (tokens.size < 2) continue
                    emitObjects(out, subject, term(expandA(tokens[0])), tokens.drop(1), ::term, confidence)
                }
            }
        }
        return out
    }

    private fun expandA(p: String): String = if (p == "a") "rdf:type" else p

    /**
     * A `#` starts a comment only OUTSIDE an IRI or a literal. Hash IRIs are
     * the common case (`<https://trikeshed.borg/lcnc#n1>`, `rdf:type` itself
     * is `…22-rdf-syntax-ns#type`), and `substringBefore('#')` chopped every
     * such line at its first fragment — the LCNC causal projection bridged to
     * zero statements while the sniff said TURTLE.
     */
    private fun stripComment(line: String): String {
        var inIri = false; var inLit = false; var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inLit -> { if (c == '\\') i++ else if (c == '"') inLit = false }
                inIri -> if (c == '>') inIri = false
                c == '<' -> inIri = true
                c == '"' -> inLit = true
                c == '#' -> return line.substring(0, i)
            }
            i++
        }
        return line
    }

    private fun emitObjects(
        out: MutableList<KgTriplet>,
        subject: String,
        predicate: String,
        objectTokens: List<String>,
        term: (String) -> String,
        confidence: Float,
    ) {
        for (objRaw in objectTokens.joinToString(" ").split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
            out.add(KgTriplet(subject, predicate, term(objRaw), confidence = confidence))
        }
    }

    private fun tokenize(clause: String): List<String> {
        val tokens = ArrayList<String>()
        var i = 0
        val n = clause.length
        while (i < n) {
            when {
                clause[i].isWhitespace() -> i++
                clause[i] == '"' -> {
                    val end = clause.indexOf('"', i + 1).let { if (it < 0) n - 1 else it }
                    tokens.add(clause.substring(i, minOf(end + 1, n)))
                    i = end + 1
                }
                clause[i] == '<' -> {
                    val end = clause.indexOf('>', i).let { if (it < 0) n - 1 else it }
                    tokens.add(clause.substring(i, minOf(end + 1, n)))
                    i = end + 1
                }
                else -> {
                    var j = i
                    while (j < n && !clause[j].isWhitespace()) j++
                    tokens.add(clause.substring(i, j))
                    i = j
                }
            }
        }
        return tokens
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
