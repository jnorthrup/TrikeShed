package borg.trikeshed.cas

import borg.trikeshed.lib.intp.*
import java.io.File
import kotlin.math.abs
import kotlin.math.ln
import kotlin.test.*

/** Exact BM25 against a direct reading of Evoke's formulas (`evoke_core.c` evoke_score_tfc / _idf). */
class TermPostingsTest {
    /** The reference: every document scored term by term from the definitions, no postings. */
    private fun reference(docs: Map<Any?, List<String>>, query: List<String>, p: Bm25Params): Map<Any?, Double> {
        val n = docs.size.toDouble()
        val lavg = docs.values.sumOf { it.size }.toDouble() / docs.size
        fun idf(df: Double) = when (p.idfMethod) {
            Bm25Method.ROBERTSON -> ln(maxOf(1.0, (n - df + 0.5) / (df + 0.5)))
            Bm25Method.LUCENE -> ln(1.0 + (n - df + 0.5) / (df + 0.5))
            Bm25Method.ATIRE -> ln(n / df)
            Bm25Method.BM25L -> ln((n + 1.0) / (df + 0.5))
            Bm25Method.BM25PLUS -> ln((n + 1.0) / df)
        }
        // A match is any document holding a query term, even at weight 0 (Robertson clamps common terms to 0).
        return docs.filterValues { words -> query.any { it in words } }.mapValues { (_, words) ->
            query.sumOf { t ->
                val tf = words.count { it == t }.toDouble()
                if (tf == 0.0) 0.0 else idf(docs.values.count { t in it }.toDouble()) * bm25Tfc(p, tf, words.size.toDouble(), lavg)
            }
        }
    }

    private fun assertScores(expected: Map<Any?, Double>, hits: List<PostingHit>, label: String) {
        assertEquals(expected.keys, hits.map { it.key }.toSet(), label)
        for (hit in hits) assertTrue(abs(expected.getValue(hit.key) - hit.score) <= 1e-12 * maxOf(1.0, abs(hit.score)), "$label ${hit.key}")
    }

    @Test fun theAnalyzerBreaksWordsAsUax29() {
        assertEquals(
            listOf("can't", "stop", "3.14", "1,000", "e.g", "a:b", "foo_bar", "ünïcode", "东", "京"),
            Analyzer().tokens("Can't stop: 3.14 + 1,000, e.g. a:b foo_bar Ünïcode 东京!"),
        )
    }

    @Test fun everyMethodEqualsTheReferenceIncludingRetirementsAndRepeatedQueryTerms() {
        val corpus = mapOf<Any?, String>(
            1 to "the cursor algebra joins series", 2 to "cursor cursor cursor", 3 to "an algebra of series and joins",
            4 to "nothing relevant here at all", 5 to "join the cursor to the algebra and the cursor again",
        )
        val query = listOf("cursor", "algebra", "cursor")
        for (method in Bm25Method.entries) {
            val p = Bm25Params(method = method, idfMethod = method)
            val postings = TermPostings(params = p)
            corpus.forEach { (k, v) -> postings.upsert(k, v) }
            val tokens = corpus.mapValues { Analyzer().tokens(it.value) }
            assertScores(reference(tokens, query, p), postings.topKTokens(query, 10), "$method")
            // An upsert retires the old version; a removal retires outright. Statistics follow the live rows.
            postings.upsert(2, "a fresh algebra")
            postings.remove(4)
            val live = tokens - 4 + (2 to Analyzer().tokens("a fresh algebra"))
            assertScores(reference(live, query, p), postings.topKTokens(query, 10), "$method after retire")
            assertEquals(4, postings.documents)
        }
    }

    @Test fun tiesBreakBySlotAndTopKIsExact() {
        val postings = TermPostings()
        for (i in 0 until 50) postings.upsert(i, if (i % 7 == 0) "kernel kernel" else "kernel filler words")
        val top = postings.topK("kernel", 5)
        assertEquals(listOf(0, 7, 14, 21, 28), top.map { it.key })
        assertEquals(top.map { it.slot }, top.map { it.slot }.sorted())
    }

    @Test fun readmeLinesRankAsTheReferenceDoes() {
        val lines = generateSequence(File(".").absoluteFile) { it.parentFile }.map { File(it, "README.md") }.first { it.isFile }
            .readLines().filter { it.isNotBlank() }
        val postings = TermPostings()
        lines.forEachIndexed { i, line -> postings.upsert(i, line) }
        val query = Analyzer().tokens("cursor algebra")
        val expected = reference(lines.withIndex().associate { (i, l) -> i as Any? to Analyzer().tokens(l) }, query, Bm25Params())
        val top = postings.topKTokens(query, 5)
        assertEquals(expected.entries.sortedWith(compareByDescending<Map.Entry<Any?, Double>> { it.value }.thenBy { it.key as Int }).take(5).map { it.key }, top.map { it.key })
        assertScores(expected.filterKeys { k -> top.any { it.key == k } }, top, "README")
        for (hit in top) assertTrue(query.any { it in Analyzer().tokens(lines[hit.key as Int]) }, lines[hit.key as Int])
    }
}
