package borg.trikeshed.wiki

import borg.trikeshed.job.ContentId
import borg.trikeshed.narsese.CausalConstruction
import borg.trikeshed.narsese.ConstructionPatternGate
import borg.trikeshed.parse.json.JsonSupport
import borg.trikeshed.platform.CommonResources
import borg.trikeshed.platform.text
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integrity and incumbent-score audit for the immutable 1A trainer.
 *
 * This test deliberately records the incumbent's directional miss. It does not
 * pretend the candidate has been validated or promoted.
 */
class HermesWikiTrainer1ATest {

    private fun text(path: String): String =
        assertNotNull(CommonResources.text("${HermesWikiTrainerCorpus.ROOT_1A}/$path"), path)

    @Suppress("UNCHECKED_CAST")
    private fun objectAt(path: String): Map<String, Any?> =
        JsonSupport.parse(text(path)) as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun jsonLines(path: String): List<Map<String, Any?>> = text(path)
        .lineSequence()
        .filter { it.isNotBlank() }
        .map { JsonSupport.parse(it) as Map<String, Any?> }
        .toList()

    @Test
    fun manifestCidsNameTheExactBundledTranscriptBytes() {
        val manifest = objectAt("manifest.json")
        assertEquals(HermesWikiTrainerCorpus.SET_1A, manifest["setId"])
        val authority = manifest["designAuthority"] as Map<*, *>
        assertEquals(HermesWikiTrainerCorpus.PAPER_ARXIV, authority["arxiv"])
        assertTrue((authority["role"] as String).contains("not local 1A results"))

        val traces = manifest["traces"] as List<Map<String, Any?>>
        assertEquals(4, traces.size)
        for (trace in traces) {
            val path = "${HermesWikiTrainerCorpus.ROOT_1A}/${trace["path"]}"
            val bytes = assertNotNull(CommonResources.bytes(path), path)
            assertEquals(trace["cid"], ContentId.of(bytes).value, "immutable bytes changed: $path")
            assertTrue(bytes.size <= 15_000, "$path exceeds the trainer trace cap")
        }

        val train = traces.filter { it["split"] == "train" }.map { it["cid"] }.toSet()
        val validation = traces.filter { it["split"] == "validation" }.map { it["cid"] }.toSet()
        assertTrue(train.intersect(validation).isEmpty(), "training and validation traces must be disjoint")
        assertEquals(setOf("pass", "fail"), traces.filter { it["split"] == "train" }.map { it["outcome"] }.toSet())
        assertEquals(setOf("pass", "fail"), traces.filter { it["split"] == "validation" }.map { it["outcome"] }.toSet())
    }

    @Test
    fun dependencyFactsAndNarsDecisionsShareExactEvidenceCoordinates() {
        val dependencies = jsonLines("nlp/dependencies.jsonl").associateBy { it["case"] as String }
        val decisions = jsonLines("nars/causal-decisions.jsonl")
        assertEquals(4, dependencies.size)
        assertEquals(4, decisions.size)

        for (decision in decisions) {
            val dep = assertNotNull(dependencies[decision["case"] as String])
            assertEquals(decision["traceCid"], dep["traceCid"])
            assertEquals(decision["sourceSpan"], dep["sourceSpan"])
            // Confix reifies a non-empty JSON array as a List and an empty one as
            // EmptySeries. Canonical JSON is the target-neutral emptiness check.
            val hasSupport = JsonSupport.stringify(dep["causalSupport"]) != "[]"
            if (decision["expectedDecision"] == "admit") {
                assertTrue(hasSupport)
                assertNotNull(decision["narsEvidence"])
            } else {
                assertNull(decision["narsEvidence"], "refused links must not mint NARS evidence")
            }
        }
    }

    @Test
    fun brainClientMuxTranslationIsBidirectionalButNeverEvidenceAuthority() {
        val contracts = jsonLines("translation/round-trips.jsonl")
        assertEquals(setOf("nlpcore_to_nars", "nars_to_nlpcore"), contracts.map { it["direction"] }.toSet())

        val dependency = jsonLines("nlp/dependencies.jsonl")
            .first { it["case"] == "train-explicit-cause-pass" }
        for (contract in contracts) {
            val path = contract["path"] as List<*>
            assertTrue("BrainClient" in path)
            assertTrue("ModelMux" in path)
            assertTrue((contract["authority"] as String).contains("only"))
        }

        val reverse = contracts.first { it["direction"] == "nars_to_nlpcore" }
        val reverseOutput = reverse["output"] as Map<*, *>
        assertEquals(dependency["traceCid"], reverseOutput["traceCid"])
        assertEquals(dependency["sourceSpan"], reverseOutput["sourceSpan"])
        assertEquals(dependency["sentenceIndex"], reverseOutput["sentenceIndex"])
    }

    @Test
    fun incumbentScoresThreeOfFourAndExposesTheDirectionalHallucination() {
        val decisions = jsonLines("nars/causal-decisions.jsonl")
        val actualByCase = linkedMapOf<String, String>()

        for (decision in decisions) {
            val proposal = decision["proposal"] as Map<*, *>
            val construction = CausalConstruction(
                subject = proposal["subject"] as String,
                relation = proposal["relation"] as String,
                obj = proposal["object"] as String,
                polarity = proposal["polarity"] as Boolean,
                evidenceCid = ContentId(decision["traceCid"] as String),
                dependency = proposal["dependency"] as String,
            )
            actualByCase[decision["case"] as String] =
                if (ConstructionPatternGate.validateLine(construction, decision["sourceSpan"] as String) == null) "admit" else "refuse"
        }

        val correct = decisions.count { actualByCase[it["case"]] == it["expectedDecision"] }
        val expected = objectAt("validation/expected-results.json")["incumbent"] as Map<*, *>
        assertEquals((expected["correct"] as Number).toInt(), correct)
        assertEquals(3, correct)
        assertEquals("admit", actualByCase["validation-reversed-cause-fail"])
        assertEquals(
            "refuse",
            decisions.first { it["case"] == "validation-reversed-cause-fail" }["expectedDecision"],
        )
    }
}
