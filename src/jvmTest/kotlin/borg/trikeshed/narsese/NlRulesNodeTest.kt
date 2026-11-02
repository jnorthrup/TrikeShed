package borg.trikeshed.narsese

import borg.trikeshed.job.ContentId
import borg.trikeshed.kif.KifExpr
import borg.trikeshed.kif.KifKnowledgeBase
import borg.trikeshed.lcnc.LcncNode
import borg.trikeshed.lcnc.LcncNodeRunner
import borg.trikeshed.lib.*
import borg.trikeshed.nlp.*
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NlRulesNodeTest {
    private fun reading(text: String): NlpDocument {
        val subject = "Rain"
        val verb = if ("implies" in text) "implies" else "causes"
        val lemma = if (verb == "implies") "imply" else "cause"
        val a = text.indexOf(subject)
        val v = text.indexOf(verb, a + subject.length)
        val o = text.indexOf("floods", v + verb.length)
        val dot = text.indexOf('.', o + 6)
        require(a >= 0 && v >= 0 && o >= 0 && dot >= 0)
        return NlpDocument(text, s_[NlpSentence(0, a, dot + 1, s_[
            NlpToken(1, a, a + 4, subject, "rain", "NN", "O"),
            NlpToken(2, v, v + verb.length, verb, lemma, "VBZ", "O"),
            NlpToken(3, o, o + 6, "floods", "flood", "NNS", "O"),
            NlpToken(4, dot, dot + 1, ".", ".", ".", "O")
        ], s_[NlpDependency(0, 2, "root"), NlpDependency(2, 1, "nsubj"),
            NlpDependency(2, 3, "obj"), NlpDependency(2, 4, "punct")])])
    }

    private fun lane() = ClassRuleLane(maxSources = 16, maxSourceChars = 1_024,
        readDocument = ::reading, classLookup = { if (it == "rain") 17 else null })

    private fun negativeReading(): NlpDocument {
        val text = "Rain does not cause floods."
        return NlpDocument(text, s_[NlpSentence(0, 0, text.length, s_[
            NlpToken(1, 0, 4, "Rain", "rain", "NN", "O"),
            NlpToken(2, 5, 9, "does", "do", "VBZ", "O"),
            NlpToken(3, 10, 13, "not", "not", "RB", "O"),
            NlpToken(4, 14, 19, "cause", "cause", "VB", "O"),
            NlpToken(5, 20, 26, "floods", "flood", "NNS", "O"),
            NlpToken(6, 26, 27, ".", ".", ".", "O")
        ], s_[NlpDependency(0, 4, "root"), NlpDependency(4, 1, "nsubj"),
            NlpDependency(4, 2, "aux"), NlpDependency(4, 3, "advmod"),
            NlpDependency(4, 5, "obj"), NlpDependency(4, 6, "punct")])])
    }

    @Test
    fun registeredNodeEmitsUnreviewedExactCandidatesWithoutLedgerOrBankEffects() = runBlocking {
        val text = "  Rain implies floods. \n"
        val bank = KifKnowledgeBase()
        val runners = HashMap<String, LcncNodeRunner>()
        lane().use { lane ->
            NlRulesNode.register(runners, bank, lane)
            val node = LcncNode("n", NlRulesNode.TYPE, mapOf("promote" to "0", "mode" to "legacy"))
            repeat(2) {
                val out = runners.getValue(NlRulesNode.TYPE).execute(node, mapOf("text" to text, "ask" to "Rain"))
                val candidates = out["candidates"] as List<*>
                assertEquals(1, candidates.size)
                val candidate = candidates.single() as Map<*, *>
                assertEquals(ContentId.of(text.encodeToByteArray()).value, candidate["sourceDigest"])
                assertEquals(false, candidate["sourceRetained"])
                assertEquals(2, candidate["begin"])
                assertEquals("Rain implies floods.", candidate["quote"])
                assertEquals("imply", candidate["predicate"])
                assertEquals("unreviewed", candidate["reviewStatus"])
                assertEquals(true, candidate["polarity"])
                assertEquals("pattern-structural-not-parser-confidence", candidate["weightKind"])
                assertTrue(candidate["structuralWeight"] is Float)
                assertEquals(emptyList<Any>(), out["rules"])
                assertEquals(emptyList<Any>(), out["answers"])
                assertEquals("deferred-unreviewed", out["answerStatus"])
                assertEquals("not-established", out["coverage"])
                assertEquals(0, lane.ledger.size)
                assertEquals(0, lane.rete.size)
            }
        }
        assertTrue(bank.query(KifExpr.parse("(ruleLearned ?a ?c)")).isEmpty())
        assertTrue(bank.query(KifExpr.parse("(ruleAnswer ?s ?c ?via ?f ?conf)")).isEmpty())
    }

    @Test
    fun negatedReadingRemainsNegative() = runBlocking {
        val text = "Rain does not cause floods."
        val doc = negativeReading()
        ClassRuleLane(maxSources = 1, readDocument = { doc }).use { lane ->
            val output = NlRulesNode.runner(lane).execute(LcncNode("n", NlRulesNode.TYPE), mapOf("text" to text))
            val candidate = ((output["candidates"] as List<*>).single() as Map<*, *>)
            assertEquals(false, candidate["polarity"])
            assertEquals(text, candidate["quote"])
            assertEquals(0, lane.ledger.size)
        }
    }

    @Test
    fun directCandidateHandoffRefusesPositiveAndNegativeReadings() = runBlocking {
        val bag = BeliefBagElement(capacity = 8)
        bag.open()
        val rete = CausalityReteElement(bag, rules = emptySeriesOf())
        rete.open()
        try {
            val scenarios = listOf("Rain causes floods." to reading("Rain causes floods."),
                "Rain does not cause floods." to negativeReading())
            for ((text, document) in scenarios) {
                ClassRuleLane(maxSources = 1, readDocument = { document }).use { lane ->
                    val output = NlRulesNode.runner(lane).execute(LcncNode("n", NlRulesNode.TYPE), mapOf("text" to text))
                    val candidates = output["candidates"] as List<*>
                    val candidate = candidates.single() as Map<*, *>
                    assertEquals("unreviewed", candidate["reviewStatus"])
                    val evidence = candidate["sourceEvidence"] as Map<*, *>
                    assertEquals("unretained-supplied-text", evidence["kind"])
                    assertEquals("utf16-code-units", evidence["offsetUnit"])
                    val strict = assertFailsWith<IllegalArgumentException> {
                        RuleNodes.ruleAdmitRunner(rete).run(LcncNode("admit", "nal.rule.admit"), mapOf("rules" to candidates))
                    }
                    assertTrue(strict.message.orEmpty().contains("review release"))
                    val legacy = assertFailsWith<IllegalArgumentException> {
                        RuleNodes.ruleAdmitRunner(rete, policy = RuleNodes.AdmissionPolicy.TRUSTED_HOST_LEGACY)
                            .run(LcncNode("admit", "nal.rule.admit"), mapOf("rules" to candidates))
                    }
                    assertTrue(legacy.message.orEmpty().contains("Document rule proposals"))
                    assertEquals(0, rete.rules.size)
                    assertEquals(0, lane.ledger.size)
                }
            }
        } finally { rete.drain(); bag.drain() }
    }

    @Test
    fun recognizedSentenceDoesNotClaimCoverageOfUnsupportedSentence() = runBlocking {
        val first = "Rain causes floods."
        val text = "$first No rule."
        val start = first.length + 1
        val doc = NlpDocument(text, s_[reading(first).sentences[0], NlpSentence(1, start, text.length, s_[
            NlpToken(1, start, start + 2, "No", "no", "DT", "O"),
            NlpToken(2, start + 3, start + 7, "rule", "rule", "NN", "O"),
            NlpToken(3, start + 7, start + 8, ".", ".", ".", "O")
        ], s_[NlpDependency(0, 2, "root"), NlpDependency(2, 1, "det"), NlpDependency(2, 3, "punct")])])
        ClassRuleLane(maxSources = 1, readDocument = { doc }).use { lane ->
            val out = NlRulesNode.runner(lane).execute(LcncNode("n", NlRulesNode.TYPE), mapOf("text" to text))
            assertEquals(1, (out["candidates"] as List<*>).size)
            assertEquals("not-established", out["coverage"])
            val unresolved = (out["unparsed"] as List<*>).single() as Map<*, *>
            assertTrue((unresolved["reason"] as String).contains("coverage not established"))
        }
    }

    @Test
    fun mismatchedReaderIsRefusedBeforeAnyCandidateOrLedgerMutation() = runBlocking {
        ClassRuleLane(maxSources = 1, readDocument = { reading("Rain causes floods.") }).use { lane ->
            assertFailsWith<IllegalArgumentException> {
                NlRulesNode.runner(lane).execute(LcncNode("n", NlRulesNode.TYPE), mapOf("text" to "different"))
            }
            assertEquals(0, lane.ledger.size)
            assertEquals(0, lane.rete.size)
        }
    }

    @Test
    fun legacyPromotionRequiresExplicitHostRunner() = runBlocking {
        val assertions = ArrayList<String>()
        lane().use { lane ->
            val out = NlRulesNode.legacyRunner(lane) { assertions.add(it) }.execute(
                LcncNode("n", NlRulesNode.TYPE, mapOf("promote" to "0")),
                mapOf("text" to "Rain causes floods."),
            )
            assertTrue((out["rules"] as List<*>).isNotEmpty())
            assertTrue(assertions.any { it.startsWith("(ruleLearned ") })
        }
    }
}
