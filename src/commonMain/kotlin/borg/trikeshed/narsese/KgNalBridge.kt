package borg.trikeshed.narsese

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.j

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
 *   causes / leadsTo / KIF =>         → IMPLICATION  (A ==> C)   [CAUSALITY]
 *   precedes / before / then          → PREDICTIVE   (A =/> C)   [CAUSALITY + temporal]
 *   during / while / concurrent       → CONCURRENT   (A =|> C)   [CAUSALITY + temporal]
 *   anything else                     → PRODUCT-framed inheritance ((s×o) --> p)
 *
 * Parsers are deliberately minimal (statement-level Turtle with prefix/;/,
 * support; s-expression KIF) — the bridge's value is the MAPPING, not a
 * conformance suite. Unparseable statements are skipped, never guessed.
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
            temporal = triplet.temporal
                ?: if (copula.isTemporal) TemporalSignal(TemporalGrade.RELATIVE, sourceCid = sourceCid) else null,
            provenanceCid = provenanceCid,
        )
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
            "causes", "cause", "caused", "leadsto", "leads-to", "results-in", "implies", "entails", "=>" ->
                NalCopula.IMPLICATION j RelationKind.CAUSALITY
            "precedes", "before", "then", "next", "predicts" ->
                NalCopula.PREDICTIVE_IMPLICATION j RelationKind.CAUSALITY
            "during", "while", "concurrent", "concurrent-with", "simultaneous" ->
                NalCopula.CONCURRENT_IMPLICATION j RelationKind.CAUSALITY
            "contradicts", "disjointwith", "disjoint-with", "not" -> NalCopula.SIMILARITY j RelationKind.CONTRADICTION
            else -> NalCopula.PRODUCT j RelationKind.MATCH
        }
    }

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
            val l = line.substringBefore('#').trim()
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
        val out = ArrayList<KgTriplet>()
        for (expr in topLevelSexprs(text)) {
            val parts = sexprParts(expr) ?: continue
            if (parts.size < 3) continue
            val head = parts[0].lowercase()
            when (head) {
                "instance", "subclass", "=>", "<=>" -> out.add(
                    KgTriplet(parts[1], head, parts[2], confidence = confidence),
                )
                else -> if (parts.size >= 3) out.add(
                    KgTriplet(parts[1], parts[0], parts[2], confidence = confidence),
                )
            }
        }
        return out
    }

    private fun topLevelSexprs(text: String): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        var start = -1
        for (i in text.indices) {
            when (text[i]) {
                '(' -> { if (depth == 0) start = i; depth++ }
                ')' -> {
                    depth--
                    if (depth == 0 && start >= 0) { out.add(text.substring(start, i + 1)); start = -1 }
                }
            }
        }
        return out
    }

    /** Split one s-expr into head + args; nested exprs stay as their own text. */
    private fun sexprParts(expr: String): List<String>? {
        val inner = expr.trim().removePrefix("(").removeSuffix(")").trim()
        if (inner.isEmpty()) return null
        val parts = ArrayList<String>()
        var i = 0
        while (i < inner.length) {
            when {
                inner[i].isWhitespace() -> i++
                inner[i] == '(' -> {
                    var depth = 0
                    var j = i
                    while (j < inner.length) {
                        if (inner[j] == '(') depth++
                        if (inner[j] == ')') { depth--; if (depth == 0) break }
                        j++
                    }
                    parts.add(inner.substring(i, minOf(j + 1, inner.length)))
                    i = j + 1
                }
                else -> {
                    var j = i
                    while (j < inner.length && !inner[j].isWhitespace()) j++
                    parts.add(inner.substring(i, j))
                    i = j
                }
            }
        }
        return parts
    }
}
