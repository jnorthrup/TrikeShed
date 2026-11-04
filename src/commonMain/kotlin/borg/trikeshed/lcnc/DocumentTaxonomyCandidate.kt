package borg.trikeshed.lcnc

import borg.trikeshed.job.*
import borg.trikeshed.kif.*
import borg.trikeshed.lib.*
import borg.trikeshed.narsese.*

/** Exact top-level KIF forms are candidates; this does not approve or assert them. */
object DocumentTaxonomyCandidate {
    const val KIND = "taxonomy"
    const val PARTITION_KIND = "taxonomy-partition"
    const val POLICY = "positive-ground-kif/1"
    const val MAX_SOURCE_CHARS = 4 * 1024 * 1024
    const val MAX_FORMS = 65536
    const val MAX_SELECTIONS = 256
    const val PARTITION_SIZE = 32

    fun isTaxonomy(kind: String): Boolean = kind == KIND || kind == PARTITION_KIND

    private fun policy() = _m["kind" j POLICY]

    fun record(cas: CasStore, cid: ContentId): DocumentCurationRecord {
        val reader = cas as? BoundedCasReader ?: error("Bounded CAS reads are required")
        val bytes = requireNotNull(reader.getBounded(cid, 16 * 1024 * 1024)) { "Document curation receipt is unavailable" }
        require(ContentId.of(bytes) == cid) { "Document curation receipt identity differs" }
        return DocumentCuratorCodec.decodeBounded(bytes, 16 * 1024 * 1024, 64, 1_000_000)
    }

    /** Require a complete sequence of top-level lists; quoted and nested forms never become assertions. */
    private fun inspect(source: DocumentSource, accept: (CurationReviewSelection) -> Unit) {
        require(source.name.endsWith(".kif", ignoreCase = true)) { "A .kif source is required" }
        val text = source.text
        require(text.length <= MAX_SOURCE_CHARS) { "KIF source exceeds the inspection limit" }
        val textBytes = text.encodeToByteArray(throwOnInvalidSequence = true)
        require(ContentId.of(textBytes) == source.extractedTextCid && source.originalCid == source.extractedTextCid) {
            "KIF source must match the original UTF-8 bytes"
        }
        var i = 0
        var forms = 0
        fun skip() {
            while (i < text.length) when {
                text[i].isWhitespace() -> i++
                text[i] == ';' -> { while (i < text.length && text[i] != '\n' && text[i] != '\r') i++ }
                else -> return
            }
        }
        while (true) {
            skip()
            if (i == text.length) break
            require(++forms <= MAX_FORMS) { "KIF source exceeds the form limit" }
            val begin = i
            var quotedForm = false
            var quotePrefixes = 0
            while (i < text.length && text[i] == '\'') {
                quotedForm = true; quotePrefixes++; require(quotePrefixes <= 64) { "KIF quotation nesting limit" }
                i++; skip()
            }
            require(i < text.length && text[i] == '(') { "KIF source requires top-level lists" }
            var depth = 0
            var activeQuotes = 0
            var nodes = quotePrefixes
            var atom = false
            val quoteCosts = mutableListOf<Int>()
            var string = false
            var escape = false
            var comment = false
            do {
                require(i < text.length) { "Truncated KIF source" }
                val ch = text[i++]
                if (!string && !comment) {
                    if (ch == '\'') {
                        quotePrefixes++; nodes++
                        require(depth + activeQuotes + quotePrefixes <= 64) { "KIF quotation nesting limit" }
                    } else if (!ch.isWhitespace() && ch != ';' && ch != '(' && ch != ')') quotePrefixes = 0
                }
                when {
                    comment -> if (ch == '\n' || ch == '\r') comment = false
                    string -> when {
                        escape -> escape = false
                        ch == '\\' -> escape = true
                        ch == '"' -> string = false
                    }
                    ch == ';' -> { atom = false; comment = true }
                    ch == '"' -> { nodes++; atom = false; string = true }
                    ch == '(' -> {
                        nodes++; atom = false; depth++
                        quoteCosts.add(quotePrefixes); activeQuotes += quotePrefixes; quotePrefixes = 0
                        require(depth + activeQuotes <= 64) { "KIF source nesting limit" }
                    }
                    ch == ')' -> {
                        atom = false; depth--; require(depth >= 0) { "Unexpected KIF closing parenthesis" }
                        activeQuotes -= quoteCosts.removeAt(quoteCosts.lastIndex); quotePrefixes = 0
                    }
                    ch.isWhitespace() -> atom = false
                    else -> if (!atom) { nodes++; atom = true }
                }
                require(nodes <= 8192) { "KIF form exceeds the syntax node limit" }
            } while (depth > 0)
            require(!string && i - begin <= 65536) { "KIF form exceeds the inspection limit" }
            val quote = text.substring(begin, i)
            val parsed = KifExpr.parseAll(quote, strict = true)
            require(parsed.size == 1) { "Exactly one top-level KIF expression required" }
            val list = parsed.single() as? KifExpr.ListExpr
            val head = (list?.elements?.firstOrNull() as? KifExpr.Atom)?.token
            // Unrelated valid syntax is retained in the source but never interpreted as taxonomy.
            val formula = if (!quotedForm && head in _s["subclass", "instance"])
                KifSemantic.decode(quote).formula as? KifFormula.Predicate else null
            if (!quotedForm && formula != null && formula.name in _s["subclass", "instance"] &&
                formula.arguments.size == 2 && formula.arguments.all { it is KifTerm.Constant }) {
                val subject = (formula.arguments[0] as KifTerm.Constant).token
                val obj = (formula.arguments[1] as KifTerm.Constant).token
                accept(CurationReviewSelection("kif:$begin:$i", "relationship",
                    _m["format" j POLICY, "predicate" j formula.name, "subject" j subject, "object" j obj],
                    CurationReviewQuotation(source.originalCid, source.extractedTextCid, begin, i, quote)))
            }
        }
    }

