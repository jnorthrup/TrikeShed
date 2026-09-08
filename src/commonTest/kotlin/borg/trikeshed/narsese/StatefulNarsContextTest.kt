package borg.trikeshed.narsese

import borg.trikeshed.context.ElementState
import borg.trikeshed.cursor.BudgetCoord
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.kif.KifKnowledgeBase
import borg.trikeshed.lcnc.LcncNode
import borg.trikeshed.lcnc.LcncNodeRunner
import borg.trikeshed.lcnc.LcncServiceBinding
import borg.trikeshed.lib.emptySeriesOf
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.rdf.RdfGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StatefulNarsContextTest {
    private fun node(type: String, params: Map<String, String> = emptyMap()) =
        LcncNode(id = "n", type = type, params = params)

    private suspend fun await(predicate: () -> Boolean) = withContext(Dispatchers.Default) {
        withTimeout(5_000) { while (!predicate()) delay(1) }
    }

    private suspend fun mint(bag: BeliefBagElement, angular: Long) {
        bag.intake.send(BeliefIntake.Mint(
            SemanticSignal(angular, EvidenceCoord(2 * Nal.UNIT, 0L), RelationKind.CAUSALITY, "subject-$angular"),
            BudgetCoord(0.8f, 0.5f, 0.6f),
        ))
        await { bag.budgetOf(angular) != null }
    }

    private fun required(runner: LcncNodeRunner, key: CoroutineContext.Key<*>) {
        val binding = runner as LcncServiceBinding
        assertEquals(1, binding.requiredKeys.size)
        assertSame(key, binding.requiredKeys[0])
        assertEquals(1, binding.providedKeys.size)
        assertSame(key, binding.providedKeys[0])
    }

    @Test
    fun bagReplacementRedirectsReadsAndIntakeAndSurvivesInvocations() = runTest {
        val original = BeliefBagElement(capacity = 32)
        val replacement = BeliefBagElement(capacity = 32)
        original.open()
        replacement.open()
        try {
            mint(original, 11L)
            mint(replacement, 11L)
            mint(replacement, 22L)
            val originalSnapshot = original.snapshot()
            val introspect = BeliefsNodes.introspectRunner(original)
            val resonate = BeliefsNodes.resonateRunner(original) { "gloss:$it" }
            val attend = BeliefsNodes.attendRunner(original)
            val reinforce = BeliefsNodes.reinforceRunner(original)
            for (runner in arrayOf(introspect, resonate, attend, reinforce)) required(runner, BeliefBagElement.Key)

            assertEquals(1, (introspect.run(node("beliefs.introspect"), emptyMap())["field"] as Map<*, *>)["size"])
            withContext(replacement) {
                assertEquals(2, (introspect.run(node("beliefs.introspect"), emptyMap())["field"] as Map<*, *>)["size"])
                for (mode in arrayOf("raw", "whitened")) {
                    val peaks = resonate.run(node("beliefs.resonate", mapOf("mode" to mode)), mapOf("goal" to "alarm"))
                    val rows = peaks["synonymPeaks"] as List<*>
                    assertEquals(setOf("11", "22"), rows.map { (it as Map<*, *>)["angular"] }.toSet())
                    assertTrue(rows.all { (it as Map<*, *>)["gloss"] == "gloss:${it["angular"]}" })
                }
                attend.run(node("nal.attend", mapOf("angular" to "11", "p" to "0.3")), emptyMap())
                reinforce.run(node("nal.reinforce", mapOf("angular" to "11", "wPlus" to "3")), emptyMap())
            }
            await {
                replacement.budgetOf(11L)!!.pf < 0.4f &&
                    replacement.snapshot().values.single { it.angular == 11L }.evidence.positive == 5 * Nal.UNIT
            }
            assertEquals(originalSnapshot, original.snapshot())
            assertEquals(ElementState.ACTIVE, original.state)
            assertEquals(ElementState.ACTIVE, replacement.state)
            mint(replacement, 33L)
            assertEquals(3, replacement.size, "the owner can keep using the borrowed service")
        } finally {
            replacement.drain()
            original.drain()
        }
    }

    @Test
    fun reviewReplacementKeepsItsOwnedBagAndTheSuppliedGlossSink() = runTest {
        val originalBag = BeliefBagElement(capacity = 32)
        val replacementBag = BeliefBagElement(capacity = 32)
        val original = TurnReviewElement(originalBag)
        val replacement = TurnReviewElement(replacementBag)
        originalBag.open()
        replacementBag.open()
        original.open()
        replacement.open()
        try {
            val glosses = mutableListOf<String>()
            val runner = BeliefsNodes.reviewRunner(original) { _, gloss -> glosses.add(gloss) }
            required(runner, TurnReviewElement.Key)
            val facts = mapOf("facts" to listOf(mapOf("verb" to "compile", "context" to "build", "ok" to true)))
            // Review owns its bag; an unrelated bag key does not replace that ownership.
            val out = withContext(originalBag + replacement) {
                runner.run(node("beliefs.review"), facts)
            }
            await { replacementBag.size == 1 }
            assertEquals(0, originalBag.size)
            assertEquals(out["glosses"], glosses)
            assertEquals(1, glosses.size)
            runner.run(node("beliefs.review"), facts)
            await { originalBag.size == 1 }
            assertEquals(2, glosses.size)
            assertEquals(ElementState.ACTIVE, original.state)
            assertEquals(ElementState.ACTIVE, replacement.state)
        } finally {
            replacement.drain()
            original.drain()
            replacementBag.drain()
            originalBag.drain()
        }
    }

    @Test
    fun reteReplacementRedirectsAdmissionAndPreservesOfferedRuleDurability() = runTest {
        val bag = BeliefBagElement(capacity = 32)
        val original = CausalityReteElement(bag, emptySeriesOf())
        val replacement = CausalityReteElement(bag, emptySeriesOf())
        bag.open()
        original.open()
        replacement.open()
        try {
            val cas = CasStore.inMemory()
            val writes = mutableListOf<ContentId>()
            var target = replacement
            val admit = RuleNodes.ruleAdmitRunner(original) { rule ->
                assertTrue((0 until target.rules.size).any { target.rules[it].ruleCid == rule.ruleCid })
                writes.add(cas.put(rule.ruleCid.value.encodeToByteArray()))
                error("ledger failure after recording must not undo live admission")
            }
            val fromKg = RuleNodes.rulesFromKgRunner(original)
            required(admit, CausalityReteElement.Key)
            required(fromKg, CausalityReteElement.Key)
            val rule = node("nal.rule.admit", mapOf("antecedent" to "fire", "consequent" to "smoke"))
            withContext(replacement) {
                val out = admit.run(rule, emptyMap())
                assertEquals(1, out["admitted"])
                assertEquals((out["ruleCids"] as List<*>).single(), cas.get(writes.single())!!.decodeToString())
                assertEquals(0, admit.run(rule, emptyMap())["admitted"])
                assertEquals(2, writes.size, "duplicate offers still reach the durability callback")
                val bridged = fromKg.run(node("nal.rules.fromKg"), mapOf("kgText" to "(=> heat expansion)\n(precedes rain flood)"))
                assertEquals(1, bridged["admitted"])
                assertEquals(1, bridged["rejectedTemporal"])
            }
            assertEquals(0, original.rules.size)
            assertEquals(2, replacement.rules.size)
            target = original
            assertEquals(1, admit.run(rule, emptyMap())["admitted"])
            assertEquals(3, writes.size)
            assertEquals(ElementState.ACTIVE, original.state)
            assertEquals(ElementState.ACTIVE, replacement.state)
        } finally {
            replacement.drain()
            original.drain()
            bag.drain()
        }
    }

    @Test
    fun freezeAndThawUseSelectedBagsWithTheSuppliedCasAndKnowledgeBase() = runTest {
        val original = BeliefBagElement(capacity = 32)
        val source = BeliefBagElement(capacity = 32)
        val target = BeliefBagElement(capacity = 32)
        original.open()
        source.open()
        target.open()
        try {
            mint(original, 33L)
            mint(source, 11L)
            mint(source, 22L)
            val cas = CasStore.inMemory()
            val kif = KifKnowledgeBase().apply { assertKif("(instance Freeze RoundTrip)") }
            var projections = 0
            val freeze = StateNodes.freezeRunner(original, kif, {
                projections++
                RdfGraph(emptyList())
            }, cas)
            val restoredKif = KifKnowledgeBase()
            val thaw = StateNodes.thawRunner(original, cas, restoredKif)
            required(freeze, BeliefBagElement.Key)
            required(thaw, BeliefBagElement.Key)
            val frozen = withContext(source) { freeze.run(node("state.freeze"), emptyMap()) }
            val snapshot = frozen["snapshot"] as Map<*, *>
            assertEquals(2, snapshot["bagSize"])
            assertEquals(1, projections)
            assertNotNull(cas.get(ContentId(snapshot["cid"] as String)))
            val bagBytes = assertNotNull(cas.get(ContentId(snapshot["bagCid"] as String))).decodeToString()
            assertTrue("\"angular\":11," in bagBytes && "\"angular\":22," in bagBytes)
            assertTrue("\"angular\":33," !in bagBytes)
            val restored = withContext(target) {
                thaw.run(node("state.thaw", mapOf("cid" to snapshot["cid"] as String)), emptyMap())
            }["restored"] as Map<*, *>
            await { target.size == 2 }
            assertEquals(source.snapshot(), target.snapshot(), "signals and budgets reach the replacement bag")
            assertEquals(2, restored["bagRestored"])
            assertEquals(1, restored["kifAssertionsRestored"])
            assertTrue("(instance Freeze RoundTrip)" in restoredKif.toKifFile())
            assertEquals(1, original.size)
            assertEquals(ElementState.ACTIVE, source.state)
            assertEquals(ElementState.ACTIVE, target.state)
        } finally {
            target.drain()
            source.drain()
            original.drain()
        }
    }
}
