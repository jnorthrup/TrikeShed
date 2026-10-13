package borg.trikeshed.narsese

import borg.trikeshed.lib.*
import kotlin.test.*

class BertSenseIndexTest {
    private fun vector(lemma: String = "bank", values: FloatArray? = floatArrayOf(1f, 0f)) =
        BertVector(BertToken(lemma, "The bank holds the account."), if (values == null) BertSpan.Missing else BertSpan.Exact, values)
    private fun rows(n: Int = 7, sense: (Int) -> String = { "Organization" }) = n j { i: Int ->
        BertSenseEvidence("source-$i", sense(i), vector().copy(token = BertToken("bank", "Training sentence $i")))
    }

    @Test fun sevenAgreeingSourcesRetainTheirProvenance() {
        val result = BertSenseIndex("bert-test", rows()).sense(vector(), "query", s_["Organization", "LandArea"])
        assertEquals("Organization", result.sense)
        assertEquals(BertSenseStatus.Supported, result.status)
        assertEquals(7, result.neighbors.size)
        assertEquals((0..6).map { "source-$it" }, result.neighbors.view.map { it.evidence.source })
    }
    @Test fun queryCannotCountItsOwnLabelOrAnotherLemma() {
        val index = BertSenseIndex("bert-test", rows())
        assertEquals(BertSenseStatus.InsufficientEvidence, index.sense(vector(), "source-0", s_["Organization"]).status)
        assertEquals(BertSenseStatus.InsufficientEvidence, index.sense(vector("river"), "query", s_["Organization"]).status)
    }
    @Test fun minorityAndOutOfMenuEvidenceAreNotHiddenToManufactureAgreement() {
        val disagreement = BertSenseIndex("bert-test", rows(7) { if (it == 6) "LandArea" else "Organization" })
        assertEquals(BertSenseStatus.Disagreement, disagreement.sense(vector(), "query", s_["Organization"]).status)
        assertEquals(BertSenseStatus.OutsideMenu, BertSenseIndex("bert-test", rows())
            .sense(vector(), "query", s_["LandArea"]).status)
    }
    @Test fun missingVectorAndDuplicateSourcesCannotBecomeIndependentVotes() {
        assertEquals(BertSenseStatus.NoVector, BertSenseIndex("bert-test", rows())
            .sense(vector(values = null), "query", s_["Organization"]).status)
        assertFailsWith<IllegalArgumentException> { BertSenseIndex("bert-test", 7 j { rows()[0] }) }
    }
    @Test fun cosineSelectsNearestSevenRatherThanFirstSeven() {
        val far = BertSenseEvidence("far", "LandArea", vector(values = floatArrayOf(-1f, 0f))
            .copy(token = BertToken("bank", "The river bank is steep.")))
        val candidates = 8 j { i: Int -> if (i == 0) far else rows()[i - 1] }
        val result = BertSenseIndex("bert-test", candidates).sense(vector(), "query", s_["Organization", "LandArea"])
        assertEquals(BertSenseStatus.Supported, result.status)
        assertTrue(result.neighbors.view.none { it.evidence.source == "far" })
    }
    @Test fun identicalContextCannotVoteForItselfUnderAnotherSourceId() {
        val candidates = 7 j { i: Int -> if (i == 0) BertSenseEvidence("other-id", "Organization", vector()) else rows()[i] }
        assertEquals(BertSenseStatus.InsufficientEvidence, BertSenseIndex("bert-test", candidates)
            .sense(vector(), "query", s_["Organization"]).status)
    }
}
