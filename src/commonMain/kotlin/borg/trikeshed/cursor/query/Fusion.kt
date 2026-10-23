package borg.trikeshed.cursor.query

import borg.trikeshed.cursor.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.kursive.sql.compareCells
import borg.trikeshed.parse.kursive.sql.ordinalOf
import kotlin.math.sqrt

/**
 * Evoke's fusion helpers (`evoke_fusion`, `evoke_hybrid_fuse_candidates`) as read from
 * `sql/evoke--0.2.6.sql` and `src/evoke_am_sql.c`: candidates from any retrieval sources combine into
 * one ranking that keeps every source's raw value, normalized score, weighted score and rank.
 *
 * Deviations from the C, both deliberate: a candidate's [Candidate.id] is an application row key and
 * ties compare it as a cell (numbers numerically, text lexically) rather than as the text of a
 * PostgreSQL tid; scores are kept double rather than narrowed to float4.
 */
enum class Direction { HIGHER_IS_BETTER, LOWER_IS_BETTER }

/** How a source's raw value becomes a comparable score under [FusionMethod.SCORE]; ignored under RRF. */
enum class Normalizer { IDENTITY, NEGATIVE_DISTANCE, INVERSE_DISTANCE, MINMAX, ZSCORE, RANK }

enum class FusionMethod { RRF, SCORE }

/** `evoke_result_hybrid_candidate`: one source's view of one row. */
data class Candidate(
    val source: String,
    val id: Any?,
    val raw: Double,
    /** Null or 0 derives the rank from the source's order; a negative rank drops the candidate after it has shaped the source's statistics. */
    val rank: Int? = null,
    val weight: Double = 1.0,
    /** Null selects NEGATIVE_DISTANCE for a lower-is-better source and IDENTITY otherwise. */
    val normalizer: Normalizer? = null,
    val direction: Direction = Direction.HIGHER_IS_BETTER,
)

/** `evoke_result_hybrid_hit`: a row and every source that reached it, sources in name order. */
data class HybridHit(
    val id: Any,
    val score: Double,
    val sources: List<String>,
    val raw: DoubleArray,
    val normalized: DoubleArray,
    val weighted: DoubleArray,
    val ranks: IntArray,
) {
    val sourceCount: Int get() = sources.size
}

/** `evoke_result_hit` for [evokeFusion]: a row's score with the index-local document id Evoke carries beside it. */
data class ScoredId(val id: Any, val score: Double, val docId: Int = 0)

private class Prepared(val c: Candidate, val source: String, val id: Any) {
    var rank = 0
    var normalized = 0.0
    var weighted = 0.0
    var scored = true
}

private class SourceStats {
    var min = Double.MAX_VALUE
    var max = -Double.MAX_VALUE
    var sum = 0.0
    var sumSq = 0.0
    var count = 0
}

private fun idCompare(a: Any, b: Any): Int = compareCells(a, b)

/** Best-first within a source: lower-is-better before higher, then the value by direction, then id. */
private fun rankOrder(a: Prepared, b: Prepared): Int {
    a.source.compareTo(b.source).let { if (it != 0) return it }
    if (a.c.direction != b.c.direction) return if (a.c.direction == Direction.LOWER_IS_BETTER) -1 else 1
    val byValue = if (a.c.direction == Direction.LOWER_IS_BETTER) a.c.raw.compareTo(b.c.raw) else b.c.raw.compareTo(a.c.raw)
    if (byValue != 0) return byValue
    return idCompare(a.id, b.id)
}

/**
 * `evoke_hybrid_fuse_candidates(candidates, k, fusion, rrf_k, epsilon)`: candidates that lack an id
 * or carry a non-finite value are dropped first; each source is ordered best first and positions
 * give the missing ranks while min/max/mean/stddev accumulate over every candidate; a negative rank
 * then drops its candidate; RRF scores `1 / (rrfK + rank)`, SCORE applies the normalizer (identity
 * and minmax/zscore are direction-aware); weights multiply; the best candidate per (source, id)
 * survives; rows group by id with their sources in name order; the result is ordered by score
 * descending then id, and cut to [k] (clamped at 0; [rrfK] at 1e-6; [epsilon] at 1e-12).
 */
