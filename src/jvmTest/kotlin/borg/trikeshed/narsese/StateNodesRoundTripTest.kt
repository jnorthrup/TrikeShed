package borg.trikeshed.narsese

import borg.trikeshed.cursor.BudgetCoord
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.kif.KifKnowledgeBase
import borg.trikeshed.lcnc.LcncNode
import borg.trikeshed.lib.get
import borg.trikeshed.rdf.RdfGraph
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/** state.freeze → state.thaw is a real round trip: the snapshot repopulates a FRESH bag. */
class StateNodesRoundTripTest {

    private fun signal(angular: Long, positive: Long, relation: RelationKind) =
        SemanticSignal(
            angular = angular,
            evidence = EvidenceCoord(positive, 0L),
            relation = relation,
            subjectCid = ContentId.of("subject-$angular".encodeToByteArray()).value,
        )

    private suspend fun BeliefBagElement.settle() {
        // single-consumer intake: wait until the channel stays empty across polls
        var quiet = 0
        var spins = 0
        while (spins++ < 400 && quiet < 3) {
            delay(10)
            if (intake.isEmpty) quiet++ else quiet = 0
        }
        delay(25)
    }

    @Test
    fun freezeThenThawRepopulatesAFreshBag() = runBlocking {
        val cas = CasStore.inMemory()
        val kif = KifKnowledgeBase()
        kif.assertKif("(instance Freeze RoundTrip)")

        val source = BeliefBagElement(capacity = 16)
        source.open()
        source.intake.send(BeliefIntake.Mint(signal(11L, 3L, RelationKind.CAUSALITY), BudgetCoord(0.9f, 0.5f, 0.5f)))
        source.intake.send(BeliefIntake.Mint(signal(22L, 2L, RelationKind.MATCH), BudgetCoord(0.6f, 0.5f, 0.5f)))
        source.intake.send(BeliefIntake.Mint(signal(33L, 1L, RelationKind.GAP), BudgetCoord(0.3f, 0.5f, 0.5f)))
        source.settle()
        assertEquals(3, source.size)

        val frozen = StateNodes.freezeRunner(source, kif, { RdfGraph(emptyList()) }, cas)
            .run(LcncNode(id = "f", type = "state.freeze"), emptyMap())
        @Suppress("UNCHECKED_CAST")
        val receiptCid = (frozen["snapshot"] as Map<String, Any?>)["cid"] as String

        val fresh = BeliefBagElement(capacity = 16)
        fresh.open()
        val freshKif = KifKnowledgeBase()
        val thawed = StateNodes.thawRunner(fresh, cas, freshKif)
            .run(LcncNode(id = "t", type = "state.thaw", params = mapOf("cid" to receiptCid)), emptyMap())
        fresh.settle()

        @Suppress("UNCHECKED_CAST")
        val restored = thawed["restored"] as Map<String, Any?>
        assertEquals(3, restored["bagRestored"])
        assertEquals(1, restored["kifAssertionsRestored"])
        assertEquals(3, fresh.size, "thaw must repopulate the bag, not just report CIDs")

        val want = source.recallTop(1)[0]
        val got = fresh.recallTop(1)[0]
        assertEquals(want.a, got.a, "top recalled signal must round-trip")
        assertEquals(want.b.packed, got.b.packed, "budget must ride the snapshot, not be invented")

        source.drain()
        fresh.drain()
    }
}
