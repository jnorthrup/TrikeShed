package borg.trikeshed.narsese

import borg.trikeshed.cursor.BlackboardContext
import borg.trikeshed.dag.FactId
import borg.trikeshed.dag.ReteNetwork
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** P4 not-theater gates: fixture chapter → bag+Rete, hallucination refusal, idempotent reread. */
class ConstructionReadingLoopTest {

    @Test
    fun fixtureChapterLandsBeliefsAndReteRuleFires() = runTest {
        val cas = CasStore.inMemory()
        val causeCid = cas.put("Smoke causes alarm.".encodeToByteArray())
        val ruleCid = cas.put("If fire starts then sprinklers activate.".encodeToByteArray())
        val becauseCid = cas.put("The lease remained valid because the tenant paid rent.".encodeToByteArray())
        val lines = listOf(
            ConstructionSourceLine(causeCid, "Smoke causes alarm."),
            ConstructionSourceLine(ruleCid, "If fire starts then sprinklers activate."),
            ConstructionSourceLine(becauseCid, "The lease remained valid because the tenant paid rent."),
        )
        var botCalls = 0
        val proposals = listOf(
            CausalConstruction("smoke", "causes", "alarm", true, causeCid),
            CausalConstruction("fire starts", "if_then", "sprinklers activate", true, ruleCid, StanfordDependency.MARK_IF),
            CausalConstruction("the tenant paid rent", "because", "the lease remained valid", true, becauseCid, StanfordDependency.ADVCL_BECAUSE),
        )
        val bot = ConstructionBot {
            botCalls++
            proposals.size j { i: Int -> proposals[i] }
        }
        val bag = BeliefBagElement(capacity = 32)
        bag.open()
        val rete = ReteNetwork()
        val activations = mutableListOf<borg.trikeshed.dag.Activation>()
        rete.productionSink = activations::add
        val kif = mutableListOf<String>()
        val loop = ConstructionReadingLoop(bot, cas, bag, rete, kif::add)

        val receipt = loop.read(lines.size j { i: Int -> lines[i] })
        assertEquals(1, botCalls, "only the bot seat spends; exactly one call for one chapter window")
        assertEquals(3, receipt.accepted.size)
        assertEquals(0, receipt.refused.size)
        assertEquals(3, receipt.aggregates.size)
        withTimeout(5_000) { while (bag.size != 3) delay(10) }
        assertEquals(3, bag.size, "three causal assertions land in the belief bag")
        assertEquals(3, kif.size, "each accepted aggregate is SUMO/KIF-bank-shaped")
        assertTrue(kif.all { it.startsWith("(causes ") })
        assertTrue(rete.productions.all().any { it.ruleId.startsWith("construction:") }, "if/then registered as a Rete rule")

        // Matching fact fires the registered rule.
        val factCid = ContentId.of("fact:fire".encodeToByteArray())
        rete.assert(
            FactId("book", "fire-fact"),
            mapOf("concept" to "fire starts"),
            factCid,
            BlackboardContext("book"),
        )
        assertEquals(1, activations.size)
        assertEquals("sprinklers activate", activations.single().bindings["consequent"])
        bag.drain()
    }

    @Test
    fun hallucinationGateRefusesTupleNotBackedByItsCasLine() {
        val cas = CasStore.inMemory()
        val cid = cas.put("Smoke causes alarm.".encodeToByteArray())
        val invented = CausalConstruction("smoke", "causes", "eviction", true, cid)
        val refusal = ConstructionPatternGate.validate(invented, cas)
        assertNotNull(refusal)
        assertTrue("object not present" in refusal.reason)
    }

    @Test
    fun rereadingSameChapterRevisesEvidenceInsteadOfDuplicating() = runTest {
        val cas = CasStore.inMemory()
        val cid = cas.put("Delay leads to damages.".encodeToByteArray())
        val line = ConstructionSourceLine(cid, "Delay leads to damages.")
        val proposal = CausalConstruction("delay", "leads_to", "damages", true, cid)
        var botCalls = 0
        val bot = ConstructionBot {
            botCalls++
            1 j { _: Int -> proposal }
        }
        val bag = BeliefBagElement(capacity = 8)
        bag.open()
        val loop = ConstructionReadingLoop(bot, cas, bag, ReteNetwork())
        val lines = 1 j { _: Int -> line }
        loop.read(lines)
        loop.read(lines)
        withTimeout(5_000) { while (bag.size != 1) delay(10) }
        // Give the second intake time to revise the first slot.
        repeat(10) { delay(10) }
        assertEquals(2, botCalls, "two explicit reads invoke the bot seat twice")
        assertEquals(1, bag.size, "same aggregate angular revises one slot")
        val signal = bag.snapshot().values.single()
        assertEquals(1L, signal.evidence.positive,
            "same evidence CID is overlap-deduped; reread does not double-count")
        bag.drain()
    }

    @Test
    fun deterministicGateFoldAndLandingPathsMakeNoBotCalls() = runTest {
        val cas = CasStore.inMemory()
        val cid = cas.put("A results in B.".encodeToByteArray())
        val c = CausalConstruction("a", "results_in", "b", true, cid)
        var botCalls = 0
        val bot = ConstructionBot { botCalls++; 1 j { _: Int -> c } }
        // Gate itself is quota-free.
        assertEquals(null, ConstructionPatternGate.validate(c, cas))
        assertEquals(0, botCalls)
        // Merely constructing the loop / deterministic components spends nothing.
        val bag = BeliefBagElement(capacity = 4)
        val loop = ConstructionReadingLoop(bot, cas, bag, ReteNetwork())
        assertEquals(0, botCalls)
        loop // keep explicit: only loop.read invokes bot.propose
    }
}
