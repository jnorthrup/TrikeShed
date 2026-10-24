package borg.trikeshed.agent.modelmux

import borg.trikeshed.cas.SourceLine
import borg.trikeshed.cas.SourceRange
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import kotlin.math.ln
import kotlin.math.min

/**
 * What a projection may spend. [maxChars] bounds the RENDERED projection exactly (headers included), so a caller
 * can hand it to a model without a second accounting pass. [maxLines] bounds the number of selected lines (adjacent
 * ones are merged into one excerpt). [neighborLines] is how far, in non-blank lines, a
 * protected constraint is searched for around a selected line.
 */
data class ProjectionBudget(
    val maxChars: Int = 2_000,
    val maxLines: Int = 24,
    val neighborLines: Int = 1,
) {
    init {
        require(maxChars >= 0 && maxLines > 0 && neighborLines >= 0)
    }
}

/**
 * Whole original lines of one pinned document, with exact provenance: [range] indexes the document's original text
 * (UTF-16 units) so `text == original.substring(range.start, range.end)` always holds, and [documentCid] names the
 * exact bytes that range is valid for. Lines are never clipped.
 */
class Excerpt(
    val document: String,
    val documentCid: ContentId,
    /** Raw line numbers, 0-based, as `String.lineSequence()` numbers them. */
    val firstLine: Int,
    val lastLine: Int,
    val range: SourceRange,
    val text: String,
    val score: Double,
    val reasons: List<String>,
) {
    /** What a reader sees. The header is part of the cost, so budgets are exact. */
    fun rendered(): String = "@ $document:${firstLine + 1}-${lastLine + 1}\n$text\n"
}

/**
 * A task-conditioned view over a pinned snapshot. Pure function of (snapshot, question, budget, [policy]); nothing
 * here is evidence, it points back at evidence. [droppedConstraints] counts protected neighbours (negations,
 * limits, error conditions) that were wanted but did not fit: when it is non-zero the projection is NOT a faithful
 * summary of its matches and [requiresExpansion] says so.
 */
class Projection(
    val policy: String,
    val question: String,
    val excerpts: Series<Excerpt>,
    val charsUsed: Int,
    val omittedCandidates: Int,
    val droppedConstraints: Int,
) {
    val requiresExpansion: Boolean get() = omittedCandidates > 0 || droppedConstraints > 0

    fun render(): String {
        val out = StringBuilder()
        for (i in 0 until excerpts.size) out.append(excerpts[i].rendered())
        return out.toString()
    }
}

/**
 * Deterministic extractive projection. No model, no randomness, no network.
 *
 * Score of a line = sum over query terms present of  weight(term) * saturate(tf, length), where
 *  - weight(term) = idf(term) * (1 + 0.05 * min(len(term), 12))   rare terms and longer (more specific) terms count more,
 *  - saturate     = BM25 term-frequency saturation (k1 = 1.2, b = 0.75),
 *  - lines that open with an action verb get a 10% boost (an imperative states something to do).
 * Corpus statistics are taken over DISTINCT line contents in the snapshot, so a line copied into ten documents is
 * counted once and cannot inflate or deflate anyone's popularity. Identical lines are also shown once.
 *
 * Protected constraints travel with their match: a line beside a selected line that carries a negation, an error
 * condition or a numeric limit is selected with it if it fits, and counted in [Projection.droppedConstraints] if not.
 */
object BreadcrumbProjector {
    const val POLICY = "lexical-1"

    internal const val K1 = 1.2
    internal const val B = 0.75

    internal val STOP = setOf(
        "the", "an", "of", "to", "in", "is", "are", "was", "be", "for", "on", "and", "or", "it",
        "this", "that", "with", "as", "by", "at", "from", "if", "then", "do", "does", "did", "so",
    )

    internal val PROTECTED = setOf(
        "not", "no", "never", "must", "only", "cannot", "without", "except", "unless", "forbidden",
        "required", "require", "requires", "error", "errors", "fail", "fails", "failed", "failure",
        "exception", "deny", "denied", "reject", "rejected", "rejects", "invalid", "unsafe",
    )

    internal val LIMIT_WORDS = setOf(
        "max", "min", "limit", "limits", "maximum", "minimum", "most", "least", "within", "under",
        "over", "exceeds", "exceed", "bytes", "ms", "seconds",
    )

    internal val ACTION = setOf(
        "add", "remove", "delete", "fix", "run", "load", "fetch", "publish", "pin", "verify", "reject",
        "validate", "acquire", "write", "read", "merge", "build", "test", "install", "configure", "start",
        "stop", "create", "update", "check", "ensure", "use", "call", "return", "throw", "send",
    )

    /** Lowercased word pieces: identifiers are split at `_`, case changes and letter/digit edges; stopwords and 1-letter noise dropped. */
    internal fun tokens(text: String): List<String> {
        val out = ArrayList<String>()
        val word = StringBuilder()
        for (ch in text) {
            if (ch.isLetterOrDigit() || ch == '_') word.append(ch) else {
                if (word.isNotEmpty()) { splitIdentifier(word.toString(), out); word.clear() }
            }
        }
        if (word.isNotEmpty()) splitIdentifier(word.toString(), out)
        return out
    }

