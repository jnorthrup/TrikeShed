package borg.trikeshed.narsese

import borg.trikeshed.context.ElementState
import borg.trikeshed.cursor.BudgetCoord
import borg.trikeshed.dag.ReteNetwork
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.jules.BrainClient
import borg.trikeshed.lcnc.BrainClientKey
import borg.trikeshed.lcnc.CasStoreKey
import borg.trikeshed.lcnc.KifSinkKey
import borg.trikeshed.lcnc.LcncNode
import borg.trikeshed.lcnc.LcncNodeElement
import borg.trikeshed.lcnc.LcncNodeKey
import borg.trikeshed.lcnc.LcncNodeRunner
import borg.trikeshed.lcnc.LcncServiceBinding
import borg.trikeshed.lcnc.MuxContextKey
import borg.trikeshed.lcnc.ReteNetworkKey
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.lib.view
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NalNodesContextTest {
    private suspend fun await(predicate: () -> Boolean) {
        withTimeout(5_000) { while (!predicate()) delay(1) }
    }

    private fun requiredBag(runner: LcncNodeRunner) {
        val binding = runner as LcncServiceBinding
        assertEquals(1, binding.requiredKeys.size)
        assertSame(BeliefBagElement.Key, binding.requiredKeys[0])
        assertEquals(1, binding.providedKeys.size)
        assertSame(BeliefBagElement.Key, binding.providedKeys[0])
    }

    @Test
    fun recallAndBothDecayNodesUseTheReplacementBag() = runBlocking {
        val originalTicks = AtomicInteger()
        val replacementTicks = AtomicInteger()
        val original = BeliefBagElement(capacity = 32, afterTick = { originalTicks.incrementAndGet() })
        val replacement = BeliefBagElement(
            capacity = 32,
            decayFn = { BudgetCoord(it.pf * 0.5f, it.df, it.qf) },
            afterTick = { replacementTicks.incrementAndGet() },
        )
        original.open()
        replacement.open()
        try {
            suspend fun mint(bag: BeliefBagElement, angular: Long) {
                bag.intake.send(BeliefIntake.Mint(
                    SemanticSignal(angular, EvidenceCoord(Nal.UNIT, 0L), RelationKind.CAUSALITY, "subject-$angular"),
                    BudgetCoord(0.8f, 0.5f, 0.6f),
                ))
                await { bag.budgetOf(angular) != null }
            }
            mint(original, 33L)
            mint(replacement, 11L)
            mint(replacement, 22L)
            val recall = NalNodes.recallRunner(original)
            val decay = NalNodes.decayRunner(original)
            val skillDecay = NalNodes.skillDecayRunner(original)
            for (runner in arrayOf(recall, decay, skillDecay)) requiredBag(runner)
            val node = LcncNode("n", "nal.recall")
            assertEquals(33L, ((recall.run(node, emptyMap())["beliefs"] as List<*>).single() as Map<*, *>)["angular"])
            withContext(replacement) {
                for (mode in arrayOf("top", "sample", "near")) {
                    val rows = recall.run(node.copy(params = mapOf("mode" to mode, "centroid" to "11", "maxDistance" to "64")), emptyMap())["beliefs"] as List<*>
                    assertTrue(rows.isNotEmpty())
                    assertTrue(rows.all { (it as Map<*, *>)["angular"] in setOf(11L, 22L) })
                }
                decay.run(LcncNode("d", "nal.decay"), emptyMap())
                await { replacementTicks.get() == 1 }
                assertTrue(replacement.budgetOf(11L)!!.pf < 0.5f)
                skillDecay.run(LcncNode("s", "skill.decay"), emptyMap())
                await { replacementTicks.get() == 2 }
                assertTrue(replacement.budgetOf(11L)!!.pf < 0.3f)
            }
            assertEquals(0, originalTicks.get())
            assertEquals(ElementState.ACTIVE, replacement.state)
            decay.run(LcncNode("d", "nal.decay"), emptyMap())
            await { originalTicks.get() == 1 }
            assertEquals(ElementState.ACTIVE, original.state)
        } finally {
            replacement.drain()
            original.drain()
        }
    }

    @Test
    fun mintInstallsTheSelectedBagAndDelegatesWithinOneInvocation() = runBlocking {
        val original = BeliefBagElement(capacity = 32)
        val replacement = BeliefBagElement(capacity = 32)
        original.open()
        replacement.open()
        try {
            val cas = CasStore.inMemory()
            val line = "Smoke causes alarm."
            val cid = ContentId.of(line.encodeToByteArray())
            var expectedBag = original
            var invocation: LcncNodeElement? = null
            var modelCalls = 0
            var fail = false
            val brain = object : BrainClient(apiKey = "test", base = "https://test.invalid/v1", model = "test") {
                override suspend fun chat(
                    messages: List<Pair<String, String>>,
                    maxTokens: Int,
                    temperature: Double,
                    contextId: String?,
                ): String {
                    modelCalls++
                    val context = currentCoroutineContext()
                    assertSame(expectedBag, context[BeliefBagElement.Key], "the selected bag must actually be installed")
                    assertSame(assertNotNull(invocation), context[LcncNodeKey.NAL_MINT], "delegation must not construct another invocation")
                    assertEquals(ElementState.ACTIVE, invocation!!.state)
                    assertEquals(line, cas.get(cid)!!.decodeToString())
                    if (fail) error("model failed")
                    return """{"constructions":[{"subject":"smoke","relation":"causes","object":"alarm","polarity":true,"evidenceCid":"${cid.value}","dependency":"nsubj"}]}"""
                }
            }
            val kif = mutableListOf<String>()
            val mint = NalNodes.mintRunner(brain, EmptyCoroutineContext, cas, original, ReteNetwork()) { kif.add(it) }
            val services = mint as LcncServiceBinding
            val keys = setOf(BrainClientKey, MuxContextKey, CasStoreKey, ReteNetworkKey, KifSinkKey, BeliefBagElement.Key)
            assertEquals(keys, services.requiredKeys.view.toSet())
            assertEquals(keys, services.providedKeys.view.toSet())
            val registry = mapOf("nal.mint" to LcncNodeRunner { node, inputs ->
                invocation = assertNotNull(currentCoroutineContext()[LcncNodeKey.NAL_MINT])
                mint.execute(node, inputs)
            })
            val node = LcncNode("m", "nal.mint")
            val inputs = mapOf("lines" to listOf(line))
            val runner = registry.getValue(node.type)
            assertNull(currentCoroutineContext()[BeliefBagElement.Key])
            val first = runner.run(node, inputs)
            await { original.size == 1 }
            assertEquals(1, (first["accepted"] as List<*>).size)
            assertEquals(0, replacement.size)
            assertEquals(ElementState.CLOSED, invocation!!.state)
            assertNull(currentCoroutineContext()[BeliefBagElement.Key], "default binding must not leak to the caller")

            expectedBag = replacement
            withContext(replacement) { runner.run(node, inputs) }
            await { replacement.size == 1 }
            assertEquals(2, modelCalls)
            assertEquals(listOf("(causes smoke alarm)", "(causes smoke alarm)"), kif)
            assertEquals(ElementState.CLOSED, invocation!!.state)
            assertEquals(ElementState.ACTIVE, original.state)
            assertEquals(ElementState.ACTIVE, replacement.state)

            fail = true
            val failure = runCatching { withContext(replacement) { runner.run(node, inputs) } }.exceptionOrNull()
            assertEquals("model failed", assertNotNull(failure).message)
            assertEquals(ElementState.CLOSED, invocation!!.state)
            assertEquals(ElementState.ACTIVE, replacement.state, "failed invocations also leave the borrowed service alive")
            assertTrue(replacement.supervisor.isActive)
        } finally {
            replacement.drain()
            original.drain()
        }
    }
}
