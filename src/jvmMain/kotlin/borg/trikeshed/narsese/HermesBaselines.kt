package borg.trikeshed.narsese

import borg.trikeshed.couch.CouchStore
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.emptySeriesOf
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import kotlinx.coroutines.delay

/**
 * I3/I4/I5 producers — the two lanes [HermesBaselineScoring] was waiting for, plus
 * corpus identity and baseline persistence. A new object on purpose: no API is added
 * to any existing class; everything here drives existing public surface only.
 *
 * What the v1 watermark honestly measures: **execution fidelity of the curation
 * lane** (feature = curation-recording). Each scenario is replayed through a FRESH
 * CuratorImpulseElement — the real assess → bank → mint pipeline executes — and the
 * replayed verdict agrees with the recorded one only when the lane actually lands
 * the scenario. Features whose replayers do not exist yet produce NO outcomes —
 * absent, never fabricated.
 *
 * The NARS training baseline is a real held-out prediction: train on the even half
 * (deterministic ordering by scenario id), probe the TRAINED BAG's beliefs with
 * `recallNear` for each held-out scenario, and score agreement against the recorded
 * hindsight verdicts.
 */
object HermesBaselines {

    const val TRANSCRIPT_PREFIX: String = "/corpus/hermes/transcripts/"

    /** Bounded, deterministic scenario window: sorted by scenario id, capped. */
    const val MAX_SCENARIOS: Int = 64

    /** Hamming radius for held-out probes: the 16 object-simhash bits legitimately differ per scenario. */
    const val PROBE_RADIUS: Int = 24

    data class Corpus(val cid: ContentId, val seq: Long)

    data class Landed(
        val corpus: Corpus,
        val watermark: Series<HermesBaselineScoring.WatermarkBaseline>,
        val training: HermesBaselineScoring.TrainingBaseline?,
        val refs: HermesBaselineScoring.BaselineRefs,
    )

    /**
     * Corpus point-in-time from the landed transcript docs: the sorted content cids
     * hash to the corpus cid; seq is the corpus's own growth counter (transcript doc
     * count — monotone, deterministic, corpus-scoped). Null when nothing has landed.
     */
    fun corpusSnapshot(couch: CouchStore): Corpus? {
        val cids = couch.all()
            .filter { it.id.startsWith(TRANSCRIPT_PREFIX) }
            .mapNotNull { d -> d.fields.firstOrNull { it.name == "contentId" }?.value?.toString() }
            .sorted()
        if (cids.isEmpty()) return null
        val cid = ContentId.of(("hermes-corpus-v1\n" + cids.joinToString("\n")).encodeToByteArray())
        return Corpus(cid, cids.size.toLong())
    }

    /** Deterministic (impulse, scenario) pairs: scenario order by id, first impulse whose subject matches. */
    private fun pairs(
        impulses: Series<CuratorImpulse>,
        scenarios: Series<ReplayScenario>,
    ): List<Pair<CuratorImpulse, ReplayScenario>> {
        val byId = ArrayList<ReplayScenario>(scenarios.size)
        for (i in 0 until scenarios.size) byId.add(scenarios[i])
        byId.sortBy { it.scenarioId }
        val out = ArrayList<Pair<CuratorImpulse, ReplayScenario>>()
        for (s in byId) {
            var matched: CuratorImpulse? = null
            for (i in 0 until impulses.size) {
                if (impulses[i].subject == s.impulseSubject) { matched = impulses[i]; break }
            }
            matched?.let { out.add(it to s) }
            if (out.size >= MAX_SCENARIOS) break
        }
        return out
    }

    private fun recordedVerdict(impulse: CuratorImpulse, scenario: ReplayScenario): HindsightVerdict {
        val assessed = CuratorImpulseRecipient.assess(
            listOf(impulse).toSeries(), listOf(scenario).toSeries(),
        )
        return if (assessed.size == 0) HindsightVerdict.NEUTRAL else assessed[0].verdict
    }

    private fun opposite(v: HindsightVerdict): HindsightVerdict = when (v) {
        HindsightVerdict.SUPPORTED -> HindsightVerdict.REFUTED
        HindsightVerdict.REFUTED -> HindsightVerdict.SUPPORTED
        HindsightVerdict.NEUTRAL -> HindsightVerdict.NEUTRAL
    }

    private fun scenarioCid(scenario: ReplayScenario): ContentId =
        ContentId.of("scenario:${scenario.scenarioId}".encodeToByteArray())

    /** Wait until the bag's intake channel has drained (stable belief count, bounded). */
    private suspend fun settle(bag: BeliefBagElement) {
        var last = -1
        repeat(25) {
            val now = bag.recallNear(0L, 64).size
            if (now == last) return
            last = now
            delay(20)
        }
    }

