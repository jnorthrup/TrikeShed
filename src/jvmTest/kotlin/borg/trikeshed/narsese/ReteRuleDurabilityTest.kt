package borg.trikeshed.narsese

import borg.trikeshed.lcnc.LcncNode
import borg.trikeshed.lib.toSeries
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The daemon boots `causalityRete` over ZERO rules, so every rule ever admitted through
 * `nal.rule.admit` was gone at the next restart — VAL-NARS-TEACH-002 could not be satisfied for
 * the rules half at all. `ruleAdmitRunner` now tees each admitted rule and the daemon files it
 * under `rete-rule/<ruleCid>` in couch, which the boot thaw replays.
 *
 * The property that decides whether that is worth anything is IDENTITY: a rule reconstructed
 * from its stored fields must carry the SAME `ruleCid`. `ruleCid` hashes
 * `antecedent|consequent|copula|evidence.packed|provenanceCid`, so a field dropped or reformatted
 * in the round trip yields a DIFFERENT rule that still looks admitted — dedup breaks, the rete
 * accumulates near-duplicates, and firings attribute to a ruleCid no admit response ever named.
 */
class ReteRuleDurabilityTest {

    /** Exactly the daemon's `rete-rule/` document body. */
    private fun store(r: EternalRule): Map<String, String> = mapOf(
        "antecedent" to r.antecedent,
        "consequent" to r.consequent,
        "copula" to r.copula.name,
        "evidence" to r.evidence.packed.toString(),
        "provenanceCid" to (r.provenanceCid ?: ""),
    )

    /** Exactly the daemon's boot-thaw reconstruction. */
    private fun restore(doc: Map<String, String>): EternalRule = EternalRule(
        antecedent = doc.getValue("antecedent"),
        consequent = doc.getValue("consequent"),
        copula = NalCopula.valueOf(doc.getValue("copula")),
        evidence = EvidenceCoord(doc.getValue("evidence").toLong()),
        provenanceCid = doc["provenanceCid"]?.takeIf { it.isNotBlank() },
    )

    private fun admitThrough(ledger: MutableList<EternalRule>, rules: List<Map<String, Any>>): Map<*, *> = runBlocking {
        val bag = BeliefBagElement(capacity = 64)
        bag.open()
        val rete = CausalityReteElement(bag, emptyList<EternalRule>().toSeries())
        rete.open()
        val runner = RuleNodes.ruleAdmitRunner(rete, ledger = { ledger.add(it) })
        runner.run(LcncNode(id = "n", type = "nal.rule.admit"), mapOf("rules" to rules)) as Map<*, *>
    }

    @Test
    fun everyAdmittedRuleIsTeedForDurability() {
        val ledger = mutableListOf<EternalRule>()
        val out = admitThrough(
            ledger,
            listOf(
                mapOf("antecedent" to "curation-page-cites-corpus-cid", "consequent" to "curation-page-traceable", "copula" to "==>"),
                mapOf("antecedent" to "wiki-patch-never-rollback", "consequent" to "wiki-history-append-only", "copula" to "<=>", "discount" to "0.5"),
            ),
        )
        assertEquals(2, out["admitted"], "control: both rules entered the live rete")
        assertEquals(2, ledger.size, "an admitted rule that is never tee'd does not survive a restart")
        // The tee must carry the same identities the admit response advertised, or the durable
        // record describes different rules than the caller was told it admitted.
        assertEquals(
            (out["ruleCids"] as List<*>).toSet(),
            ledger.map { it.ruleCid.value }.toSet(),
        )
    }

    @Test
    fun aStoredRuleRestoresToTheSameRuleCid() {
        val ledger = mutableListOf<EternalRule>()
        admitThrough(
            ledger,
            listOf(
                mapOf("antecedent" to "a", "consequent" to "b", "copula" to "==>"),
                mapOf("antecedent" to "c", "consequent" to "d", "copula" to "<=>", "discount" to "0.25"),
            ),
        )
        assertTrue(ledger.isNotEmpty())
        for (original in ledger) {
            val round = restore(store(original))
            assertEquals(original.ruleCid.value, round.ruleCid.value, "identity lost in the round trip: $original")
            assertEquals(original, round, "a field was dropped or reformatted: $original -> $round")
        }
    }

    @Test
    fun replayingTheWholePlaneIntoAFreshReteIsIdempotent() = runBlocking {
        val ledger = mutableListOf<EternalRule>()
        admitThrough(
            ledger,
            listOf(
                mapOf("antecedent" to "x", "consequent" to "y", "copula" to "==>"),
                mapOf("antecedent" to "p", "consequent" to "q", "copula" to "==>"),
            ),
        )
        val docs = ledger.map { store(it) }

        // Boot: a fresh rete over zero rules, fed the whole stored plane.
        val bag = BeliefBagElement(capacity = 64)
        bag.open()
        val booted = CausalityReteElement(bag, emptyList<EternalRule>().toSeries())
        booted.open()
        assertEquals(
            docs.size, booted.admit(docs.map { restore(it) }.toSeries()),
            "the restarted rete did not recover every rule",
        )
        // A second replay (a re-boot, or a doc written twice) must add nothing — admit is a set
        // union on ruleCid, and that is what makes replaying the whole plane safe.
        assertEquals(
            0, booted.admit(docs.map { restore(it) }.toSeries()),
            "replaying the plane duplicated rules instead of unioning them",
        )
    }
}
