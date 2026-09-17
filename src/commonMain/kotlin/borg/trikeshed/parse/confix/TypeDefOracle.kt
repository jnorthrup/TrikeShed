@file:Suppress("NonAsciiCharacters", "UNCHECKED_CAST")

package borg.trikeshed.parse.confix

import borg.trikeshed.cursor.*
import borg.trikeshed.lib.*
import borg.trikeshed.lib.`▶`
import borg.trikeshed.parse.kursive.legacy.JursiveCharSeries
import borg.trikeshed.parse.kursive.legacy.KursiveParser
import borg.trikeshed.parse.kursive.legacy.parser
import borg.trikeshed.parse.kursive.std

// ─────────────────────────────────────────────────────────────────────────────
// TYPEDEF ORACLE — typedef collection + lattice build
// ─────────────────────────────────────────────────────────────────────────────
//
// Collects typedef declarations from .x source text (or CBOR module
// descriptors) and feeds them into an IsALattice for type subsumption queries.
//
// Each typedef creates an IsAEdge:
//   typedef Join<T, T> as Twin<T>  →  Twin IS-A Join
//   typedef Series<RowVec> as Cursor  →  Cursor IS-A Series

// ── TypeDefParam — a type parameter on a typedef ─────────────────────────────

data class TypeDefParam(val name: String, val bound: String? = null)

// ── TypeDefEntry — a single typedef declaration ───────────────────────────────

data class TypeDefEntry(
    val name: String,
    val referredToType: String,
    val params: Series<TypeDefParam>,
    val source: String,
)

// ── TypeDefOracleK — facet keys for TypeDefOracleRow ───────────────

sealed class TypeDefOracleK<out R> : OpK<R>() {
    data object Entries   : TypeDefOracleK<Series<TypeDefEntry>>()
    data object Tokens    : TypeDefOracleK<Series<TypeToken>>()
    data object Names     : TypeDefOracleK<(TypeToken) -> String>()
    data object ByName    : TypeDefOracleK<(String) -> TypeToken?>()
    data object Lattice   : TypeDefOracleK<IsALattice>()
    data object EdgeCount : TypeDefOracleK<Int>()
}

// ── TypeDefOracle ──────────────────────────────────────────────────────────────

class TypeDefOracle {
    private val entries  = mutableListOf<TypeDefEntry>()
    private val nameToIdx = LinkedHashMap<String, Int>()
    private val idxToName = mutableListOf<String>()
    private val edges    = mutableListOf<IsAEdge>()

    // ── parseTypeDefs — scan .x source for typedef declarations ─────────────

