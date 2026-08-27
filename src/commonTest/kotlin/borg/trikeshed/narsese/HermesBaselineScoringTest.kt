package borg.trikeshed.narsese

import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.emptySeriesOf
import borg.trikeshed.lib.get
import borg.trikeshed.lib.s_
import borg.trikeshed.lib.size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** I3/I4/I5 gate — durable, reproducible baseline session CIDs and refusal before both exist. */
class HermesBaselineScoringTest {
    private val corpus = ContentId.of("hermes-transcript-snapshot".encodeToByteArray())
    private val s1 = ContentId.of("scenario-1".encodeToByteArray())
    private val s2 = ContentId.of("scenario-2".encodeToByteArray())

    @Test
    fun watermarkSessionsArePerFeatureDeterministicAndExcludeNeutral() {
        val cas = CasStore.inMemory()
        val outcomes = s_[
            HermesBaselineScoring.WatermarkOutcome("tool-frame", s1, HindsightVerdict.SUPPORTED, HindsightVerdict.SUPPORTED),
            HermesBaselineScoring.WatermarkOutcome("tool-frame", s2, HindsightVerdict.REFUTED, HindsightVerdict.SUPPORTED),
            HermesBaselineScoring.WatermarkOutcome("retry", s1, HindsightVerdict.NEUTRAL, HindsightVerdict.SUPPORTED),
            HermesBaselineScoring.WatermarkOutcome("retry", s2, HindsightVerdict.REFUTED, HindsightVerdict.REFUTED),
        ]
        val a = HermesBaselineScoring.watermark(cas, corpus, 7, outcomes)
        val b = HermesBaselineScoring.watermark(cas, corpus, 7, outcomes)
        assertEquals(2, a.size, "one session per feature")
        for (i in 0 until a.size) assertEquals(a[i].sessionCid, b[i].sessionCid, "identical replay reproduces cid")
        val retry = if (a[0].feature == "retry") a[0] else a[1]
        assertEquals(1, retry.run.scores.size, "NEUTRAL excluded")
        val tools = if (a[0].feature == "tool-frame") a[0] else a[1]
        assertEquals(1, tools.run.scores[0].linked, "agreement scored")
        assertEquals(1, tools.run.scores[1].contentOnly, "disagreement scored")
    }

    @Test
    fun narsTrainingSessionAndBaselineGateAreContentAddressed() {
        val cas = CasStore.inMemory()
        val outcomes = s_[
            HermesBaselineScoring.NarsOutcome(s1, HindsightVerdict.SUPPORTED, HindsightVerdict.SUPPORTED),
            HermesBaselineScoring.NarsOutcome(s2, HindsightVerdict.REFUTED, HindsightVerdict.SUPPORTED),
        ]
        val a = HermesBaselineScoring.narsTraining(cas, corpus, 9, outcomes)
        val b = HermesBaselineScoring.narsTraining(cas, corpus, 9, outcomes)
        assertEquals(a.sessionCid, b.sessionCid)
        assertTrue(cas.get(a.sessionCid) != null, "baseline session bytes are durable in CAS")

        val missing = HermesBaselineScoring.BaselineRefs(emptySeriesOf(), null)
        assertFailsWith<IllegalArgumentException> { missing.requireReady("attention retune") }
        val ready = HermesBaselineScoring.BaselineRefs(s_[a.sessionCid], a.sessionCid)
        ready.requireReady("attention retune")
        assertTrue(ready.ready)
    }
}
