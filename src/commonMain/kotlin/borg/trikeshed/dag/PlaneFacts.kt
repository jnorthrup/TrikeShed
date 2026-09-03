package borg.trikeshed.dag

import borg.trikeshed.cursor.BlackboardContext
import borg.trikeshed.job.ContentId
import borg.trikeshed.kif.KifExpr
import borg.trikeshed.parse.json.JsonSupport
import borg.trikeshed.rdf.RdfGraph
import borg.trikeshed.rdf.RdfTerm
import borg.trikeshed.rdf.RdfTriple
import borg.trikeshed.rdf.RdfVocab
import borg.trikeshed.rdf.TurtleRdf

/**
 * Conventions every plane bridge obeys. The fact type itself is [ReteStoredFact]:
 * the Rete fact IS the join between the blackboard, the LCNC canvases and the
 * runtime — productions watch it (field == value per partition, the only gate
 * [ReteNetwork] has), versioning and retraction live on it and nowhere else,
 * and RDF ([toTriples]) and KIF ([toKif]) are PROJECTIONS computed from it by
 * one function each, never authored.
 *
 * Partition = [BlackboardContext.id] = [FactId.partitionId] (enforced by
 * [ReteWorkingMemory.assert]). Four reserved fields sit on every plane fact:
 * [KIND] is the interest handle, [KEY] the inverse pointer back to the thing
 * (blackboard key, panel name, graal accumulator id), [ACTOR] the provenance,
 * [AT_MS] the stamp.
 *
 * Projection rules, one table for both targets:
 *  - a scalar field (String, Number, Boolean) is one triple / one KIF tuple;
 *  - a list-valued field fans out to one triple / tuple PER ELEMENT;
 *  - a map-valued field (or a nested list/map inside a list) collapses to one
 *    literal holding its canonical JSON ([canonicalJson]);
 *  - a null field emits nothing (RDF has no null; the fact still carries it and
 *    [versionOf] still hashes it);
 *  - a ContentId collapses to its `sha256:` text.
 * So `toTriples(f).size == toKif(f).size == scalars + list elements`, and a
 * KIF tuple is ALWAYS arity 3 — `(field <factIri> value)` — which is what
 * [borg.trikeshed.narsese.KgNalBridge.parseKif] reads losslessly. KIF strings
 * become bare atoms when the KIF tokenizer would read them back as one token
 * (so `(kind ?f cable)` unifies on the daemon bank), and quoted otherwise.
 */
object PlaneFacts {
    // partitions (BlackboardContext.id == FactId.partitionId)
    /** daemonBlackboard keys, one fact per admitted key. */
    const val BLACKBOARD = "blackboard"
    /** LCNC canvases, exploded to program / node / cable / violation facts. */
    const val PANELS = "panels"
    /** Runtime state (memory, gc, jit, deopt, alloc), pointcut landings, site heat. */
    const val GRAAL = "graal"

    // reserved field names — present on EVERY plane fact
    /** The interest handle: `field == value` is the only gate a production has. */
    const val KIND = "kind"
    /** The inverse pointer: blackboard key | panel name | graal id. */
    const val KEY = "key"
    /** Provenance: language / facet id / "jvmvitals". */
    const val ACTOR = "actor"
    const val AT_MS = "atMs"

    /** Subject namespace: `fact:<partition>/<localId>` lives under this IRI. */
    const val FACT_NS = "https://trikeshed.borg/fact/"
    /** Predicate namespace: one IRI per field name. */
    const val FIELD_NS = "https://trikeshed.borg/plane#"

    /** Turtle prefixes for the two namespaces, on top of [TurtleRdf.defaultPrefixes]. */
    val PREFIXES: Map<String, String> = TurtleRdf.defaultPrefixes() + mapOf("fact" to FACT_NS, "plane" to FIELD_NS)

    private const val XSD_INTEGER = RdfVocab.XSD + "integer"
    private const val XSD_DOUBLE = RdfVocab.XSD + "double"
    private const val XSD_BOOLEAN = RdfVocab.XSD + "boolean"

    /**
     * Build a plane fact. `versionCid` defaults to [versionOf] the fields, so a
     * bridge that re-derives the same fields lands on the same cid and the
     * network's assert is a no-op.
     */
    fun fact(
        partition: String,
        localId: String,
        fields: Map<String, Any?>,
        versionCid: ContentId = versionOf(fields),
    ): ReteStoredFact = ReteStoredFact(FactId(partition, localId), fields, versionCid, BlackboardContext(partition))

