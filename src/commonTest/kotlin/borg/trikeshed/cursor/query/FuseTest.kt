package borg.trikeshed.cursor.query

import borg.trikeshed.cursor.*
import borg.trikeshed.lib.*
import borg.trikeshed.memory.ontology.MemoryRetrievalElement
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.*

/** Evoke's fusion against the fixtures of its own integration suite (sql/evoke_integration.sql). */
class FuseTest {
    private fun near(expected: Double, actual: Double, label: String = "") = assertTrue(abs(expected - actual) < 1e-9, "$label expected $expected got $actual")

    @Test fun rrfSumsWeightOverRrfKPlusRankAndKeepsEverySource() {
        // title rank 1 weight 2 + embedding rank 2 weight 1 on row 1; embedding rank 1 weight 1 on row 2.
        val hits = fuseCandidates(listOf(
            Candidate("title", 1L, 10.0, rank = 1, weight = 2.0),
            Candidate("embedding", 1L, 0.4, rank = 2, direction = Direction.LOWER_IS_BETTER),
            Candidate("embedding", 2L, 0.1, rank = 1, direction = Direction.LOWER_IS_BETTER),
        ))
        assertEquals(listOf(1L, 2L), hits.map { it.id })
        near(2.0 / 61 + 1.0 / 62, hits[0].score)
        near(1.0 / 61, hits[1].score)
        assertEquals(listOf("embedding", "title"), hits[0].sources, "sources in name order, not input order")
        assertEquals(listOf(2, 1), hits[0].ranks.toList())
        near(0.4, hits[0].raw[0]); near(1.0 / 62, hits[0].normalized[0]); near(2.0 / 61, hits[0].weighted[1])
        assertEquals(2, hits[0].sourceCount)
        // The plan's acceptance: ranked 1 and 3 across two sources beats ranked 1 in one.
        val two = fuseCandidates(listOf(Candidate("lex", "a", 1.0, rank = 1), Candidate("vec", "a", 0.5, rank = 3), Candidate("lex", "b", 1.0, rank = 1)))
        assertEquals(listOf("a", "b"), two.map { it.id })
    }

    @Test fun scoreNormalizersFollowTheC() {
        fun one(vararg c: Candidate) = fuseCandidates(c.toList(), method = FusionMethod.SCORE)
        // minmax: title {10, 5} higher -> {1, 0}; embedding {0.4, 0.1} lower -> {0, 1}; max == min -> 1.0.
        val minmax = one(
            Candidate("title", 1L, 10.0, normalizer = Normalizer.MINMAX), Candidate("title", 2L, 5.0, normalizer = Normalizer.MINMAX),
            Candidate("embedding", 1L, 0.4, normalizer = Normalizer.MINMAX, direction = Direction.LOWER_IS_BETTER),
            Candidate("embedding", 2L, 0.1, normalizer = Normalizer.MINMAX, direction = Direction.LOWER_IS_BETTER),
        ).associateBy { it.id }
        assertEquals(listOf(0.0, 1.0), minmax.getValue(1L).normalized.toList())
        assertEquals(listOf(1.0, 0.0), minmax.getValue(2L).normalized.toList())
        near(1.0, one(Candidate("s", 1L, 7.0, normalizer = Normalizer.MINMAX))[0].normalized[0], "single-row minmax")
        // zscore: population statistics, {1,2,3} -> ±1.2247, 0; a flat source -> 0.
        val z = one(*(1..3).map { Candidate("s", it.toLong(), it.toDouble(), normalizer = Normalizer.ZSCORE) }.toTypedArray()).associateBy { it.id }
        near(1.0 / kotlin.math.sqrt(2.0 / 3), z.getValue(3L).normalized[0]); near(0.0, z.getValue(2L).normalized[0]); near(-1.0 / kotlin.math.sqrt(2.0 / 3), z.getValue(1L).normalized[0])
        val flat = one(Candidate("f", 1L, 7.0, normalizer = Normalizer.ZSCORE), Candidate("f", 2L, 7.0, normalizer = Normalizer.ZSCORE))
        assertEquals(listOf(0.0, 0.0), flat.map { it.normalized[0] })
        // inverse_distance 0.5 -> ~2.0 and rank 2 -> 0.5 on the same row: total ~2.5.
        val inv = one(
            Candidate("d", 1L, 0.5, normalizer = Normalizer.INVERSE_DISTANCE, direction = Direction.LOWER_IS_BETTER),
            Candidate("r", 1L, 9.0, rank = 2, normalizer = Normalizer.RANK),
        )
        near(1.0 / (0.5 + 1e-6) + 0.5, inv[0].score)
        // identity is direction-aware; negative_distance is the lower-is-better default.
        near(-0.25, one(Candidate("s", 1L, 0.25, normalizer = Normalizer.IDENTITY, direction = Direction.LOWER_IS_BETTER))[0].normalized[0])
        near(-0.25, one(Candidate("s", 1L, 0.25, direction = Direction.LOWER_IS_BETTER))[0].normalized[0])
    }