    companion object {
        private data class ParsedDeclaration(
            val name: String,
            val referredTo: String,
            val params: String?,
        )

        private val requiredWhitespace: KursiveParser<CharSeries> =
            std.takeWhile("declarationWhitespace", min = 1) { it.isWhitespace() }
        private val typedefKeyword = std.lit("typedef")
        private val typealiasKeyword = std.lit("typealias")
        private val asKeyword = std.lit("as")
        private val openTypeParameters = std.ch('<')
        private val closeTypeParameters = std.ch('>')
        private val semicolon = std.ch(';')
        private val equals = std.ch('=')
        private val typeParameterBody = std.takeUntil("typeParameterBody", min = 1) { it == '>' }
        private val typealiasRhs = std.takeWhile("typealiasRhs", min = 1) { true }

        private val identifier: KursiveParser<CharSeries> = parser("identifier") { input ->
            val start = input.pos
            val first = input.peek() ?: return@parser null
            if (!isIdentifierStart(first)) return@parser null
            input.advance()
            while (input.peek()?.let(::isIdentifierPart) == true) input.advance()
            input.slice(start)
        }

        private val typeParameters: KursiveParser<CharSeries> = parser("typeParameters") { input ->
            if (openTypeParameters(input) == null) return@parser null
            val body = typeParameterBody(input)
                ?: return@parser null
            if (closeTypeParameters(input) == null) return@parser null
            body
        }

        private val referredType: KursiveParser<CharSeries> = parser("referredType") { input ->
            val start = input.pos
            val source = input.source
            var cursor = start
            while (cursor < source.limit) {
                if (source[cursor].isWhitespace()) {
                    var keyword = cursor
                    while (keyword < source.limit && source[keyword].isWhitespace()) keyword++
                    val after = keyword + 2
                    if (matchesAt(source, keyword, "as") &&
                        after < source.limit && source[after].isWhitespace()
                    ) {
                        if (cursor > start) {
                            source.pos = cursor
                            return@parser input.slice(start)
                        }
                    }
                }
                cursor++
            }
            null
        }

        private val typedefDeclaration: KursiveParser<ParsedDeclaration> = parser("typedefDeclaration") { input ->
            std.ws(input)
            if (typedefKeyword(input) == null) return@parser null
            requiredWhitespace(input) ?: return@parser null
            val referred = referredType(input) ?: return@parser null
            requiredWhitespace(input) ?: return@parser null
            if (asKeyword(input) == null) return@parser null
            requiredWhitespace(input) ?: return@parser null
            val name = identifier(input) ?: return@parser null
            val params = if (input.peek() == '<') {
                typeParameters(input) ?: return@parser null
            } else {
                null
            }
            std.ws(input)
            if (semicolon(input) == null) return@parser null
            ParsedDeclaration(name.asString(), referred.asString(), params?.asString())
        }

        private val typealiasDeclaration: KursiveParser<ParsedDeclaration> = parser("typealiasDeclaration") { input ->
            std.ws(input)
            if (typealiasKeyword(input) == null) return@parser null
            requiredWhitespace(input) ?: return@parser null
            val name = identifier(input) ?: return@parser null
            val params = if (input.peek() == '<') {
                typeParameters(input) ?: return@parser null
            } else {
                null
            }
            std.ws(input)
            if (equals(input) == null) return@parser null
            std.ws(input)
            val referred = typealiasRhs(input) ?: return@parser null
            ParsedDeclaration(name.asString(), referred.trim.asString(), params?.asString())
        }

        private val sourceLine: KursiveParser<CharSeries> = std.restOfLine("sourceLine")

        private fun isIdentifierStart(char: Char): Boolean =
            char == '_' || char in 'A'..'Z' || char in 'a'..'z'

        private fun isIdentifierPart(char: Char): Boolean =
            isIdentifierStart(char) || char in '0'..'9'

        private fun matchesAt(source: CharSeries, offset: Int, literal: String): Boolean {
            if (offset < 0 || offset + literal.length > source.limit) return false
            for (index in literal.indices) if (source[offset + index] != literal[index]) return false
            return true
        }

        private val topicPattern = Regex("""^topic:(\w+)\s+as\s+(\w+)""")
        private val typeParamPattern = Regex("<[^>]*>")
        private val typeParenPattern = Regex("\\([^)]*\\)")
        private val typeSplitPattern = Regex("""[|,\s]+""")
    }

    fun parseTypeDefs(text: String, source: String = "<unknown>") {
        val input = JursiveCharSeries(text.toSeries())
        while (input.hasRemaining) {
            val line = sourceLine(input)
            if (line != null) {
                val declarationInput = JursiveCharSeries(line as Series<Char>)
                val declaration = typedefDeclaration(declarationInput)
                    ?: typealiasDeclaration(declarationInput)
                if (declaration != null) addEntry(
                    declaration.name,
                    declaration.referredTo,
                    declaration.params,
                    source,
                )
            }
            if (input.hasRemaining) {
                check(std.lineBreak(input) != null) { "Expected a source line break" }
            }
        }
    }

    // ── parseCBORTypeDefs ─────────────────────────────────────────────────────

    fun parseCBORTypeDefs(doc: ConfixDoc) {
        val roots = doc.roots
        for (row in roots.`▶`) {
            if (row.tag != IOMemento.IoObject) continue
            val kids = row.kids
            var name: String? = null
            var referredTo: String? = null
            var params: String? = null
            var k = 0
            while (k + 1 < kids.size) {
                val keyRow = kids[k]
                val valRow = kids[k + 1]
                if (keyRow.tag == IOMemento.IoString) {
                    val keyText = doc.src.let { s ->
                        val o = keyRow.open
                        val c = keyRow.close
                        CharArray(c - o) { j -> s[o + j].toInt().toChar() }.concatToString()
                    }
                    when (keyText) {
                        "name"       -> name = valRow.reify(doc.src)?.toString()
                        "referredTo" -> referredTo = valRow.reify(doc.src)?.toString()
                        "params"     -> params = valRow.reify(doc.src)?.toString()
                    }
                }
                k += 2
            }
            if (name != null && referredTo != null) {
                addEntry(name, referredTo, params, "cbor")
            }
        }
    }

    // ── ingestOracleJson ──────────────────────────────────────────────────────

