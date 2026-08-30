package borg.trikeshed.narsese

import borg.trikeshed.kif.KifKnowledgeBase
import borg.trikeshed.lib.toSeries
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Everything `/api/beliefs/teach` banked used to die at the next boot.
 *
 * The daemon's boot thaw restores the kifBank by re-asserting `kif-ledger/` couch docs
 * (`OroborosDaemon.kt`, "council thaw"), and council and legal.ingest both write that ledger —
 * but the curator did not. So the ONE surface for teaching curation knowledge was the one
 * surface whose knowledge was not durable, and VAL-NARS-TEACH-002 (N3: axioms failing to
 * survive is an unconditional FAIL) could not be satisfied at all.
 *
 * These tests pin the tee and, more importantly, the property that makes it worth anything:
 * the text handed to the ledger must be re-assertable, because that is exactly what boot does
 * with it. A ledger that records an unparseable rendering restores nothing.
 */
class CuratorLedgerDurabilityTest {

    private fun teachOnce(ledger: ((String) -> Unit)?): Pair<CuratorImpulseElement, List<String>> {
        val recorded = mutableListOf<String>()
        val bag = BeliefBagElement(capacity = 512)
        val curator = CuratorImpulseElement(
            bag,
            ledger = ledger ?: { recorded.add(it); Unit },
        )
        runBlocking {
            bag.open()
            curator.open()
            curator.teach(
                impulses = listOf(
                    CuratorImpulse(
                        kind = CuratorImpulseKind.ADOPT,
                        subject = "wikiskill-page-server-health-http-check",
                        rationale = "grounded in corpus cid sha256:4909f796",
                    ),
                ).toSeries(),
                scenarios = listOf(
                    ReplayScenario(
                        scenarioId = "s-durability",
                        impulseSubject = "wikiskill-page-server-health-http-check",
                        turns = listOf(
                            ReplayTurn("user", "start"),
                            ReplayTurn("curator", "adopted the page [pass]"),
                        ).toSeries(),
                    ),
                ).toSeries(),
            )
        }
        return curator to recorded
    }

    @Test
    fun teachingTeesEveryBankedAxiomToTheLedger() {
        val (curator, recorded) = teachOnce(null)
        assertTrue(recorded.isNotEmpty(), "teach banked axioms but tee'd none — they would not survive a boot")
        // The SUMO spine is bootstrapped in init on every boot and must NOT be tee'd, or the
        // ledger accumulates another copy of the ontology at every restart. Checked by identity,
        // not by size — the taught set can legitimately be larger than the spine.
        val spine = borg.trikeshed.kif.KifExpr
            .parseAll(borg.trikeshed.ontology.SumoOntology.emitUpperKif())
            .map { it.toKifString() }.toSet()
        val leaked = recorded.filter { it in spine }
        assertTrue(leaked.isEmpty(), "the SUMO spine leaked into the ledger: $leaked")
        assertTrue(
            recorded.any { it.contains("wikiskill-page-server-health-http-check") },
            "the taught subject is absent from the ledger: $recorded",
        )
        assertTrue(curator.knowledgeBank.asserts().size > recorded.size, "spine plus taught axioms are banked")
    }

    @Test
    fun everyLedgeredLineRestoresTheBankTheWayBootWillReplayIt() {
        val (curator, recorded) = teachOnce(null)

        // Exactly what OroborosDaemon's boot thaw does: a fresh bank, fed the ledger text.
        val restored = KifKnowledgeBase()
        for (line in recorded) restored.assertKif(line)

        assertEquals(
            recorded.size, restored.asserts().size,
            "a ledgered line failed to re-assert — boot would silently restore less than was taught",
        )

        // And the restored bank must answer the taught query, which is the property the
        // contract actually cares about: the axioms are queryable after a restart.
        val taughtVerdicts = curator.knowledgeBank.asserts()
            .map { it.toKifString() }
            .filter { it.contains("wikiskill-page-server-health-http-check") }
        assertTrue(taughtVerdicts.isNotEmpty())
        val restoredText = restored.asserts().map { it.toKifString() }.toSet()
        for (v in taughtVerdicts) {
            assertTrue(v in restoredText, "taught axiom lost across the ledger round-trip: $v")
        }
    }

    @Test
    fun aFailingLedgerNeverCostsTheLiveBankItsAxioms() {
        val (curator, _) = teachOnce { error("ledger sink is down") }
        assertTrue(
            curator.knowledgeBank.asserts().any { it.toKifString().contains("wikiskill-page-server-health-http-check") },
            "a broken durability tee must degrade durability, never the live bank",
        )
    }
}
