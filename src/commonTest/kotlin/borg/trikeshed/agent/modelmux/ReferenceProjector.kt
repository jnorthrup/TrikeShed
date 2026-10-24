package borg.trikeshed.agent.modelmux

import borg.trikeshed.cas.LineCas
import borg.trikeshed.cas.LineCasSourceRanges
import borg.trikeshed.cas.SourceLine
import borg.trikeshed.cas.SourceRange
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import kotlin.math.ln
import kotlin.math.min

/**
 * The specification of [BreadcrumbProjector], kept as a test oracle: the plain algorithm that re-reads, re-hashes and
 * re-tokenizes the whole snapshot for every question. The production projector answers the same questions from a
 * memoized index and must agree with this one on everything observable, down to the bits of every score.
 *
 * The word lists and the tokenizer are shared with production on purpose: this oracle checks the indexing and the
 * caching, not the vocabulary. Do not "improve" this copy; change the policy revision and both together.
 */
internal object ReferenceProjector {
    private class IndexedDoc(
        val name: String,
        val cid: ContentId,
        val text: String,
        val lines: Series<SourceLine>,
        val contents: List<ContentId>,
    ) {
        fun lineText(ordinal: Int): String = text.substring(lines[ordinal].line.start, lines[ordinal].line.end)
        fun trimmed(ordinal: Int): String = text.substring(lines[ordinal].text.start, lines[ordinal].text.end)
    }

    private class Pick(val doc: Int, val ordinal: Int, val score: Double, val reasons: MutableList<String>)

    fun project(snapshot: ContextSnapshot, question: String, budget: ProjectionBudget = ProjectionBudget()): Projection {
        val query = BreadcrumbProjector.tokens(question).distinct()
        val docs = ArrayList<IndexedDoc>(snapshot.documents.size)
        for (d in 0 until snapshot.documents.size) {
            val doc = snapshot.documents[d]
            val text = doc.text
            val lines = LineCasSourceRanges.of(text)
            docs.add(
                IndexedDoc(doc.name, doc.cid, text, lines, (0 until lines.size).map { i ->
                    LineCas.contentOf(text.substring(lines[i].text.start, lines[i].text.end))
                }),
            )
        }

        // Corpus statistics over DISTINCT line contents.
        val tokensOf = LinkedHashMap<ContentId, List<String>>()
        for (doc in docs) for (i in doc.contents.indices) tokensOf.getOrPut(doc.contents[i]) { BreadcrumbProjector.tokens(doc.trimmed(i)) }
        val n = tokensOf.size
        val df = HashMap<String, Int>()
        for (toks in tokensOf.values) for (t in toks.toSet()) df[t] = (df[t] ?: 0) + 1
        val avgLen = if (n == 0) 1.0 else maxOf(1.0, tokensOf.values.sumOf { it.size }.toDouble() / n)

        val scoreOf = HashMap<ContentId, Double>()
        fun score(cid: ContentId): Double = scoreOf.getOrPut(cid) {
            val toks = tokensOf.getValue(cid)
            var s = 0.0
            for (q in query) {
                val tf = toks.count { it == q }
                if (tf == 0) continue
                val dq = df[q] ?: 0
                val idf = ln(1.0 + (n - dq + 0.5) / (dq + 0.5))
                val weight = idf * (1.0 + 0.05 * min(q.length, 12))
                s += weight * (tf * (BreadcrumbProjector.K1 + 1.0)) / (tf + BreadcrumbProjector.K1 * (1.0 - BreadcrumbProjector.B + BreadcrumbProjector.B * toks.size / avgLen))
            }
            if (s > 0.0 && toks.isNotEmpty() && toks[0] in BreadcrumbProjector.ACTION) s *= 1.1
            s
        }

        class Candidate(val doc: Int, val ordinal: Int, val score: Double)

        val candidates = ArrayList<Candidate>()
        for (d in docs.indices) for (i in docs[d].contents.indices) {
            val s = score(docs[d].contents[i])
            if (s > 0.0) candidates.add(Candidate(d, i, s))
        }
        candidates.sortWith(compareBy<Candidate>({ -it.score }, { it.doc }, { it.ordinal }))

        fun single(d: IndexedDoc, pick: Pick): Excerpt {
            val l = d.lines[pick.ordinal]
            return Excerpt(d.name, d.cid, l.index, l.index, l.line, d.lineText(pick.ordinal), pick.score, pick.reasons.toList())
        }

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

        fun costWith(picks: List<Pick>, extra: Pick): Int = assemble(picks + extra).sumOf { it.rendered().length }

        val picks = ArrayList<Pick>()
        val pickedAt = HashSet<Pair<Int, Int>>()
        val pickedContent = HashSet<ContentId>()
        var omitted = 0
        val droppedAt = HashSet<Pair<Int, Int>>()

        for (cand in candidates) {
            val content = docs[cand.doc].contents[cand.ordinal]
            if (content in pickedContent) continue
            val reasons = mutableListOf("match")
            if (tokensOf.getValue(content).firstOrNull() in BreadcrumbProjector.ACTION) reasons.add("action")
            val pick = Pick(cand.doc, cand.ordinal, cand.score, reasons)
            if (picks.size >= budget.maxLines || costWith(picks, pick) > budget.maxChars) { omitted++; continue }
            picks.add(pick)
            pickedAt.add(cand.doc to cand.ordinal)
            pickedContent.add(content)

            val d = docs[cand.doc]
            for (off in -budget.neighborLines..budget.neighborLines) {
                val nb = cand.ordinal + off
                if (off == 0 || nb < 0 || nb >= d.contents.size) continue
                val nbContent = d.contents[nb]
                if ((cand.doc to nb) in pickedAt || nbContent in pickedContent) continue
                if (!BreadcrumbProjector.isProtected(tokensOf.getValue(nbContent))) continue
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
        val dropped = droppedAt.count { it !in pickedAt }
        val total = excerpts.sumOf { it.rendered().length }
        check(total <= budget.maxChars) { "projection of $total chars exceeds budget ${budget.maxChars}" }
        return Projection(BreadcrumbProjector.POLICY, question, excerpts.toSeries(), total, omitted, dropped)
    }
}
