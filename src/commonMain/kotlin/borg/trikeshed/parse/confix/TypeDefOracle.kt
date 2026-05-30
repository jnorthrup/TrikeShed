@file:Suppress("NonAsciiCharacters", "UNCHECKED_CAST")

package borg.trikeshed.parse.confix

import borg.trikeshed.cursor.*
import borg.trikeshed.lib.*

// ── TypeDefOracle — typedef collection + link check feeder ────────
//
// Collects typedef declarations from .x source text (or CBOR module
// descriptors) and feeds them into an IsALattice for type subsumption
// queries.
//
// Architecture:
//
//   1. parseTypeDefs(text)  — scan .x source for typedef declarations
//   2. TypeDefEntry         — (name, referredToType, params, source)
//   3. TypeDefOracle        — pool of TypeTokens + edges + lattice
//   4. IsALattice.isA()     — the link check query
//
// The oracle bridges the xvm typedef algebra into zTrike's type
// subsumption staircase.  Each typedef creates an IsAEdge:
//
//   typedef Join<T,T> as Twin<T>  →  Twin IS-A Join
//   typedef Series<RowVec> as Cursor  →  Cursor IS-A Series
//
// Parameters are tracked as TypeDefParam entries for later use in
// parameterized type checking and ccallsite replumbing.

// ── TypeDefParam — a type parameter on a typedef ──────────────────

data class TypeDefParam(val name: String, val bound: String? = null)

// ── TypeDefEntry — a single typedef declaration ───────────────────

data class TypeDefEntry(
    val name: String,
    val referredToType: String,
    val params: Series<TypeDefParam>,
    val source: String,
)

// ── TypeDefOracleK — facet keys for the oracle FacetedRow ─────────

sealed class TypeDefOracleK<out R> : OpK<R>() {
    data object Entries      : TypeDefOracleK<Series<TypeDefEntry>>()
    data object Tokens       : TypeDefOracleK<Series<TypeToken>>()
    data object Names        : TypeDefOracleK<(TypeToken) -> String>()
    data object ByName       : TypeDefOracleK<(String) -> TypeToken?>()
    data object Lattice      : TypeDefOracleK<IsALattice>()
    data object EdgeCount    : TypeDefOracleK<Int>()
}

// ── TypeDefOracle ─────────────────────────────────────────────────

class TypeDefOracle {
    private val entries = mutableListOf<TypeDefEntry>()
    private val nameToIdx = LinkedHashMap<String, Int>()
    private val idxToName = mutableListOf<String>()
    private val edges = mutableListOf<IsAEdge>()

    // ── parseTypeDefs — scan .x source for typedef declarations ───
    //
    // Handles both forms:
    //   typedef Join<A, B> as Tuple<A, B>     (xvm .x style)
    //   typedef Join<T, T> as Twin<T>          (RFC-1 style, reversed)
    //   typedef String|Int as StringOrInt      (union typealias)

    fun parseTypeDefs(text: String, source: String = "<unknown>") {
        // .x typedef pattern: typedef <typeExpr> as <name>;
        val typedefPattern = Regex(
            """(?m)^\s*typedef\s+(.+?)\s+as\s+([A-Za-z_]\w*)(?:<([^>]+)>)?\s*;"""
        )
        for (m in typedefPattern.findAll(text)) {
            val referredTo = m.groupValues[1].trim()
            val name = m.groupValues[2].trim()
            val paramsStr = m.groupValues[3]
            addEntry(name, referredTo, paramsStr, source)
        }

        // Kotlin typealias pattern: typealias <name><params?> = <typeExpr>
        val typealiasPattern = Regex(
            """(?m)^\s*typealias\s+([A-Za-z_]\w*)(?:<([^>]+)>)?\s*=\s*(.+?)$"""
        )
        for (m in typealiasPattern.findAll(text)) {
            val name = m.groupValues[1].trim()
            val paramsStr = m.groupValues[2]
            val referredTo = m.groupValues[3].trim()
            addEntry(name, referredTo, paramsStr, source)
        }
    }