    fun selections(source: DocumentSource): List<CurationReviewSelection> =
        mutableListOf<CurationReviewSelection>().also { rows -> inspect(source) { rows.add(it) } }

    /** Inspect every form, even beyond the requested window, before returning any candidates. */
    fun page(source: DocumentSource, offset: Int, limit: Int): Join<List<Map<String, Any?>>, Int> {
        require(offset >= 0 && limit in 1..MAX_SELECTIONS) { "Invalid KIF candidate page" }
        val rows = mutableListOf<Map<String, Any?>>()
        var total = 0
        inspect(source) {
            if (total >= offset && total - offset < limit) rows.add(projection(it, total / PARTITION_SIZE))
            total++
        }
        require(offset <= total) { "KIF candidate offset exceeds total" }
        return rows j total
    }

    fun candidate(cas: CasStore, receiptCid: ContentId, ids: List<String>, retainPolicy: Boolean = true,
                  kind: String = KIND): CurationReviewCandidate {
        require(isTaxonomy(kind)) { "Unknown KIF review kind" }
        val limit = if (kind == PARTITION_KIND) PARTITION_SIZE else MAX_SELECTIONS
        require(ids.isNotEmpty() && ids.size <= limit && ids.distinct().size == ids.size) {
            "Choose at most $limit distinct KIF assertions"
        }
        val record = record(cas, receiptCid)
        val source = record.source
        val reader = cas as? BoundedCasReader ?: error("Bounded CAS reads are required")
        require(source.originalCid == source.extractedTextCid) { "KIF source must match the original UTF-8 bytes" }
        val original = requireNotNull(reader.getBounded(source.originalCid, 16 * 1024 * 1024)) { "Source is unavailable" }
        require(ContentId.of(original) == source.originalCid &&
            original.contentEquals(source.text.encodeToByteArray(throwOnInvalidSequence = true))) { "Source identity differs" }
        val requested = ids.toSet()
        val choices = mutableMapOf<String, CurationReviewSelection>()
        var ordinal = 0
        var partition: Int? = null
        inspect(source) {
            if (it.id in requested) {
                val bucket = ordinal / PARTITION_SIZE
                if (kind == PARTITION_KIND) {
                    require(partition == null || partition == bucket) { "KIF assertions must belong to one partition" }
                    partition = bucket
                }
                choices[it.id] = it
            }
            ordinal++
        }
        val selected = ids.map { choices[it] ?: error("Unknown KIF assertion: $it") }.toSeries()
        val policyBytes = CanonicalCbor.encodeMap(policy())
        val policyCid = ContentId.of(policyBytes)
        if (retainPolicy) require(cas.put(policyBytes) == policyCid) { "KIF policy identity differs" }
        else require(reader.getBounded(policyCid, 4096)?.contentEquals(policyBytes) == true) { "KIF policy is unavailable" }
        val identity = source.project?.let { _m["kind" j "project", "project" j it.project, "id" j it.id] }
            ?: source.metadata["evidenceId"]?.singleOrNull()?.let { _m["kind" j "evidence", "id" j it] }
            ?: _m["kind" j "content", "cid" j source.originalCid.value]
        val family = if (kind == KIND) ContentId.of(CanonicalCbor.encodeMap(_m["kind" j "taxonomy-source-family/1", "source" j identity]))
        else ContentId.of(CanonicalCbor.encodeMap(_m["kind" j "taxonomy-partition-family/1", "source" j identity,
            "partition" j requireNotNull(partition), "size" j PARTITION_SIZE]))
        return CurationReviewCandidate(kind, "$kind/${family.value}", receiptCid, policyCid,
            DocumentReviewCandidate.revision(source), selected)
    }

    private fun projection(it: CurationReviewSelection, partition: Int): Map<String, Any?> =
        _m["id" j it.id, "kind" j it.kind, "label" j it.quotation.quote,
            "partition" j partition,
            "quotation" j _m["originalCid" j it.quotation.originalCid.value,
                "extractedTextCid" j it.quotation.extractedTextCid.value, "begin" j it.quotation.begin,
                "end" j it.quotation.end, "quote" j it.quotation.quote]]

    fun projection(source: DocumentSource): List<Map<String, Any?>> =
        selections(source).mapIndexed { index, item -> projection(item, index / PARTITION_SIZE) }
}