    /**
     * versionCid = sha256 of the canonical JSON of `fields` with keys sorted —
     * nested maps sorted too. Same value => same cid => assert is a no-op.
     */
    fun versionOf(fields: Map<String, Any?>): ContentId =
        ContentId.of(canonicalJson(fields).encodeToByteArray())

    /**
     * [JsonSupport.stringify] over a copy whose maps are key-sorted at every
     * depth; lists keep their order (order is meaning in a list). Non-JSON
     * scalars (a [ContentId], any other object) print through `toString`.
     */
    fun canonicalJson(value: Any?): String = JsonSupport.stringify(canonicalize(value))

    private fun canonicalize(value: Any?): Any? = when (value) {
        null, is String, is Number, is Boolean -> value
        is ContentId -> value.value
        is Map<*, *> -> {
            val sorted = value.entries.map { (k, v) -> k.toString() to canonicalize(v) }.sortedBy { it.first }
            val out = LinkedHashMap<String, Any?>(sorted.size)
            for ((k, v) in sorted) out[k] = v
            out
        }
        is Iterable<*> -> value.map(::canonicalize)
        is Array<*> -> value.map(::canonicalize)
        else -> value.toString()
    }

    /** Identity: fact -> (partition, key). A fact without a [KEY] field answers with its localId (couch/board facts predate the reserved fields). */
    fun keyOf(f: ReteStoredFact): Pair<String, String> =
        f.factId.partitionId to ((f.fields[KEY] as? String) ?: f.factId.localId)

    /** `<fact:partition/localId>`; the localId is percent-encoded so nothing in it can end the IRI or split a KIF token. */
    fun factIri(factId: FactId): RdfTerm.Iri = RdfTerm.Iri(FACT_NS + encodeIriPart(factId.partitionId) + "/" + encodeIriPart(factId.localId))

    fun factIri(f: ReteStoredFact): RdfTerm.Iri = factIri(f.factId)

    /** `<plane#field>`, percent-encoded the same way. */
    fun fieldIri(field: String): RdfTerm.Iri = RdfTerm.Iri(FIELD_NS + encodeIriPart(field))

    /** Inverse of [factIri]: the (partition, localId) an IRI under [FACT_NS] names, or null if it is not one of ours. */
    fun factIdOf(iri: RdfTerm.Iri): FactId? {
        if (!iri.iri.startsWith(FACT_NS)) return null
        val rest = iri.iri.removePrefix(FACT_NS)
        val slash = rest.indexOf('/')
        if (slash <= 0) return null
        return FactId(decodeIriPart(rest.substring(0, slash)), decodeIriPart(rest.substring(slash + 1)))
    }

    /**
     * Keeps `A-Za-z0-9 - . _ ~ : @ + = , ! $ & *`; everything else — including
     * `/` (it separates partition from localId), `<` `>` (they end an IRI, see
     * LcncRdf.kindIri), `%`, `?` (a KIF variable), `;` `(` `)` `'` `"` and
     * whitespace (KIF token breaks) and every non-ASCII byte — is `%XX` of its
     * UTF-8 bytes.
     */
    fun encodeIriPart(s: String): String = buildString(s.length + 8) {
        for (byte in s.encodeToByteArray()) {
            val v = byte.toInt() and 0xFF
            if (v < 128 && IRI_SAFE[v]) append(v.toChar())
            else {
                append('%')
                append(HEX[v shr 4])
                append(HEX[v and 0x0F])
            }
        }
    }