    @Test fun ranksPositionsDedupAndCutsFollowTheC() {
        // Derived ranks count every candidate of the source, best first by direction, then id; equal values get distinct ranks.
        val derived = fuseCandidates(listOf(
            Candidate("s", 3L, 5.0), Candidate("s", 1L, 9.0), Candidate("s", 2L, 9.0), Candidate("s", 4L, 1.0, rank = -1),
        ), k = 10).associateBy { it.id }
        assertEquals(mapOf(1L to 1, 2L to 2, 3L to 3), derived.mapValues { it.value.ranks[0] }.mapKeys { it.key as Long })
        assertFalse(4L in derived, "a negative rank drops the candidate")
        // The dropped candidate still shaped the statistics: minmax over {9, 9, 5, 1}, so 5 maps to 0.5.
        val shaped = fuseCandidates(listOf(
            Candidate("s", 3L, 5.0, normalizer = Normalizer.MINMAX), Candidate("s", 1L, 9.0, normalizer = Normalizer.MINMAX),
            Candidate("s", 4L, 1.0, rank = -1, normalizer = Normalizer.MINMAX),
        ), method = FusionMethod.SCORE).associateBy { it.id }
        near(0.5, shaped.getValue(3L).normalized[0])
        // Two 'distance' rows for one id, both rank 1, rank normalizer: the better raw value survives.
        val dedup = fuseCandidates(listOf(
            Candidate("distance", 1L, 0.9, rank = 1, normalizer = Normalizer.RANK, direction = Direction.LOWER_IS_BETTER),
            Candidate("distance", 1L, 0.1, rank = 1, normalizer = Normalizer.RANK, direction = Direction.LOWER_IS_BETTER),
        ), method = FusionMethod.SCORE)
        assertEquals(1, dedup.size); near(0.1, dedup[0].raw[0]); assertEquals(1, dedup[0].sourceCount)
        // Dropped before anything: no id, non-finite value; empty input and k <= 0 give nothing; a blank source is "source".
        assertEquals(emptyList<HybridHit>(), fuseCandidates(listOf(Candidate("s", null, 1.0), Candidate("s", 1L, Double.NaN))))
        assertEquals(emptyList<HybridHit>(), fuseCandidates(emptyList()))
        assertEquals(emptyList<HybridHit>(), fuseCandidates(listOf(Candidate("s", 1L, 1.0)), k = 0))
        assertEquals(listOf("source"), fuseCandidates(listOf(Candidate("", 1L, 1.0)))[0].sources)
        // Order: score descending, then id; k cuts after the full sort.
        val cut = fuseCandidates(listOf(Candidate("s", 2L, 1.0, rank = 1), Candidate("s", 1L, 1.0, rank = 1), Candidate("s", 3L, 1.0, rank = 2)), k = 2)
        assertEquals(listOf(1L, 2L), cut.map { it.id })
    }

    @Test fun evokeFusionIsAWeightedSumWithMinDocIdAndTheLargerSideAsDefaultK() {
        val left = listOf(ScoredId("a", 1.0, 7), ScoredId("b", 0.5, 2), ScoredId("b", 0.25, 9))
        val right = listOf(ScoredId("a", 2.0, 3), ScoredId("c", 4.0, 1))
        val fused = evokeFusion(left, 2.0, right, 0.5)
        // a: 1*2 + 2*0.5 = 3; b: (0.5 + 0.25)*2 = 1.5 (no per-side dedup); c: 4*0.5 = 2; k defaults to max(3, 2) = 3.
        assertEquals(listOf("a" to 3.0, "c" to 2.0, "b" to 1.5), fused.map { it.id to it.score })
        assertEquals(listOf(3, 1, 2), fused.map { it.docId })
        assertEquals(listOf("c"), evokeFusion(left, 1.0, right, 1.0, k = 1).map { it.id }, "with equal weights c (4.0) leads a (3.0)")
        assertEquals(emptyList<ScoredId>(), evokeFusion(emptyList(), 1.0, emptyList(), 1.0))
    }

    @Test fun theFuseStageReadsACandidateTableAndWritesTheHitTable() {
        val candidates = rowsCursor(
            listOf("source_name", "id", "raw_value", "source_rank", "weight", "normalizer", "direction"),
            listOf(
                mapOf("source_name" to "title", "id" to 1L, "raw_value" to 10.0, "source_rank" to 1L, "weight" to 2.0, "normalizer" to "identity", "direction" to "higher_is_better"),
                mapOf("source_name" to "embedding", "id" to 1L, "raw_value" to 0.4, "source_rank" to 2L, "weight" to 1.0, "normalizer" to "negative_distance", "direction" to "lower_is_better"),
                mapOf("source_name" to "embedding", "id" to 2L, "raw_value" to 0.1, "source_rank" to 1L, "weight" to 1.0, "normalizer" to "negative_distance", "direction" to "lower_is_better"),
            ),
        )
        val hits = candidates.facet(QueryK.Fuse())
        assertEquals(2, hits.size)
        val first = hits[0].values
        assertEquals(1L, first[0]); near(2.0 / 61 + 1.0 / 62, first[1] as Double); assertEquals(2L, first[2])
        assertEquals("""["embedding","title"]""", borg.trikeshed.parse.kursive.sql.scalarText(first[3]).replace(" ", ""))
        assertEquals("""[2,1]""", borg.trikeshed.parse.kursive.sql.scalarText(first[7]).replace(" ", ""))
    }

    @Test fun theRetrievalElementFusesItsSources() = runTest {
        val element = MemoryRetrievalElement(candidates = { query ->
            listOf(Candidate("lexical", "doc-$query", 3.0, rank = 1), Candidate("vector", "doc-$query", 0.2, rank = 1, direction = Direction.LOWER_IS_BETTER), Candidate("vector", "other", 0.9, rank = 2, direction = Direction.LOWER_IS_BETTER))
        })
        element.open(); element.activate()
        @Suppress("UNCHECKED_CAST")
        val hits = element.retrieve("q").await() as List<HybridHit>
        assertEquals(listOf("doc-q", "other"), hits.map { it.id })
        val trace = element.subscribe().receive()
        assertEquals(hits, trace.b)
        element.drain()
    }
}
