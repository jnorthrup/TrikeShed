package borg.trikeshed.narsese

import borg.trikeshed.kif.KifKnowledgeBase
import borg.trikeshed.lcnc.LcncNode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * legal.ingest's grounding gate is the anti-hallucination invariant the
 * plan requires (Task 2 Stage 2): the LLM is asked nicely not to fabricate,
 * but only [LegalNodes.groundList] actually enforces it — same shape as
 * [ConstructionPatternGate], applied to legal spans instead of causal
 * constructions.
 */
class LegalNodesTest {

    private val source = "The Supreme Court held in Miranda v. Arizona that suspects must be informed of their rights."

    @Test
    fun groundedSpanIsAccepted() {
        val proposed = listOf(mapOf("text" to "suspects must be informed of their rights", "type" to "holding"))
        val (accepted, refused) = LegalNodes.groundList(proposed, source) { it["text"] as? String }
        assertEquals(1, accepted.size)
        assertTrue(refused.isEmpty())
    }

    @Test
    fun fabricatedSpanIsRefusedNotSilentlyDropped() {
        val proposed = listOf(
            mapOf("text" to "suspects must be informed of their rights", "type" to "holding"),
            mapOf("text" to "the court also ordered the defendant to pay damages", "type" to "holding"),
        )
        val (accepted, refused) = LegalNodes.groundList(proposed, source) { it["text"] as? String }
        assertEquals(1, accepted.size, "only the grounded holding survives")
        assertEquals(1, refused.size, "the fabricated holding is reported, not dropped silently")
        val refusal = refused.single()
        assertEquals("not present in source document", refusal["reason"])
    }

    @Test
    fun caseInsensitiveMatch() {
        // Same normalization ConstructionPatternGate.validateLine uses:
        // lowercase + trim on the candidate, collapsed whitespace on the
        // source only — a candidate's own internal spacing must match.
        val proposed = listOf(mapOf("case" to "MIRANDA v. Arizona"))
        val (accepted, _) = LegalNodes.groundList(proposed, source) { it["case"] as? String }
        assertEquals(1, accepted.size, "matching must be case-insensitive")
    }

    @Test
    fun blankOrMissingKeyIsRefused() {
        val proposed = listOf(mapOf("text" to ""), mapOf<String, Any?>())
        val (accepted, refused) = LegalNodes.groundList(proposed, source) { it["text"] as? String }
        assertTrue(accepted.isEmpty())
        assertEquals(2, refused.size)
    }

    @Test
    fun renderBriefReportsEmptyCategoriesHonestly() {
        val brief = LegalNodes.renderBrief(citations = emptyList(), holdings = emptyList(), parties = emptyList())
        assertTrue(brief.contains("(none found)"), "an empty extraction must say so, not fabricate content")
    }

    @Test
    fun renderBriefIncludesGroundedEntries() {
        val brief = LegalNodes.renderBrief(
            citations = listOf(mapOf("case" to "Miranda v. Arizona", "reporter" to "U.S.", "page" to "436")),
            holdings = listOf(mapOf("type" to "holding", "text" to "suspects must be informed of their rights")),
            parties = listOf(mapOf("name" to "Miranda", "role" to "defendant")),
        )
        assertTrue(brief.contains("Miranda v. Arizona"))
        assertTrue(brief.contains("suspects must be informed of their rights"))
        assertTrue(brief.contains("Miranda") && brief.contains("defendant"))
    }

    // ── emitKif: only grounded facts land in the bank, and they must
    // actually parse — a round-trip through the real KifKnowledgeBase,
    // not just a check of the string this class happens to build. ──

    @Test
    fun emittedKifRoundTripsThroughTheRealKnowledgeBase() {
        val kb = KifKnowledgeBase()
        LegalNodes.emitKif(
            documentCid = "abc123",
            citations = listOf(mapOf("case" to "Miranda v. Arizona", "reporter" to "U.S.", "page" to "436")),
            holdings = listOf(
                mapOf("type" to "holding", "text" to "suspects must be informed of their rights"),
                mapOf("type" to "standard", "text" to "beyond a reasonable doubt"),
            ),
            parties = listOf(mapOf("name" to "Miranda", "role" to "defendant")),
            kifSink = kb::assertKif,
        )
        // instance + cites + holding + standardOfProof + party = 5 asserts.
        assertEquals(5, kb.asserts().size)
        val kif = kb.toKifFile()
        assertTrue(kif.contains("(instance doc_abc123 LegalDocument)"))
        assertTrue(kif.contains("(cites doc_abc123 Miranda_v__Arizona)"))
        assertTrue(kif.contains("(holding doc_abc123 \"suspects must be informed of their rights\")"))
        assertTrue(kif.contains("(standardOfProof doc_abc123 \"beyond a reasonable doubt\")"))
        assertTrue(kif.contains("(party doc_abc123 Miranda defendant)"))
    }

