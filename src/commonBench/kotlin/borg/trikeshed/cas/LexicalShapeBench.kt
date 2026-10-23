package borg.trikeshed.cas

import kotlinx.benchmark.*

/**
 * The lexical shape of S4 against Evoke's yardstick (BM25, `top_k = 1000`; scifact: 2,557 documents,
 * 2,557 QPS / 0.448 ms mean on PG18, n2-standard-16; msmarco build 56 s for 8.84 M documents). A
 * synthetic corpus of [docs] documents of about 200 tokens over a 20,000-term Zipf-like vocabulary,
 * queried with 3–6 term queries drawn from the same distribution:
 *  - `topK`: exact top-[k] over [TermPostings], the query tokenized per call as a client's would be;
 *  - `lifecycleScores`: the same query over a [PostingLifecycle] whose corpus was sealed into
 *    segments and folded by its own selector — the read plan's price over the single structure;
 *  - `build`: indexing the corpus from its token lists.
 * Numbers go in the commit message whatever they are; no Evoke claim is made from this host.
 */
@State(Scope.Benchmark)
open class LexicalShapeBench {
    @Param("2557") var docs = 2_557
    @Param("1000") var k = 1_000

    private lateinit var corpus: List<List<String>>
    private lateinit var queries: List<String>
    private lateinit var postings: TermPostings
    private lateinit var lifecycle: PostingLifecycle
    private var next = 0

    @Setup
    fun open() {
        var seed = 0x9E3779B97F4A7C15uL.toLong()
        fun lcg(): Double { seed = seed * 6364136223846793005L + 1442695040888963407L; return ((seed ushr 11).toDouble()) / (1L shl 53).toDouble() }
        fun term(): String { val u = lcg(); return "t" + (20_000 * u * u * u).toInt() }
        corpus = List(docs) { List(150 + (lcg() * 100).toInt()) { term() } }
        queries = List(512) { List(3 + (lcg() * 4).toInt()) { term() }.joinToString(" ") }
        postings = TermPostings()
        corpus.forEachIndexed { i, tokens -> postings.upsertTokens(i, tokens) }
        lifecycle = PostingLifecycle(limits = PostingLifecycle.Limits(l0RotationRecords = maxOf(docs / 8, 1), l0MaxRecords = maxOf(docs / 4, 2)))
        // The worker runs beside the writer: a strictly due action (a pending seal) is taken as it arises.
        corpus.forEachIndexed { i, tokens -> lifecycle.upsertTokens(i, tokens); if (lifecycle.strictDue()) lifecycle.tryMaintain() }
        var guard = 0
        while (lifecycle.tryMaintain().maintained && ++guard < 10_000) Unit
    }

    private fun query(): String = queries[next++ % queries.size]

    @Benchmark
    fun topK(): Int = postings.topK(query(), k).size

    @Benchmark
    fun lifecycleScores(): Int = lifecycle.scores(query()).size

    @Benchmark
    fun build(): Int {
        val p = TermPostings()
        corpus.forEachIndexed { i, tokens -> p.upsertTokens(i, tokens) }
        return p.documents
    }
}