fun fuseCandidates(
    candidates: List<Candidate>,
    k: Int = 10,
    method: FusionMethod = FusionMethod.RRF,
    rrfK: Double = 60.0,
    epsilon: Double = 1e-6,
): List<HybridHit> {
    val rrf = maxOf(rrfK, 1e-6)
    val eps = maxOf(epsilon, 1e-12)
    val kept = ArrayList<Prepared>(candidates.size)
    for (c in candidates) {
        val id = c.id ?: continue
        if (c.raw.isNaN() || c.raw.isInfinite()) continue
        kept += Prepared(c, c.source.ifEmpty { "source" }, id)
    }
    if (kept.isEmpty()) return emptyList()
    kept.sortWith(::rankOrder)
    // Pass 1: positions and statistics over every candidate of the source, duplicates and dropped ones included.
    val stats = HashMap<String, SourceStats>()
    var position = 0
    var current: String? = null
    for (p in kept) {
        if (p.source != current) { current = p.source; position = 1 } else position++
        val given = p.c.rank
        p.rank = if (given == null || given == 0) position else given
        stats.getOrPut(p.source) { SourceStats() }.apply {
            val v = p.c.raw
            if (v < min) min = v
            if (v > max) max = v
            sum += v; sumSq += v * v; count++
        }
    }
    // Pass 2: normalize and weight.
    for (p in kept) {
        if (p.rank <= 0) { p.scored = false; continue }
        val s = stats.getValue(p.source)
        val mean = s.sum / s.count
        var variance = s.sumSq / s.count - mean * mean
        if (variance < 0.0 && variance > -1e-12) variance = 0.0
        val stddev = if (variance <= 0.0) 0.0 else sqrt(variance)
        val lower = p.c.direction == Direction.LOWER_IS_BETTER
        val raw = p.c.raw
        val normalizer = p.c.normalizer ?: if (lower) Normalizer.NEGATIVE_DISTANCE else Normalizer.IDENTITY
        p.normalized = if (method == FusionMethod.RRF) 1.0 / (rrf + p.rank) else when (normalizer) {
            Normalizer.IDENTITY -> if (lower) -raw else raw
            Normalizer.NEGATIVE_DISTANCE -> -raw
            Normalizer.INVERSE_DISTANCE -> 1.0 / maxOf(eps, raw + eps)
            Normalizer.MINMAX -> if (s.max == s.min) 1.0 else if (lower) (s.max - raw) / (s.max - s.min) else (raw - s.min) / (s.max - s.min)
            Normalizer.ZSCORE -> if (stddev == 0.0) 0.0 else if (lower) (mean - raw) / stddev else (raw - mean) / stddev
            Normalizer.RANK -> 1.0 / maxOf(p.rank.toDouble(), 1.0)
        }
        p.weighted = p.normalized * p.c.weight
    }
    // Dedup per (source, id): the highest weighted score, then the lowest rank, then the best value.
    val scored = kept.filter { it.scored }.sortedWith { a, b ->
        a.source.compareTo(b.source).let { if (it != 0) return@sortedWith it }
        idCompare(a.id, b.id).let { if (it != 0) return@sortedWith it }
        b.weighted.compareTo(a.weighted).let { if (it != 0) return@sortedWith it }
        a.rank.compareTo(b.rank).let { if (it != 0) return@sortedWith it }
        if (a.c.direction != b.c.direction) return@sortedWith if (a.c.direction == Direction.LOWER_IS_BETTER) -1 else 1
        if (a.c.direction == Direction.LOWER_IS_BETTER) a.c.raw.compareTo(b.c.raw) else b.c.raw.compareTo(a.c.raw)
    }
    val deduped = ArrayList<Prepared>(scored.size)
    for (p in scored) {
        val last = deduped.lastOrNull()
        if (last != null && last.source == p.source && idCompare(last.id, p.id) == 0) continue
        deduped += p
    }
    // Group by id, sources in name order.
    val byId = deduped.sortedWith { a, b -> idCompare(a.id, b.id).let { if (it != 0) it else a.source.compareTo(b.source) } }
    val hits = ArrayList<HybridHit>()
    var i = 0
    while (i < byId.size) {
        var j = i
        while (j < byId.size && idCompare(byId[j].id, byId[i].id) == 0) j++
        val run = byId.subList(i, j)
        hits += HybridHit(
            byId[i].id, run.sumOf { it.weighted }, run.map { it.source },
            DoubleArray(run.size) { run[it].c.raw }, DoubleArray(run.size) { run[it].normalized },
            DoubleArray(run.size) { run[it].weighted }, IntArray(run.size) { run[it].rank },
        )
        i = j
    }
    hits.sortWith { a, b -> b.score.compareTo(a.score).let { if (it != 0) it else idCompare(a.id, b.id) } }
    return hits.take(maxOf(k, 0))
}

