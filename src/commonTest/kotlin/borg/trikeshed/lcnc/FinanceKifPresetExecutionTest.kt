package borg.trikeshed.lcnc

import borg.trikeshed.lib.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class FinanceKifPresetExecutionTest {
    // Use the production display sink; any accidental model dependency must fail.
    private fun displayRegistry(): Map<String, LcncNodeRunner> =
        TribunalNodes.registry(TribunalDialog { _, _, _ -> error("deterministic preset called a model") })
            .filterKeys { it == "display" }

    @Test fun financePresetReadsCurrentDocumentsAndConservesExactReviewTotals(): Unit = runTest {
        val corpus = InMemoryProjectCorpus()
        val header = "id,account,reference,currency,date,amount\n"
        val bank = "b1,operating,r1,USD,2026-11-02,0.10\nb2,operating,r2,USD,2026-11-02,0.20\nb3,operating,r3,USD,2026-11-02,0.30"
        val expenditure = "e1,operating,r1,USD,2026-11-02,0.10\ne2,operating,r2,USD,2026-11-02,0.20"
        corpus.put("pilot", "bank.csv", (header + bank).encodeToByteArray(), "text/csv")
        corpus.put("pilot", "expenditure.csv", (header + expenditure).encodeToByteArray(), "text/csv")
        val original = LcncProgramConfix.fromJson("preset-finance-reconcile", LcncPresets.all().getValue("preset-finance-reconcile"))
        val program = original.copy(nodes = original.nodes α { node ->
            if (node.type == ProjectNodes.READ) node.copy(params = node.params + _m["project" j "pilot"]) else node
        })
        val runner = LcncRunner(PureNodes.registry { 0L } + ProjectNodes.registry(corpus) + FinanceNodes.registry(corpus) + displayRegistry())
        val firstRun = runner.runAll(program)
        val first = assertIs<Map<*, *>>(firstRun.getValue("compare")["report"])
        assertEquals(first, firstRun.getValue("result")["x"])
        assertEquals("as-of-read-candidates-only", first["status"])
        val totals = assertIs<Map<*, *>>(first["bankTotals"])
        assertEquals("60", totals["inputMinorUnits"])
        assertEquals("30", totals["matchedMinorUnits"])
        assertEquals("30", totals["reviewMinorUnits"])
        assertEquals(true, totals["conserved"])
        val source = assertIs<Map<*, *>>(first["bankSource"])
        assertEquals("bank.csv", source["id"])
        corpus.put("pilot", "bank.csv", (header + bank.replace("0.10", "0.11")).encodeToByteArray(), "text/csv")
        val second = assertIs<Map<*, *>>(runner.runAll(program).getValue("compare")["report"])
        assertNotEquals(source["cid"], assertIs<Map<*, *>>(second["bankSource"])["cid"])
        assertEquals("20", assertIs<Map<*, *>>(second["bankTotals"])["matchedMinorUnits"])
    }

    @Test fun formulaPresetRunsTheSharedInspectionRunnerWithoutAnAssertionService(): Unit = runTest {
        val program = LcncProgramConfix.fromJson("preset-kif-structure", LcncPresets.all().getValue("preset-kif-structure"))
        val result = LcncRunner(PureNodes.registry { 0L } + displayRegistry()).runAll(program)
        val output = result.getValue("inspect")
        assertEquals(output["formula"], result.getValue("result")["x"])
        assertEquals("syntax-only-unreviewed", output["status"])
        val formula = assertIs<Map<*, *>>(output["formula"])
        assertEquals("forall", formula["kind"])
        assertEquals("implies", assertIs<Map<*, *>>(formula["body"])["kind"])
        assertEquals(emptyList<String>(), output["freeVariables"])
        assertTrue(assertIs<String>(output["sourceCid"]).startsWith("sha256:"))
    }
}