    fun ingestOracleJson(jsonText: String) {
        val doc = confixDoc(jsonText)
        var rows: Series<ConfixCell>? = doc.docAt("rows")?.cellKids
        if (rows == null && doc.roots.size > 0) {
            val root = doc.roots[0]
            if (root.tag == IOMemento.IoObject) {
                for (i in 0 until root.kids.size step 2) {
                    val kCell = root.kids[i]
                    val k = kCell.reify(doc.src)
                    val keyStr = if (k is String) k else {
                        val o = kCell.open; val c = kCell.close
                        if (o < c && o >= 0 && c <= doc.src.size) CharArray(c - o) { j -> doc.src[o + j].toInt().toChar() }.concatToString() else ""
                    }
                    if (keyStr == "rows") { rows = (root.kids[i + 1] j doc.src).cellKids; break }
                }
            }
        }
        if (rows == null) return

        for (elem in rows.`▶`) {
            val kind  = elem["kind"]?.reify() as? String
            val ngram = elem["ngram"]?.reify() as? String
            if (kind == null || ngram == null) continue

            when (kind) {
                "ngram", "factory_step" -> ngram.split(" -> ").map { it.trim() }.zipWithNext().forEach { (s, p) ->
                    if (s.isNotBlank() && p.isNotBlank()) addLinkCheck(s, p)
                }
                "isA_edge" -> ngram.split(" -> ").takeIf { it.size >= 2 }?.let { (c, p) ->
                    addLinkCheck(c, p)
                }
                "topic" -> topicPattern.find(ngram)?.let { mr ->
                    addEntry(mr.groupValues[2], mr.groupValues[1], null, "lda-topic")
                }
            }
        }
    }

    // ── build ─────────────────────────────────────────────────────────────────

    fun build(): TypeDefOracleRow {
        for (entry in entries) {
            val nameToken = ensureToken(entry.name)
            for (referred in extractBaseNames(entry.referredToType)) {
                val referredToken = ensureToken(referred)
                if (nameToken != referredToken) {
                    edges.add(nameToken edgeTo referredToken)
                }
            }
        }

        val edgeSeries: Series<IsAEdge> = edges.toSeries()
        val lattice    = IsALattice(edgeSeries)
        // Freeze mutable accumulators into Series (Collection.toSeries snapshots
        // via toList internally — PRELOAD maxim: read-only end state is Series).
        val capturedEntries: Series<TypeDefEntry> = entries.toSeries()
        val capturedNames:   Series<String>      = idxToName.toSeries()
        val capturedByName  = LinkedHashMap(nameToIdx)

        return capturedEntries.size j { op: Any? ->
            @Suppress("UNCHECKED_CAST")
            when (op) {
                TypeDefOracleK.Entries   -> capturedEntries
                TypeDefOracleK.Tokens    -> (capturedNames.size j { i: Int -> TypeToken(i) })
                TypeDefOracleK.Names     -> ({ token: TypeToken ->
                    if (token.poolIdx in 0 until capturedNames.size) capturedNames[token.poolIdx] else "?$token"
                })
                TypeDefOracleK.ByName    -> ({ name: String ->
                    capturedByName[name]?.let { TypeToken(it) }
                })
                TypeDefOracleK.Lattice   -> lattice
                TypeDefOracleK.EdgeCount -> edges.size
                else                     -> null
            }
        }
    }

    // ── addLinkCheck ─────────────────────────────────────────────────────────

    fun addLinkCheck(sub: String, sup: String) {
        val subToken = ensureToken(sub)
        val supToken = ensureToken(sup)
        edges.add(subToken edgeTo supToken)
    }

    // ── internal ─────────────────────────────────────────────────────────────

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
        if (paramsStr.isNullOrBlank()) return 0 j { error("empty") }
        val parts = paramsStr.split(',').map { it.trim() }
        return parts.size j { i: Int ->
            val p = parts[i]
            val bound = if (p.contains(':')) p.substringAfter(':').trim() else null
            TypeDefParam(p.substringBefore(':').trim(), bound)
        }
    }

    private fun extractBaseNames(typeExpr: String): List<String> {
        val cleaned = typeExpr
            .replace(typeParamPattern, "")
            .replace(typeParenPattern, "")
        return cleaned.split(typeSplitPattern)
            .filter { it.isNotBlank() && it.first().isLetter() }
            .map { it.trim() }
    }

    val size: Int get() = entries.size
}

// ── TypeDefOracleRow — the faceted row returned by build() ─────────

typealias TypeDefOracleRow = FacetedRow<Any>

// ── Faceted accessors for TypeDefOracleRow ────────────────────────────

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