    private fun splitIdentifier(word: String, out: MutableList<String>) {
        var start = 0
        fun emit(end: Int) {
            val piece = word.substring(start, end).trim('_').lowercase()
            if (piece.isNotEmpty() && (piece.length >= 2 || piece[0].isDigit()) && piece !in STOP) out.add(piece)
        }
        for (i in 1 until word.length) {
            val p = word[i - 1]
            val c = word[i]
            val next = word.getOrNull(i + 1)
            val boundary = p == '_' || c == '_' ||
                (p.isLowerCase() && c.isUpperCase()) ||
                (p.isLetter() && c.isDigit()) || (p.isDigit() && c.isLetter()) ||
                (p.isUpperCase() && c.isUpperCase() && next != null && next.isLowerCase())
            if (boundary) { emit(i); start = i }
        }
        emit(word.length)
    }

    internal fun isProtected(tokens: List<String>): Boolean =
        tokens.any { it in PROTECTED } ||
            (tokens.any { t -> t.all { it.isDigit() } && t.length >= 2 } && tokens.any { it in LIMIT_WORDS })

    /** The parts of a document one projection needs; its text is decoded only if an excerpt is taken from it. */
    private class DocView(val doc: ContextDocument, val facet: DocumentFacet) {
        val name: String get() = doc.name
        val cid: ContentId get() = doc.cid
        val lines: Series<SourceLine> get() = facet.lines
        val text: String by lazy(LazyThreadSafetyMode.NONE) { doc.text }
        fun lineText(ordinal: Int): String = text.substring(lines[ordinal].line.start, lines[ordinal].line.end)
    }

    private class Pick(val doc: Int, val ordinal: Int, val score: Double, val reasons: MutableList<String>)

    /** Lexical features of one trimmed line. They are part of the policy: change these rules and [POLICY] together. */
    internal fun featuresOf(trimmed: String): LineFeatures {
        val toks = tokens(trimmed)
        return LineFeatures(toks, action = toks.isNotEmpty() && toks[0] in ACTION, constraint = isProtected(toks))
    }

    /**
     * What a question makes of an index: which distinct lines contain one of its terms ([scored], each once) and what
     * each is worth ([scoreOfLine], by distinct line number). The one place a line is scored: [project] selects from
     * it and [rank] summarizes it, so what a document is worth can never disagree with what is shown from it.
     */
    internal class LineScores(val scored: IntBuf, val scoreOfLine: DoubleArray)

    /**
     * Corpus statistics are over DISTINCT line contents and live in the index, so only the postings of the question's
     * own terms are read: a line that contains none of them is never looked at.
     */
    private fun scoreLines(index: SnapshotIndex, query: List<String>, facets: ProjectionFacets): LineScores {
        val n = index.distinct
        val weights = DoubleArray(query.size) { qi ->
            val dq = index.postings[query[qi]]?.size ?: 0
            val idf = ln(1.0 + (n - dq + 0.5) / (dq + 0.5))
            idf * (1.0 + 0.05 * min(query[qi].length, 12))
        }
        val seen = BooleanArray(n)
        val scored = IntBuf()
        for (q in query) {
            val posting = index.postings[q] ?: continue
            for (id in posting) if (!seen[id]) { seen[id] = true; scored.add(id) }
        }
        facets.noteScored(scored.size)
        val avgLen = index.avgLen
        val scoreOfLine = DoubleArray(n)
        for (k in 0 until scored.size) {
            val id = scored[k]
            val line = index.features[id]
            val toks = line.tokens
            var s = 0.0
            for (qi in query.indices) {
                val tf = toks.count { it == query[qi] }
                if (tf == 0) continue
                s += weights[qi] * (tf * (K1 + 1.0)) / (tf + K1 * (1.0 - B + B * toks.size / avgLen))
            }
            if (s > 0.0 && toks.isNotEmpty() && line.action) s *= 1.1
            scoreOfLine[id] = s
        }
        return LineScores(scored, scoreOfLine)
    }

    /**
     * How well each document of [index] answers [question]: the score of its best line, 0.0 for a document none of
     * whose lines matched. A line several documents share counts for each of them; [project] shows it once, but a
     * document is still about what it says. Reads only the postings of the question's own terms and the positions of
     * the lines they score, never a document's text.
     */
    internal fun rank(index: SnapshotIndex, question: String, facets: ProjectionFacets): DoubleArray {
        require(facets.policy == POLICY) { "facets were built for policy '${facets.policy}', this projector is '$POLICY'" }
        val lines = scoreLines(index, tokens(question).distinct(), facets)
        val best = DoubleArray(index.documents.size)
        for (k in 0 until lines.scored.size) {
            val id = lines.scored[k]
            val s = lines.scoreOfLine[id]
            if (s <= 0.0) continue
            for (o in index.occStart[id] until index.occStart[id + 1]) {
                val d = index.occDocument[o]
                if (s > best[d]) best[d] = s
            }
        }
        return best
    }