    fun decodeIriPart(s: String): String {
        val bytes = ByteArray(s.length)
        var n = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val hi = HEX.indexOf(s[i + 1].lowercaseChar())
                val lo = HEX.indexOf(s[i + 2].lowercaseChar())
                if (hi >= 0 && lo >= 0) {
                    bytes[n++] = ((hi shl 4) or lo).toByte()
                    i += 3
                    continue
                }
            }
            // ASCII passthrough (encoded parts never contain raw non-ASCII)
            bytes[n++] = c.code.toByte()
            i++
        }
        return bytes.decodeToString(0, n)
    }

    private val HEX = "0123456789abcdef"
    private val IRI_SAFE = BooleanArray(128).also { safe ->
        for (c in 'A'..'Z') safe[c.code] = true
        for (c in 'a'..'z') safe[c.code] = true
        for (c in '0'..'9') safe[c.code] = true
        for (c in "-._~:@+=,!$&*") safe[c.code] = true
    }

    // ── RDF projection ────────────────────────────────────────────────────

    /**
     * RDF projection: `<fact:partition/localId> <plane#field> literal`, one
     * triple per scalar field in field-name order; list-valued fields fan out
     * per element (see the class doc for the full table). Every triple this
     * returns survives `TurtleRdf.emit` → `TurtleRdf.parse` unchanged.
     */
    fun toTriples(f: ReteStoredFact): List<RdfTriple> {
        val subject = factIri(f.factId)
        val out = ArrayList<RdfTriple>(f.fields.size)
        for ((name, value) in f.fields.entries.sortedBy { it.key }) {
            val predicate = fieldIri(name)
            forEachProjectedValue(value) { out.add(RdfTriple(subject, predicate, literalOf(it))) }
        }
        return out
    }

    /** Turtle of the projections of many facts, with [PREFIXES]. */
    fun toTurtle(facts: List<ReteStoredFact>): String =
        TurtleRdf.emit(RdfGraph(facts.flatMap(::toTriples)), PREFIXES)

    private fun literalOf(v: Any): RdfTerm.Literal = when (v) {
        is String -> RdfTerm.Literal(v)
        is Boolean -> RdfTerm.Literal(v.toString(), datatype = XSD_BOOLEAN)
        is Int, is Long, is Short, is Byte -> RdfTerm.Literal(v.toString(), datatype = XSD_INTEGER)
        is Float, is Double -> RdfTerm.Literal(v.toString(), datatype = XSD_DOUBLE)
        is Number -> RdfTerm.Literal(v.toString(), datatype = XSD_DOUBLE)
        is ContentId -> RdfTerm.Literal(v.value)
        else -> RdfTerm.Literal(canonicalJson(v))
    }

    // ── KIF projection ────────────────────────────────────────────────────

    /**
     * KIF projection: `(kind <factIri> K)` first, then `(field <factIri> value)`
     * per scalar field in field-name order, list fields fanned out per element.
     * Arity is always 3. A fact with no [KIND] field has no leading kind tuple.
     * Every expression here re-parses (`KifExpr.parse(e.toKifString()) == e`),
     * so `KifKnowledgeBase.assert(expr)` and `assertKif(expr.toKifString())`
     * land the same string in the bank.
     */
    fun toKif(f: ReteStoredFact): List<KifExpr> {
        val subject = KifExpr.Atom(factIri(f.factId).iri)
        val out = ArrayList<KifExpr>(f.fields.size)
        val kind = f.fields[KIND]
        if (kind != null) forEachProjectedValue(kind) { out.add(tuple(KIND, subject, it)) }
        for ((name, value) in f.fields.entries.sortedBy { it.key }) {
            if (name == KIND) continue
            forEachProjectedValue(value) { out.add(tuple(name, subject, it)) }
        }
        return out
    }

    private fun tuple(field: String, subject: KifExpr.Atom, value: Any): KifExpr.ListExpr =
        KifExpr.ListExpr(listOf(KifExpr.Atom(kifFieldToken(field)), subject, kifValue(value)))

    /** A field name as a relation token: bare when the tokenizer reads it as one token, else percent-encoded. */
    private fun kifFieldToken(field: String): String = if (isBareToken(field)) field else encodeIriPart(field)

    private fun kifValue(v: Any): KifExpr = when (v) {
        is String -> if (isBareToken(v)) KifExpr.Atom(v) else KifExpr.Atom(quoteKif(v))
        is Boolean, is Number -> KifExpr.Atom(v.toString())
        is ContentId -> KifExpr.Atom(v.value)
        else -> KifExpr.Atom(quoteKif(canonicalJson(v)))
    }

    /** True when the KIF tokenizer would read `s` back as exactly one atom token that is not a variable or a quote. */
    private fun isBareToken(s: String): Boolean {
        if (s.isEmpty() || s[0] == '?' || s[0] == '\'' || s[0] == '"') return false
        for (c in s) if (c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == '(' || c == ')' || c == '"' || c == ';') return false
        return true
    }

    private fun quoteKif(s: String): String = buildString(s.length + 2) {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(c)
        }
        append('"')
    }

    // ── shared fan-out ────────────────────────────────────────────────────

    /** Applies the projection table: skip null, fan out lists, pass everything else through once. */
    private inline fun forEachProjectedValue(value: Any?, emit: (Any) -> Unit) {
        when (value) {
            null -> Unit
            is Iterable<*> -> for (e in value) if (e != null) emit(e)
            is Array<*> -> for (e in value) if (e != null) emit(e)
            else -> emit(value)
        }
    }
}
