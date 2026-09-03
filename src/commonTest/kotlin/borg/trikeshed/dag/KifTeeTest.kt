package borg.trikeshed.dag

import borg.trikeshed.cursor.BlackboardContext
import borg.trikeshed.kif.KifExpr
import borg.trikeshed.kif.KifKnowledgeBase
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The KIF bank is a projection of working memory: a fact asserted, modified
 * or retracted in the [ReteNetwork] shows up, changes, and disappears in the
 * bank through [KifTee], and telling the same fact twice grows nothing.
 * Daemon-free: `ReteNetwork()` + `KifKnowledgeBase()`.
 */
class KifTeeTest {

    private val panels = BlackboardContext(PlaneFacts.PANELS)
    private val cableId = FactId(PlaneFacts.PANELS, "demo/cable/0")

    private fun cable(type: String, toPort: String = "in") = linkedMapOf<String, Any?>(
        PlaneFacts.KIND to "cable",
        PlaneFacts.KEY to "demo",
        PlaneFacts.ACTOR to "lcnc",
        "fromNode" to "n1", "fromPort" to "out",
        "toNode" to "n2", "toPort" to toPort,
        "type" to type,
    )

    private fun rows(bank: KifKnowledgeBase, pattern: String, v: String) =
        bank.query(KifExpr.parse(pattern)).map { it.getValue(v) }

    private val iri = PlaneFacts.factIri(cableId).iri

    @Test
    fun assertedFactIsQueryableByKindOnTheBank() = runTest {
        val net = ReteNetwork()
        val bank = KifKnowledgeBase()
        val tee = KifTee(bank)
        tee.attach(net)

        val fields = cable("json")
        net.assert(cableId, fields, PlaneFacts.versionOf(fields), panels)

        assertEquals(listOf(iri), rows(bank, "(kind ?f cable)", "?f"))
        assertEquals(listOf(iri), rows(bank, "(type ?f json)", "?f"))
        assertEquals(listOf("demo"), rows(bank, "(key $iri ?k)", "?k"))
        assertEquals(PlaneFacts.toKif(net.snapshot().single()).size, bank.size(), "exactly the projection, nothing else")
        assertEquals(1, tee.trackedCount())
        assertEquals(0L, net.observerFailures)
    }

    @Test
    fun modifyReplacesTheOldProjectionWithTheNew() = runTest {
        val net = ReteNetwork()
        val bank = KifKnowledgeBase()
        KifTee(bank).attach(net)

        val v1 = cable("json")
        net.assert(cableId, v1, PlaneFacts.versionOf(v1), panels)
        val sizeAfterAssert = bank.size()

        val v2 = cable("List<TurnFact>", toPort = "facts")
        net.modify(cableId, v2, PlaneFacts.versionOf(v2))

        assertTrue(rows(bank, "(type ?f json)", "?f").isEmpty(), "old type tuple gone")
        assertEquals(listOf(iri), rows(bank, "(type ?f List<TurnFact>)", "?f"))
        assertEquals(listOf("facts"), rows(bank, "(toPort $iri ?p)", "?p"), "old toPort replaced, not accumulated")
        assertEquals(listOf(iri), rows(bank, "(kind ?f cable)", "?f"), "the unchanged kind tuple survives the swap once")
        assertEquals(sizeAfterAssert, bank.size(), "same shape, same tuple count")
    }

    @Test
    fun retractRemovesEveryTupleOfTheFactAndOnlyThose() = runTest {
        val net = ReteNetwork()
        val bank = KifKnowledgeBase()
        val tee = KifTee(bank)
        tee.attach(net)
        bank.assertKif("(subclass Dog Mammal)") // a tenant that is not ours, in the same bank

        val fields = cable("json")
        net.assert(cableId, fields, PlaneFacts.versionOf(fields), panels)
        val other = FactId(PlaneFacts.PANELS, "demo/cable/1")
        val otherFields = cable("json")
        net.assert(other, otherFields, PlaneFacts.versionOf(otherFields), panels)
        assertEquals(2, rows(bank, "(kind ?f cable)", "?f").size)

        net.retract(cableId)

        assertEquals(listOf(PlaneFacts.factIri(other).iri), rows(bank, "(kind ?f cable)", "?f"), "the sibling fact keeps its tuples")
        assertEquals(1 + PlaneFacts.toKif(net.snapshot().single()).size, bank.size())
        assertEquals(1, tee.trackedCount())
        // a subclass pattern answers once directly and once from the closure — pre-existing query behaviour, hence the set
        assertEquals(setOf("Mammal"), rows(bank, "(subclass Dog ?p)", "?p").toSet(), "foreign tuples untouched")

        net.retract(other)
        assertEquals(1, bank.size(), "only the foreign tuple remains")
        assertEquals(0, tee.trackedCount())
    }

    @Test
    fun identicalReassertGrowsNothing() = runTest {
        val net = ReteNetwork()
        val bank = KifKnowledgeBase()
        val tee = KifTee(bank)
        tee.attach(net)

        val fields = cable("json")
        val cid = PlaneFacts.versionOf(fields)
        net.assert(cableId, fields, cid, panels)
        val size = bank.size()
        val file = bank.toKifFile()

        net.assert(cableId, fields, cid, panels) // the network reports nothing
        tee.apply(ReteOp.ASSERT, net.snapshot().single()) // and a replay is skipped by the tee itself
        net.modify(cableId, fields, cid) // a modify to the same content is observed, still no growth

        assertEquals(size, bank.size())
        assertEquals(file, bank.toKifFile())
        assertEquals(1, tee.trackedCount())
    }

    @Test
    fun primeTellsFactsThatPredateTheTeeOnce() = runTest {
        val net = ReteNetwork()
        val fields = cable("json")
        net.assert(cableId, fields, PlaneFacts.versionOf(fields), panels)

        val bank = KifKnowledgeBase()
        val tee = KifTee(bank)
        tee.attach(net)
        assertEquals(0, bank.size(), "attach alone tells nothing")
        tee.prime(net)
        val size = bank.size()
        assertEquals(listOf(iri), rows(bank, "(kind ?f cable)", "?f"))
        tee.prime(net)
        assertEquals(size, bank.size(), "priming twice is idempotent")

        net.retract(cableId)
        assertEquals(0, bank.size(), "a primed fact is retractable like an observed one")
    }

    @Test
    fun detachStopsTheProjectionButKeepsWhatWasTold() = runTest {
        val net = ReteNetwork()
        val bank = KifKnowledgeBase()
        val (tee, disposer) = KifTee.attach(net, bank)

        val fields = cable("json")
        net.assert(cableId, fields, PlaneFacts.versionOf(fields), panels)
        val size = bank.size()
        disposer.close()

        net.retract(cableId)
        assertEquals(size, bank.size())
        assertEquals(1, tee.trackedCount())
    }
}
