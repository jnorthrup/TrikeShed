package borg.trikeshed.narsese

import borg.trikeshed.cas.ScoringSession
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.emptySeriesOf
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size

/**
 * I3/I4/I5 — deterministic Hermes watermark + NARS training baseline sessions.
 *
 * Both paths reuse Step F's [ScoringSession]: corpus snapshot cid + method cid + ordered
 * mechanical outcomes → session cid. NEUTRAL observations are excluded, exactly like teach().
 * No model is reachable here; callers replay tooling / query the trained bag and hand this
 * scorer the recorded and observed verdicts.
 */
object HermesBaselineScoring {

    data class WatermarkOutcome(
        val feature: String,
        val scenarioCid: ContentId,
        val recorded: HindsightVerdict,
        val replayed: HindsightVerdict,
    )

    data class NarsOutcome(
        val scenarioCid: ContentId,
        val recorded: HindsightVerdict,
        val predicted: HindsightVerdict,
    )

    data class WatermarkBaseline(val feature: String, val run: ScoringSession.Run, val sessionCid: ContentId)
    data class TrainingBaseline(val run: ScoringSession.Run, val sessionCid: ContentId)

    /** One scoring session per tooling feature; NEUTRAL rows do not enter the denominator. */
    fun watermark(
        cas: CasStore,
        corpusCid: ContentId,
        corpusSeq: Long,
        outcomes: Series<WatermarkOutcome>,
    ): Series<WatermarkBaseline> {
        if (outcomes.size == 0) return emptySeriesOf()
        val features = ArrayList<String>()
        for (i in 0 until outcomes.size) {
            val o = outcomes[i]
            if (o.recorded == HindsightVerdict.NEUTRAL || o.replayed == HindsightVerdict.NEUTRAL) continue
            if (o.feature !in features) features.add(o.feature)
        }
        features.sort()
        val baselines = ArrayList<WatermarkBaseline>(features.size)
        for (feature in features) {
            val scores = ArrayList<ScoringSession.Score>()
            for (i in 0 until outcomes.size) {
                val o = outcomes[i]
                if (o.feature != feature || o.recorded == HindsightVerdict.NEUTRAL || o.replayed == HindsightVerdict.NEUTRAL) continue
                scores.add(agreementScore(o.scenarioCid, o.recorded, o.replayed))
            }
            val run = ScoringSession.Run(
                corpusLastSeq = corpusSeq,
                spineCids = listOf(corpusCid),
                methodCid = ContentId.of("hermes-watermark-v1|feature=$feature|method=replay-through-our-tooling".encodeToByteArray()),
                scores = scores.toList(),
            )
            baselines.add(WatermarkBaseline(feature, run, ScoringSession.sessionCid(cas, run)))
        }
        val frozen = baselines.toList()
        return frozen.size j { i: Int -> frozen[i] }
    }

    /** Held-out recorded outcomes vs recall/expectation/queryBank predictions from a trained bag. */
    fun narsTraining(
        cas: CasStore,
        corpusCid: ContentId,
        corpusSeq: Long,
        outcomes: Series<NarsOutcome>,
    ): TrainingBaseline {
        val scores = ArrayList<ScoringSession.Score>()
        for (i in 0 until outcomes.size) {
            val o = outcomes[i]
            if (o.recorded == HindsightVerdict.NEUTRAL || o.predicted == HindsightVerdict.NEUTRAL) continue
            scores.add(agreementScore(o.scenarioCid, o.recorded, o.predicted))
        }
        val run = ScoringSession.Run(
            corpusLastSeq = corpusSeq,
            spineCids = listOf(corpusCid),
            methodCid = ContentId.of("nars-hindsight-featureset-v1|recallNear|recallByExpectation|queryBank".encodeToByteArray()),
            scores = scores.toList(),
        )
        return TrainingBaseline(run, ScoringSession.sessionCid(cas, run))
    }

    private fun agreementScore(
        scenarioCid: ContentId,
        recorded: HindsightVerdict,
        observed: HindsightVerdict,
    ): ScoringSession.Score {
        val agrees = recorded == observed
        return ScoringSession.Score(
            targetCid = scenarioCid,
            linked = if (agrees) 1 else 0,
            partial = 0,
            contentOnly = if (agrees) 0 else 1,
            density = if (agrees) 1.0 else 0.0,
        )
    }

    /** I5: tuning/forking is refused until BOTH watermark and NARS baselines exist. */
    data class BaselineRefs(
        val watermarkSessionCids: Series<ContentId>,
        val narsTrainingSessionCid: ContentId?,
    ) {
        val ready: Boolean get() = watermarkSessionCids.size > 0 && narsTrainingSessionCid != null
        fun requireReady(operation: String) {
            require(ready) { "$operation refused: Hermes watermark and NARS training baseline session cids are required" }
        }
    }
}
