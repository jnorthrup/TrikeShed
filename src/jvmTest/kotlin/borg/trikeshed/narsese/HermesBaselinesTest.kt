package borg.trikeshed.narsese

import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.couch.Document
import borg.trikeshed.couch.Field
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * I3/I4/I5 gates: the watermark replay executes the real curation lane per scenario,
 * the NARS baseline predicts a deterministic held-out split from the trained bag,
 * session cids are reproducible, and computeAndLand persists them as blackboard keys.
 * Fixture data is synthesized in-test — the live ~/.hermes profile is never read.
 */
class HermesBaselinesTest {

    private fun pair(id: String, subject: String, marker: String): Pair<CuratorImpulse, ReplayScenario> =
        CuratorImpulse(CuratorImpulseKind.ADOPT, subject, "fixture", proposalCid = id) to
            ReplayScenario(
                id, subject,
                listOf(
                    ReplayTurn("user", "begin $subject"),
                    ReplayTurn("curator", "outcome recorded $marker"),
                ).toSeries(),
            )

    private fun fixture(n: Int): Pair<List<CuratorImpulse>, List<ReplayScenario>> {
        val pairs = (1..n).map { i -> pair("s$i", "subject-$i", if (i % 2 == 1) "[pass]" else "[fail]") }
        return pairs.map { it.first } to pairs.map { it.second }
    }

    @Test
    fun watermarkReplayExecutesTheLaneAndSessionCidsReproduce() = runBlocking {
        val (impulses, scenarios) = fixture(4)
        val cas = CasStore.inMemory()
        val corpusCid = ContentId.of("fixture-corpus".encodeToByteArray())

        val outcomes1 = HermesBaselines.replayCurationLane(impulses.toSeries(), scenarios.toSeries())
        assertTrue(outcomes1.size >= 2, "non-neutral scenarios produce outcomes: ${outcomes1.size}")
        for (i in 0 until outcomes1.size) {
            assertEquals(HermesDesignDistiller.Feature.CURATION_RECORDING.slug, outcomes1[i].feature,
                "only the implemented replayer's feature appears — nothing fabricated")
        }
        val w1 = HermesBaselineScoring.watermark(cas, corpusCid, 4L, outcomes1)
        val outcomes2 = HermesBaselines.replayCurationLane(impulses.toSeries(), scenarios.toSeries())
        val w2 = HermesBaselineScoring.watermark(cas, corpusCid, 4L, outcomes2)
        assertEquals(w1.size, w2.size)
        for (i in 0 until w1.size) {
            assertEquals(w1[i].sessionCid, w2[i].sessionCid, "identical corpus+replay → identical session cid")
        }
    }

    @Test
    fun heldOutPredictionIsDeterministicAndSplitsByScenarioOrder() = runBlocking {
        val (impulses, scenarios) = fixture(6)
        val p1 = HermesBaselines.predictHeldOut(impulses.toSeries(), scenarios.toSeries())
        val p2 = HermesBaselines.predictHeldOut(impulses.toSeries(), scenarios.toSeries())
        assertTrue(p1.size >= 1, "held-out half produces outcomes: ${p1.size}")
        assertEquals(p1.size, p2.size)
        for (i in 0 until p1.size) {
            assertEquals(p1[i].scenarioCid, p2[i].scenarioCid)
            assertEquals(p1[i].predicted, p2[i].predicted, "prediction is deterministic per scenario")
        }
    }

    @Test
    fun computeAndLandPersistsBaselineSessionCidsAndRefsAreReady() = runBlocking {
        val (impulses, scenarios) = fixture(4)
        val couch = CouchStoreFactory.inMemory()
        val cas = CasStore.inMemory()
        for (i in 1..4) {
            val id = "${HermesBaselines.TRANSCRIPT_PREFIX}s$i/1.md"
            val cid = ContentId.of("transcript-$i".encodeToByteArray())
            couch.put(Document(id, listOf(Field("contentId", cid.value))), couch.head.getRev(id))
        }
        val blackboard = ConfixBlackboard()
        val landed = HermesBaselines.computeAndLand(
            couch, cas, blackboard, impulses.toSeries(), scenarios.toSeries(),
        )
        assertNotNull(landed, "corpus snapshot exists → baselines land")
        assertTrue(landed.watermark.size >= 1, "at least the curation-recording watermark session")
        val key = "baseline/hermes-watermark/${HermesDesignDistiller.Feature.CURATION_RECORDING.slug}"
        val persisted = blackboard.get(key)
        assertTrue(persisted is Map<*, *> && persisted["sessionCid"] == landed.watermark[0].sessionCid.value,
            "watermark session cid persisted at $key: $persisted")
        assertTrue(landed.refs.ready, "I5 gate: refs become ready once both baselines exist")
        landed.refs.requireReady("fixture-tune")

        // Reproducibility across a second computation on the same corpus.
        val again = HermesBaselines.computeAndLand(
            couch, cas, blackboard, impulses.toSeries(), scenarios.toSeries(),
        )
        assertNotNull(again)
        assertEquals(landed.corpus.cid, again.corpus.cid)
        assertEquals(landed.watermark[0].sessionCid, again.watermark[0].sessionCid)
    }
}