    @Test
    fun emitKifSkipsBlankFieldsRatherThanAssertingGarbage() {
        val kb = KifKnowledgeBase()
        LegalNodes.emitKif(
            documentCid = "cid1",
            citations = listOf(mapOf("case" to ""), mapOf<String, Any?>()),
            holdings = listOf(mapOf("text" to " ")),
            parties = listOf(mapOf("name" to null)),
            kifSink = kb::assertKif,
        )
        // Only the document instance assertion survives — every span was blank.
        assertEquals(1, kb.asserts().size)
    }

    // ── legal.evidence: the evidence-bank read-back. §5 flagged that
    // nothing ever read the KIF landings back out; these prove a document's
    // previously-asserted facts actually come back through the shared bank
    // and land in the brief the next tribunal round argues over. ──

    @Test
    fun queryEvidenceRendersEveryPredicateShapeEmitKifMints() {
        val kb = KifKnowledgeBase()
        LegalNodes.emitKif(
            documentCid = "abc123",
            citations = listOf(mapOf("case" to "Miranda v. Arizona")),
            holdings = listOf(
                mapOf("type" to "holding", "text" to "suspects must be informed of their rights"),
                mapOf("type" to "element", "text" to "custodial interrogation"),
                mapOf("type" to "standard", "text" to "beyond a reasonable doubt"),
            ),
            parties = listOf(mapOf("name" to "Miranda", "role" to "defendant")),
            kifSink = kb::assertKif,
        )
        val evidence = LegalNodes.queryEvidence(kb, "doc_abc123")
        assertTrue(evidence.contains("cites: Miranda_v__Arizona"))
        assertTrue(evidence.contains("holding: suspects must be informed of their rights"))
        assertTrue(evidence.contains("element: custodial interrogation"))
        assertTrue(evidence.contains("standard of proof: beyond a reasonable doubt"))
        assertTrue(evidence.contains("party: Miranda (defendant)"))
    }

    @Test
    fun queryEvidenceIsEmptyForAnUnknownDocument() {
        val kb = KifKnowledgeBase()
        LegalNodes.emitKif("abc123", emptyList(), emptyList(), emptyList(), kb::assertKif)
        assertEquals("", LegalNodes.queryEvidence(kb, "doc_never-ingested"))
    }

    @Test
    fun evidenceRunnerFoldsPriorFactsIntoTheBriefForTheNextRound() = runBlocking {
        val kb = KifKnowledgeBase()
        LegalNodes.emitKif(
            documentCid = "abc123",
            citations = listOf(mapOf("case" to "Miranda v. Arizona")),
            holdings = emptyList(),
            parties = emptyList(),
            kifSink = kb::assertKif,
        )
        val runner = LegalNodes.evidenceRunner(kb)
        val node = LcncNode("n1b", "legal.evidence")
        val out = runner.run(node, mapOf("documentCid?" to "sha256:abc123", "brief?" to "Grounded citations:\n  - Miranda v. Arizona"))
        val brief = out["brief"] as String
        assertTrue(brief.startsWith("Grounded citations:\n  - Miranda v. Arizona"), "the original brief must survive, not be replaced")
        assertTrue(brief.contains("Evidence bank"), "the evidence-bank section must be appended")
        assertTrue(brief.contains("cites: Miranda_v__Arizona"), "the prior KIF assertion must actually be read back")
    }

    @Test
    fun evidenceRunnerPassesBriefThroughUnchangedWhenBankHasNoFactsYet() = runBlocking {
        val runner = LegalNodes.evidenceRunner(KifKnowledgeBase())
        val node = LcncNode("n1b", "legal.evidence")
        val out = runner.run(node, mapOf("documentCid?" to "sha256:neverseen", "brief?" to "just a brief"))
        assertEquals("just a brief", out["brief"], "no facts in the bank means no evidence section, not an error")
    }

    // ── eyecite: a real subprocess integration test, not a mock. Skips
    // (rather than failing) when `.venv` hasn't been provisioned on this
    // machine — `python3 -m venv .venv && .venv/bin/pip install eyecite`
    // from the repo root sets it up; see LegalNodes' class doc. ──

    @Test
    fun eyeciteSubprocessExtractsRealCitations() {
        val citations = LegalNodes.runEyecite(
            "The Court in Miranda v. Arizona, 384 U.S. 436 (1966), held that suspects must be informed of their rights, citing 42 U.S.C. sec 1983.",
        )
        assumeTrue(citations.isNotEmpty(), "eyecite .venv not provisioned (python3 -m venv .venv && .venv/bin/pip install eyecite)")
        val reporterCite = citations.first { (it["reporter"] as? String) == "U.S." }
        assertEquals("384 U.S. 436", reporterCite["text"])
        assertEquals("436", reporterCite["page"])
        assertTrue((reporterCite["case"] as? String)?.contains("Miranda") == true, "eyecite must resolve the case name from the parenthetical")
        assertTrue(citations.any { (it["reporter"] as? String) == "U.S.C." }, "the statutory citation must also be found")
    }
}