/**
 * `evoke_fusion(left_hits, left_weight, right_hits, right_weight, k)`: a weighted sum of raw scores
 * per id with no normalization and no per-side dedup (a row repeated on one side is summed twice),
 * `doc_id = MIN`, ordered by score descending then id, cut to [k], which when null is the larger
 * side's size (so disjoint sides can be truncated, as Evoke's SQL does).
 */
fun evokeFusion(left: List<ScoredId>, leftWeight: Double, right: List<ScoredId>, rightWeight: Double, k: Int? = null): List<ScoredId> =
    weightedSum(listOf(leftWeight j left, rightWeight j right), k ?: maxOf(left.size, right.size))

/** The n-ary form behind `evoke_fusion_query_weighted`: `SUM(score * weight) GROUP BY id`, `MIN(doc_id)`, top [k] (null = all). */
fun weightedSum(sources: List<Join<Double, List<ScoredId>>>, k: Int? = 10): List<ScoredId> {
    val score = LinkedHashMap<Any, Double>()
    val docId = HashMap<Any, Int>()
    for ((weight, hits) in sources.map { it.a to it.b }) for (h in hits) {
        score[h.id] = (score[h.id] ?: 0.0) + h.score * weight
        docId[h.id] = minOf(docId[h.id] ?: Int.MAX_VALUE, h.docId)
    }
    val fused = score.entries.map { ScoredId(it.key, it.value, docId.getValue(it.key)) }
        .sortedWith { a, b -> b.score.compareTo(a.score).let { if (it != 0) it else idCompare(a.id, b.id) } }
    return if (k == null) fused else fused.take(maxOf(k, 0))
}

/**
 * [QueryK.Fuse] over a candidate table: columns `source_name`, `id`, `raw_value` are required and
 * `source_rank`, `weight`, `normalizer`, `direction` optional (Evoke's candidate type); the result is
 * the hybrid hit table: id, score, source_count, source_names, raw_values, normalized_scores,
 * weighted_scores, ranks (the arrays as JSON cells).
 */
fun Cursor.fuse(stage: QueryK.Fuse): Cursor {
    val hits = if (size == 0) emptyList() else {
        val names = names
        fun column(name: String): Int? = (0 until names.size).firstOrNull { names[it].contentEquals(name) }
        val source = names.ordinalOf("source_name")
        val id = names.ordinalOf("id")
        val raw = names.ordinalOf("raw_value")
        val rank = column("source_rank")
        val weight = column("weight")
        val normalizer = column("normalizer")
        val direction = column("direction")
        fuseCandidates(
            List(size) { r ->
                val v = this[r].values
                Candidate(
                    v[source]?.toString().orEmpty(), v[id], (v[raw] as? Number)?.toDouble() ?: Double.NaN,
                    rank?.let { (v[it] as? Number)?.toInt() }, weight?.let { (v[it] as? Number)?.toDouble() } ?: 1.0,
                    normalizer?.let { c -> v[c]?.toString()?.let { Normalizer.valueOf(it.uppercase()) } },
                    direction?.let { c -> v[c]?.toString()?.let { Direction.valueOf(it.uppercase()) } } ?: Direction.HIGHER_IS_BETTER,
                )
            },
            stage.k, stage.method, stage.rrfK, stage.epsilon,
        )
    }
    val columns = listOf("id", "score", "source_count", "source_names", "raw_values", "normalized_scores", "weighted_scores", "ranks")
    val json = listOf("source_names", "raw_values", "normalized_scores", "weighted_scores", "ranks").associateWith { "jsonb" }
    return rowsCursor(
        columns,
        hits.map { h ->
            mapOf(
                "id" to h.id, "score" to h.score, "source_count" to h.sourceCount.toLong(), "source_names" to h.sources,
                "raw_values" to h.raw.toList(), "normalized_scores" to h.normalized.toList(), "weighted_scores" to h.weighted.toList(),
                "ranks" to h.ranks.map { it.toLong() },
            )
        },
        json + ("score" to "float8") + ("source_count" to "int8"),
    )
}