    /**
     * Answers [question] about [snapshot]. [facets] is rebuildable memory of what earlier questions already computed
     * (line structure, line features, the snapshot's inverted index); it changes the cost of an answer and never the
     * answer, which is checked against the plain algorithm. The default is a private instance, so nothing is retained.
     */
    fun project(
        snapshot: ContextSnapshot,
        question: String,
        budget: ProjectionBudget = ProjectionBudget(),
        facets: ProjectionFacets = ProjectionFacets(),
    ): Projection {
        require(facets.policy == POLICY) { "facets were built for policy '${facets.policy}', this projector is '$POLICY'" }
        val query = tokens(question).distinct()
        val index = facets.index(snapshot)
        val docs = List(snapshot.documents.size) { d -> DocView(snapshot.documents[d], index.documents[d]) }

        val lines = scoreLines(index, query, facets)
        val scored = lines.scored
        val scoreOfLine = lines.scoreOfLine

        class Candidate(val doc: Int, val ordinal: Int, val score: Double)

        val candidates = ArrayList<Candidate>()
        for (k in 0 until scored.size) {
            val id = scored[k]
            val s = scoreOfLine[id]
            if (s <= 0.0) continue
            for (o in index.occStart[id] until index.occStart[id + 1]) candidates.add(Candidate(index.occDocument[o], index.occOrdinal[o], s))
        }
        candidates.sortWith(compareBy<Candidate>({ -it.score }, { it.doc }, { it.ordinal }))

        fun single(d: DocView, pick: Pick): Excerpt {
            val l = d.lines[pick.ordinal]
            return Excerpt(d.name, d.cid, l.index, l.index, l.line, d.lineText(pick.ordinal), pick.score, pick.reasons.toList())
        }

        // Reading order; runs of consecutive non-blank lines share one header when that is no more expensive.
        fun assemble(picks: List<Pick>): List<Excerpt> {
            val ordered = picks.sortedWith(compareBy<Pick>({ it.doc }, { it.ordinal }))
            val out = ArrayList<Excerpt>()
            var i = 0
            while (i < ordered.size) {
                var j = i
                while (j + 1 < ordered.size && ordered[j + 1].doc == ordered[i].doc && ordered[j + 1].ordinal == ordered[j].ordinal + 1) j++
                val d = docs[ordered[i].doc]
                val parts = (i..j).map { k -> single(d, ordered[k]) }
                if (j == i) out.add(parts[0]) else {
                    val range = SourceRange(parts.first().range.start, parts.last().range.end)
                    val merged = Excerpt(
                        d.name, d.cid, parts.first().firstLine, parts.last().lastLine, range,
                        d.text.substring(range.start, range.end), parts.sumOf { it.score }, parts.flatMap { it.reasons }.distinct(),
                    )
                    if (merged.rendered().length > parts.sumOf { it.rendered().length }) out.addAll(parts) else out.add(merged)
                }
                i = j + 1
            }
            return out
        }

        // Exact: the cost of a candidate set is the rendered length of the excerpts it would actually produce.
        fun costWith(picks: List<Pick>, extra: Pick): Int = assemble(picks + extra).sumOf { it.rendered().length }

        val picks = ArrayList<Pick>()
        val pickedAt = HashSet<Pair<Int, Int>>()
        val pickedContent = HashSet<Int>()
        var omitted = 0
        val droppedAt = HashSet<Pair<Int, Int>>()

        for (cand in candidates) {
            val content = index.lineId[cand.doc][cand.ordinal]
            if (content in pickedContent) continue                       // already shown once
            val reasons = mutableListOf("match")
            if (index.features[content].action) reasons.add("action")
            val pick = Pick(cand.doc, cand.ordinal, cand.score, reasons)
            if (picks.size >= budget.maxLines || costWith(picks, pick) > budget.maxChars) { omitted++; continue }
            picks.add(pick)
            pickedAt.add(cand.doc to cand.ordinal)
            pickedContent.add(content)

            // Protected constraints beside the match travel with it, or are counted as dropped.
            for (off in -budget.neighborLines..budget.neighborLines) {
                val nb = cand.ordinal + off
                if (off == 0 || nb < 0 || nb >= index.lineId[cand.doc].size) continue
                val nbContent = index.lineId[cand.doc][nb]
                if ((cand.doc to nb) in pickedAt || nbContent in pickedContent) continue
                if (!index.features[nbContent].constraint) continue
                val neighbor = Pick(cand.doc, nb, 0.0, mutableListOf("constraint:neighbor"))
                if (picks.size >= budget.maxLines || costWith(picks, neighbor) > budget.maxChars) {
                    droppedAt.add(cand.doc to nb)
                    continue
                }
                picks.add(neighbor)
                pickedAt.add(cand.doc to nb)
                pickedContent.add(nbContent)
            }
        }

        val excerpts = assemble(picks)
        // A wanted constraint that later made it in some other way is not dropped; one wanted twice is dropped once.
        val dropped = droppedAt.count { it !in pickedAt }
        val total = excerpts.sumOf { it.rendered().length }
        check(total <= budget.maxChars) { "projection of $total chars exceeds budget ${budget.maxChars}" }
        return Projection(POLICY, question, excerpts.toSeries(), total, omitted, dropped)
    }
}
