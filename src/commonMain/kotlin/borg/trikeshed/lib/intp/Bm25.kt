package borg.trikeshed.lib.intp

import kotlin.math.ln

/** Evoke's BM25 family (`method` / `idf_method`), as `evoke_score_tfc` / `evoke_score_idf` define them. */
enum class Bm25Method { ROBERTSON, LUCENE, ATIRE, BM25L, BM25PLUS }

/** An index's BM25 contract; Evoke's defaults (method and idf `lucene`, k1 1.5, b 0.75, delta 0.5). */
data class Bm25Params(
    val method: Bm25Method = Bm25Method.LUCENE,
    val idfMethod: Bm25Method = Bm25Method.LUCENE,
    val k1: Double = 1.5,
    val b: Double = 0.75,
    val delta: Double = 0.5,
)

/** Inverse document frequency of a term in [df] of [n] documents. */
fun bm25Idf(method: Bm25Method, df: Double, n: Double): Double = when (method) {
    Bm25Method.ROBERTSON -> ln(maxOf(1.0, (n - df + 0.5) / (df + 0.5)))
    Bm25Method.LUCENE -> ln(1.0 + (n - df + 0.5) / (df + 0.5))
    Bm25Method.ATIRE -> ln(n / df)
    Bm25Method.BM25L -> ln((n + 1.0) / (df + 0.5))
    Bm25Method.BM25PLUS -> ln((n + 1.0) / df)
}

/** Term-frequency component of [tf] in a document of length [ld] against the average [lavg]. */
fun bm25Tfc(p: Bm25Params, tf: Double, ld: Double, lavg: Double): Double {
    val norm = 1.0 - p.b + p.b * ld / lavg
    return when (p.method) {
        Bm25Method.ROBERTSON, Bm25Method.LUCENE -> tf / (p.k1 * norm + tf)
        Bm25Method.ATIRE -> tf * (p.k1 + 1.0) / (tf + p.k1 * norm)
        Bm25Method.BM25L -> (tf / norm).let { c -> (p.k1 + 1.0) * (c + p.delta) / (p.k1 + c + p.delta) }
        Bm25Method.BM25PLUS -> (p.k1 + 1.0) * tf / (p.k1 * norm + tf) + p.delta
    }
}

/**
 * Adds `weight * idf * tfc` for one posting list (`slots[0 until n]`, `tfs[0 until n]`) into
 * `acc[slot]`, skipping slots [live] rejects, and marks each scored slot in [touched]. Answers the
 * number of slots newly touched, which it appends to [hits]. The `lucene` / `robertson` tfc runs in
 * a branch-free loop; the other variants share [bm25Tfc].
 */
fun bm25Accumulate(
    slots: IntArray, tfs: IntArray, n: Int, docLen: IntArray, lavg: Double, idf: Double, weight: Double,
    p: Bm25Params, live: BooleanArray, acc: DoubleArray, touched: BooleanArray, hits: IntArray, hitCount: Int,
): Int {
    var count = hitCount
    val scale = weight * idf
    val lucene = p.method == Bm25Method.LUCENE || p.method == Bm25Method.ROBERTSON
    val k1b = p.k1 * p.b / lavg
    val k1c = p.k1 * (1.0 - p.b)
    for (i in 0 until n) {
        val slot = slots[i]
        if (!live[slot]) continue
        val tf = tfs[i].toDouble()
        val tfc = if (lucene) tf / (k1c + k1b * docLen[slot] + tf) else bm25Tfc(p, tf, docLen[slot].toDouble(), lavg)
        acc[slot] += scale * tfc
        if (!touched[slot]) { touched[slot] = true; hits[count++] = slot }
    }
    return count
}