    // ── parseCBORTypeDefs — ingest typedefs from CBOR module descriptor ─
    //
    // The CBOR module descriptor carries typedef info as a map:
    //   { "name": "Cursor", "referredTo": "Series<RowVec>", "params": [] }
    // This method parses a ConfixDoc produced from such a descriptor.

    fun parseCBORTypeDefs(doc: ConfixDoc) {
        val roots = doc.roots
        for (i in 0 until roots.size) {
            val row = roots[i]
            if (row.tag != IOMemento.IoObject) continue
            // try to extract name and referredTo from child cells
            val kids = row.kids
            var name: String? = null
            var referredTo: String? = null
            var params: String? = null
            var k = 0
            while (k + 1 < kids.size) {
                val keyRow = kids[k]
                val valRow = kids[k + 1]
                if (keyRow.tag == IOMemento.IoString) {
                    val keyText = doc.docSrc.let { s ->
                        val o = keyRow[0].a as Int
                        val c = keyRow[1].a as Int
                        CharArray(c - o) { j -> s[o + j].toInt().toChar() }.concatToString()
                    }
                    when (keyText) {
                        "name" -> name = valRow.reify(doc.docSrc)?.toString()
                        "referredTo" -> referredTo = valRow.reify(doc.docSrc)?.toString()
                        "params" -> params = valRow.reify(doc.docSrc)?.toString()
                    }
                }
                k += 2
            }
            if (name != null && referredTo != null) {
                addEntry(name, referredTo, params, "cbor")
            }
        }
    }

    // ── addLinkCheck — add an explicit isA edge ───────────────────
    //
    // For link checks that don't come from typedefs (e.g., class
    // hierarchy edges from xvm TypeInfo), add them directly.

    fun addLinkCheck(sub: String, sup: String) {
        val subToken = ensureToken(sub)
        val supToken = ensureToken(sup)
        edges.add(subToken.edgeTo(supToken))
    }

    // ── ingestOracleJson — consume ORACLE_ROWVEC_JSON from types_code.lda ───
    // Populates the oracle from the JSON emitted by types_code.lda.
    // This wires the LDA n-gram call-site model directly into the
    // TypeDefOracle lattice without going through source parsing.
    //
    // Expected JSON schema (from types_code.lda oracle_rowvec_model()):
    //   {
    //     "columns": ["kind","rank","ngram","count","weight","builder","root","samples"],
    //     "rows": [
    //       {"kind": "ngram",     "ngram": "A -> B -> C", "count": 5, ...},
    //       {"kind": "isA_edge",  "ngram": "Child -> Parent", "count": 3, ...},
    //       {"kind": "topic",     "ngram": "topic:typedef-staircase", ...},
    //       {"kind": "factory_step", "ngram": "X -> Y", "count": 2, ...},
    //     ]
    //   }

    fun ingestOracleJson(jsonText: String) {
        val doc = confixDoc(jsonText)
        val rows: Series<JsonElement> = doc.docAt("rows")?.cellKids ?: return
        for (i in 0 until rows.size) {
            val elem = rows[i]                           // JsonElement = ConfixCell
            val kind = elem["kind"]?.reify() as? String  // no src arg needed
            val ngram = elem["ngram"]?.reify() as? String
            if (kind == null || ngram == null) continue

            when (kind) {
                "ngram", "factory_step" -> ngram.split(" -> ").map { it.trim() }.zipWithNext().forEach { (s, p) -> if (s.isNotBlank() && p.isNotBlank()) addLinkCheck(s, p) }
                "isA_edge"             -> ngram.split(" -> ").takeIf { it.size >= 2 }?.let { (c, p) -> addLinkCheck(c, p) }
                "topic"                -> Regex("""^topic:(\w+)\s+as\s+(\w+)""").find(ngram)?.let { mr -> addEntry(mr.groupValues[2], mr.groupValues[1], null, "lda-topic") }
            }
        }
    }

    // ── build — materialize the oracle as a FacetedRow ────────────

