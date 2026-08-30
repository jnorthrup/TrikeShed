package borg.trikeshed.narsese

import borg.trikeshed.kif.KifKnowledgeBase
import borg.trikeshed.lcnc.LcncNode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `legal.evidence` corpus mode + ruling-predicate read-back — the READER
 * for the `(ruling <case> <doc> <verdictCid>)` facts `council.record`
 * asserts. Reader lands before the writer so the assertion is admissible:
 * facts nothing reads back are the defect class the original audit flagged.
 *
 * All in-memory [KifKnowledgeBase], zero model calls — [LegalNodes.evidenceRunner]
 * needs no BrainClient.
 */
class LegalEvidenceCorpusTest {

    /** The design brief's seed corpus: two documents, one prior ruling on doc_A. */
    private fun seededBank(): KifKnowledgeBase = KifKnowledgeBase().apply {
        assertKif("(instance doc_A LegalDocument)")
        assertKif("(cites doc_A Smith_v_Jones)")
        assertKif("(holding doc_A \"holds X\")")
        assertKif("(cites doc_B Roe_v_Doe)")
        assertKif("(ruling case_7 doc_A abc123)")
    }

    private fun node(params: Map<String, String> = emptyMap()) =
        LcncNode("n-ev", "legal.evidence", params = params)

    @Test
    fun docScopeStaysDocScopedAndReadsThePriorRulingBack() = runBlocking {
        val runner = LegalNodes.evidenceRunner(seededBank())
        val out = runner.run(node(), mapOf("documentCid?" to "sha256:A", "brief?" to "the brief"))
        val brief = out["brief"] as String
        assertTrue(brief.startsWith("the brief"), "the original brief must survive, not be replaced")
        assertTrue(brief.contains("- cites: Smith_v_Jones"), "doc_A's own facts must render")
        assertTrue(brief.contains("- prior ruling: case_7 -> abc123"), "the (ruling ...) fact must be read back in doc scope")
        assertFalse(brief.contains("Roe_v_Doe"), "doc scope must exclude other documents' facts")
        assertFalse(brief.contains("Corpus evidence"), "doc scope must not render the corpus section")
    }

    @Test
    fun docScopeOnARulingFreeDocumentIsByteEqualToTheLegacyRendering() = runBlocking {
        val runner = LegalNodes.evidenceRunner(seededBank())
        val out = runner.run(node(), mapOf("documentCid?" to "sha256:B", "brief?" to "the brief"))
        // The exact bytes the pre-change evidenceRunner produced for doc_B —
        // legacy parity pin: absent ruling facts, nothing about the doc-scope
        // rendering may drift (LegalTribunalExecutionTest depends on it).
        assertEquals(
            "the brief\n\nEvidence bank (prior KIF facts for doc_B):\n- cites: Roe_v_Doe",
            out["brief"],
        )
    }

    @Test
    fun corpusScopeAttributesEveryDocumentAndIncludesTheRuling() = runBlocking {
        val runner = LegalNodes.evidenceRunner(seededBank())
        val out = runner.run(
            node(params = mapOf("scope" to "corpus")),
            mapOf("documentCid?" to "sha256:A", "brief?" to "the brief"),
        )
        val brief = out["brief"] as String
        assertTrue(brief.contains("Evidence bank (prior KIF facts for doc_A):"), "doc-scoped section still leads when a documentCid is present")
        assertTrue(brief.contains("Corpus evidence (all documents):"))
        assertTrue(brief.contains("- [doc_A] cites: Smith_v_Jones"))
        assertTrue(brief.contains("- [doc_A] holding: holds X"))
        assertTrue(brief.contains("- [doc_B] cites: Roe_v_Doe"), "corpus scope must surface OTHER documents' facts, attributed")
        assertTrue(brief.contains("- [doc_A] ruling: case_7 -> abc123"), "the ruling fact must appear in the corpus section")
    }

    @Test
    fun maxFactsCapsTheCorpusLoudly() {
        // Seed yields exactly 4 corpus fact lines (2 cites, 1 holding, 1 ruling).
        val corpus = LegalNodes.queryCorpus(seededBank(), maxFacts = 2)
        val lines = corpus.lines()
        assertEquals(3, lines.size, "2 fact lines + 1 truncation marker")
        assertTrue(lines[0].startsWith("- ["))
        assertTrue(lines[1].startsWith("- ["))
        assertEquals("... 2 more facts truncated (maxFacts=2)", lines[2], "the marker must name the remainder, never a silent cut")
    }

    @Test
    fun maxFactsParamIsHonoredThroughTheRunner() = runBlocking {
        val runner = LegalNodes.evidenceRunner(seededBank())
        val out = runner.run(
            node(params = mapOf("scope" to "corpus", "maxFacts" to "2")),
            mapOf("brief?" to "the brief"),
        )
        val brief = out["brief"] as String
        assertTrue(brief.contains("... 2 more facts truncated (maxFacts=2)"))
    }

    @Test
    fun wiredScopeInputOverridesTheParam() = runBlocking {
        val runner = LegalNodes.evidenceRunner(seededBank())
        val out = runner.run(
            node(params = mapOf("scope" to "doc")),
            mapOf("scope?" to "corpus", "brief?" to "the brief"),
        )
        assertTrue((out["brief"] as String).contains("Corpus evidence (all documents):"), "inputs-over-params, like every other port")
    }

    @Test
    fun emptyBankPassesTheBriefThroughUnmodified() = runBlocking {
        val runner = LegalNodes.evidenceRunner(KifKnowledgeBase())
        val out = runner.run(
            node(params = mapOf("scope" to "corpus")),
            mapOf("documentCid?" to "sha256:neverseen", "brief?" to "just a brief"),
        )
        assertEquals("just a brief", out["brief"], "no facts in the bank means no evidence sections, not an error")
    }
}