    /**
     * I3 v1: the curation-recording replayer. Each scenario runs through a fresh
     * element; replayed = recorded only when the executed lane lands it.
     */
    suspend fun replayCurationLane(
        impulses: Series<CuratorImpulse>,
        scenarios: Series<ReplayScenario>,
    ): Series<HermesBaselineScoring.WatermarkOutcome> {
        val out = ArrayList<HermesBaselineScoring.WatermarkOutcome>()
        for ((impulse, scenario) in pairs(impulses, scenarios)) {
            val recorded = recordedVerdict(impulse, scenario)
            if (recorded == HindsightVerdict.NEUTRAL) continue
            val bag = BeliefBagElement(capacity = 256)
            bag.open()
            val element = CuratorImpulseElement(bag, parentJob = null)
            element.open()
            val landed = runCatching {
                element.teach(listOf(impulse).toSeries(), listOf(scenario).toSeries())
            }.getOrDefault(emptyList())
            out.add(
                HermesBaselineScoring.WatermarkOutcome(
                    feature = HermesDesignDistiller.Feature.CURATION_RECORDING.slug,
                    scenarioCid = scenarioCid(scenario),
                    recorded = recorded,
                    replayed = if (landed.isNotEmpty()) recorded else opposite(recorded),
                ),
            )
        }
        return out.toSeries()
    }

    /**
     * I4: train on the even half (sorted by scenario id), predict the held-out odd
     * half from the trained bag via [BeliefBagElement.recallNear]. NEUTRAL when the
     * bag recalls nothing near the probe.
     */
    suspend fun predictHeldOut(
        impulses: Series<CuratorImpulse>,
        scenarios: Series<ReplayScenario>,
    ): Series<HermesBaselineScoring.NarsOutcome> {
        val all = pairs(impulses, scenarios)
        if (all.size < 2) return emptySeriesOf()
        val train = all.filterIndexed { i, _ -> i % 2 == 0 }
        val holdout = all.filterIndexed { i, _ -> i % 2 == 1 }

        val bag = BeliefBagElement(capacity = 512)
        bag.open()
        val element = CuratorImpulseElement(bag, parentJob = null)
        element.open()
        runCatching {
            element.teach(
                train.map { it.first }.toSeries(),
                train.map { it.second }.toSeries(),
            )
        }
        settle(bag)

        val out = ArrayList<HermesBaselineScoring.NarsOutcome>()
        for ((impulse, scenario) in holdout) {
            val recorded = recordedVerdict(impulse, scenario)
            if (recorded == HindsightVerdict.NEUTRAL) continue
            val probe = AngularCodec.encode(
                relation = RelationKind.MATCH,
                taxonomyKey = "curator/${impulse.kind.token}",
                subjectTerm = impulse.term(),
                objectTerm = "scenario_${scenario.scenarioId}",
            )
            val near = bag.recallNear(probe, PROBE_RADIUS)
            val predicted = if (near.size == 0) {
                HindsightVerdict.NEUTRAL
            } else if (near[0].relation == RelationKind.CONTRADICTION) {
                HindsightVerdict.REFUTED
            } else {
                HindsightVerdict.SUPPORTED
            }
            out.add(HermesBaselineScoring.NarsOutcome(scenarioCid(scenario), recorded, predicted))
        }
        return out.toSeries()
    }

    /**
     * I5: compute both baselines against the current corpus snapshot and land the
     * session cids as blackboard keys (`baseline/hermes-watermark/<feature>`,
     * `baseline/nars-training`). Returns null when no corpus has landed yet.
     */
    suspend fun computeAndLand(
        couch: CouchStore,
        cas: CasStore,
        blackboard: ConfixBlackboard,
        impulses: Series<CuratorImpulse>,
        scenarios: Series<ReplayScenario>,
    ): Landed? {
        val corpus = corpusSnapshot(couch) ?: return null
        val watermark = HermesBaselineScoring.watermark(
            cas, corpus.cid, corpus.seq, replayCurationLane(impulses, scenarios),
        )
        val training = predictHeldOut(impulses, scenarios).let { outcomes ->
            if (outcomes.size == 0) null else HermesBaselineScoring.narsTraining(cas, corpus.cid, corpus.seq, outcomes)
        }
        for (i in 0 until watermark.size) {
            val b = watermark[i]
            blackboard.put(
                "baseline/hermes-watermark/${b.feature}",
                mapOf(
                    "sessionCid" to b.sessionCid.value,
                    "scores" to b.run.scores.size.toString(),
                    "corpusCid" to corpus.cid.value,
                    "corpusSeq" to corpus.seq.toString(),
                ),
                "baseline",
            )
        }
        training?.let { t ->
            blackboard.put(
                "baseline/nars-training",
                mapOf(
                    "sessionCid" to t.sessionCid.value,
                    "scores" to t.run.scores.size.toString(),
                    "corpusCid" to corpus.cid.value,
                    "corpusSeq" to corpus.seq.toString(),
                ),
                "baseline",
            )
        }
        val cids = ArrayList<ContentId>(watermark.size)
        for (i in 0 until watermark.size) cids.add(watermark[i].sessionCid)
        return Landed(
            corpus = corpus,
            watermark = watermark,
            training = training,
            refs = HermesBaselineScoring.BaselineRefs(cids.toSeries(), training?.sessionCid),
        )
    }
}
