package borg.trikeshed.narsese

import borg.trikeshed.kif.KifKnowledgeBase
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The couch `kif-ledger/` plane looked like durability and was not: `CouchStoreFactory.casBacked`
 * builds a fresh in-memory head projection at every boot, so a document written before a restart
 * cannot be found after one. Measured live: teach took the plane 3 → 5 documents, the daemon
 * restarted, and the thaw read 3.
 *
 * These files are the plane that actually survives. What matters is that a line written before a
 * restart reconstructs the SAME knowledge after it — for rules that means the same `ruleCid`,
 * since identity is what dedup and firing attribution key on.
 */
class NarsDurableLedgerTest {

    private fun <T> withForge(block: (java.io.File) -> T): T {
        val home = Files.createTempDirectory("nars-ledger-").toFile()
        try { return block(home) } finally { home.deleteRecursively() }
    }

    @Test
    fun axiomsSurviveTheProcessTheyWereTaughtIn() = withForge { home ->
        val taught = listOf(
            "(instance impulse_adopt_wikiskill-page Agent)",
            """(=> (and (instance ?I Agent) (attribute ?I SUPPORTED)) (holds keep ?I))""",
            "(documentation Entity EnglishLanguage \"quotes, \\backslashes and\ttabs\")",
        )
        for (kif in taught) NarsDurableLedger.appendAxiom(home, kif)

        // A different process would read only the file — simulate exactly that.
        val readBack = NarsDurableLedger.readAxioms(home)
        assertEquals(taught, readBack, "a taught axiom did not round-trip through the ledger")

        // And the point of the round trip: a fresh bank restores from it.
        val restored = KifKnowledgeBase()
        for (kif in readBack) restored.assertKif(kif)
        assertEquals(taught.size, restored.asserts().size)
    }

    @Test
    fun rulesSurviveWithTheirIdentityIntact() = withForge { home ->
        val rules = listOf(
            EternalRule("page-cites-corpus-cid", "page-traceable", NalCopula.IMPLICATION, EvidenceCoord(Nal.UNIT, 0L)),
            EternalRule("patch-never-rollback", "history-append-only", NalCopula.EQUIVALENCE, EvidenceCoord(Nal.UNIT / 2, 0L)),
            EternalRule("carries-provenance", "admissible", NalCopula.IMPLICATION, EvidenceCoord(Nal.UNIT, 0L), provenanceCid = "sha256:abc"),
        )
        for (r in rules) NarsDurableLedger.appendRule(home, r)

        val readBack = NarsDurableLedger.readRules(home)
        assertEquals(rules.size, readBack.size)
        // Identity, not just presence: ruleCid hashes five fields, and a rule reconstructed with a
        // different cid is a different rule that merely looks admitted.
        assertEquals(rules.map { it.ruleCid.value }, readBack.map { it.ruleCid.value })
        assertEquals(rules, readBack)
    }

    @Test
    fun replayIsIdempotentAndACorruptLineCostsOnlyItself() = withForge { home ->
        val rule = EternalRule("a", "b", NalCopula.IMPLICATION, EvidenceCoord(Nal.UNIT, 0L))
        NarsDurableLedger.appendRule(home, rule)
        NarsDurableLedger.appendRule(home, rule)          // same rule taught twice
        NarsDurableLedger.appendAxiom(home, "(instance X Agent)")
        NarsDurableLedger.appendAxiom(home, "(instance X Agent)")

        assertEquals(1, NarsDurableLedger.readRules(home).size, "duplicate lines must collapse on read")
        assertEquals(1, NarsDurableLedger.readAxioms(home).size)

        // Append-only means a torn tail is one lost line, not a lost ledger.
        NarsDurableLedger.ruleFile(home).appendText("{\"antecedent\":\"truncated\n")
        NarsDurableLedger.axiomFile(home).appendText("not json at all\n")
        NarsDurableLedger.appendRule(home, EternalRule("c", "d", NalCopula.IMPLICATION, EvidenceCoord(Nal.UNIT, 0L)))
        NarsDurableLedger.appendAxiom(home, "(instance Y Agent)")

        val rules = NarsDurableLedger.readRules(home)
        val axioms = NarsDurableLedger.readAxioms(home)
        assertEquals(2, rules.size, "a corrupt line took healthy records with it: $rules")
        assertTrue(axioms.contains("(instance X Agent)") && axioms.contains("(instance Y Agent)"), "$axioms")
    }

    @Test
    fun anAbsentLedgerIsEmptyRatherThanAnError() = withForge { home ->
        assertEquals(emptyList(), NarsDurableLedger.readAxioms(home))
        assertEquals(emptyList(), NarsDurableLedger.readRules(home))
    }
}