    fun build(): FacetedRow<Any> {
        // resolve referred-to types into edges
        for (entry in entries) {
            val nameToken = ensureToken(entry.name)
            val referredNames = extractBaseNames(entry.referredToType)
            for (referred in referredNames) {
                val referredToken = ensureToken(referred)
                if (nameToken != referredToken) {
                    edges.add(nameToken.edgeTo(referredToken))
                }
            }
        }

        val edgeSeries: Series<IsAEdge> = edges.size j { i -> edges[i] }
        val lattice = IsALattice(edgeSeries)
        val capturedEntries = entries.toList()
        val capturedNames = idxToName.toList()
        val capturedByName = LinkedHashMap(nameToIdx)

        return capturedEntries.size j { op: Any? ->
            when (op) {
                TypeDefOracleK.Entries -> (capturedEntries.size j { i: Int ->
                    capturedEntries[i]
                })
                TypeDefOracleK.Tokens -> (capturedNames.size j { i: Int ->
                    TypeToken(i)
                })
                TypeDefOracleK.Names -> ({ token: TypeToken ->
                    if (token.poolIdx in capturedNames.indices) capturedNames[token.poolIdx] else "?$token"
                })
                TypeDefOracleK.ByName -> ({ name: String ->
                    capturedByName[name]?.let { TypeToken(it) }
                })
                TypeDefOracleK.Lattice -> lattice
                TypeDefOracleK.EdgeCount -> edges.size
                else                        -> null
            }
        }
    }

    // ── internal ──────────────────────────────────────────────────

    private fun addEntry(name: String, referredTo: String, paramsStr: String?, source: String) {
        val params = parseParams(paramsStr)
        entries.add(TypeDefEntry(name, referredTo, params, source))
        ensureToken(name)
    }

    private fun ensureToken(name: String): TypeToken {
        val idx = nameToIdx.getOrPut(name) {
            idxToName.add(name)
            idxToName.lastIndex
        }
        return TypeToken(idx)
    }

    private fun parseParams(paramsStr: String?): Series<TypeDefParam> {
        if (paramsStr.isNullOrBlank()) return 0 j { _: Int -> error("empty") }
        val parts = paramsStr.split(',').map { it.trim() }
        return parts.size j { i: Int ->
            val p = parts[i]
            val bound = if (p.contains(':')) p.substringAfter(':').trim() else null
            TypeDefParam(p.substringBefore(':').trim(), bound)
        }
    }

    /** Extract base type names from a type expression like "Series<RowVec>" → ["Series", "RowVec"] */
    private fun extractBaseNames(typeExpr: String): List<String> {
        // strip angle brackets recursively, split on | and ,
        val cleaned = typeExpr
            .replace(Regex("""<[^>]*>"""), "")
            .replace(Regex("""\([^)]*\)"""), "")
        return cleaned.split(Regex("""[|,\s]+"""))
            .filter { it.isNotBlank() && it.first().isLetter() }
            .map { it.trim() }
    }

    val size: Int get() = entries.size
}

// ── TypeDefOracleRow — the faceted row returned by build() ─────────

typealias TypeDefOracleRow = FacetedRow<Any>

// ── Faceted accessors for TypeDefOracle ────────────────────────────

@Suppress("UNCHECKED_CAST")
val TypeDefOracleRow.entries: Series<TypeDefEntry>
    get() = b(TypeDefOracleK.Entries) as Series<TypeDefEntry>

@Suppress("UNCHECKED_CAST")
val TypeDefOracleRow.tokens: Series<TypeToken>
    get() = b(TypeDefOracleK.Tokens) as Series<TypeToken>

@Suppress("UNCHECKED_CAST")
val TypeDefOracleRow.tdNames: (TypeToken) -> String
    get() = b(TypeDefOracleK.Names) as (TypeToken) -> String

@Suppress("UNCHECKED_CAST")
val TypeDefOracleRow.byName: (String) -> TypeToken?
    get() = b(TypeDefOracleK.ByName) as (String) -> TypeToken?

@Suppress("UNCHECKED_CAST")
val TypeDefOracleRow.lattice: IsALattice
    get() = b(TypeDefOracleK.Lattice) as IsALattice

@Suppress("UNCHECKED_CAST")
val TypeDefOracleRow.edgeCount: Int
    get() = b(TypeDefOracleK.EdgeCount) as Int
