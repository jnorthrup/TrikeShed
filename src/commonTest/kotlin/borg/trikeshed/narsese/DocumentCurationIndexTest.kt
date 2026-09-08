package borg.trikeshed.narsese

import borg.trikeshed.cursor.IOMemento
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import borg.trikeshed.modelmux.ModelResponse
import borg.trikeshed.modelmux.ModelUsage
import borg.trikeshed.nlp.NlpDependency
import borg.trikeshed.nlp.NlpDocument
import borg.trikeshed.nlp.NlpSentence
import borg.trikeshed.nlp.NlpToken
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DocumentCurationIndexTest {
    /** Hand-authored projection fixture, not a claimed CoreNLP execution. */
    private val text = "If Alice said Bob may not leave, Eve waits."
    private val source = DocumentSource(ContentId.of("original bytes".encodeToByteArray()),
        ContentId.of(text.encodeToByteArray()), text, "projection-fixture.txt", "text/plain", "projection",
        mapOf("fixture" to listOf("hand-authored")))
    private val words = s_["If", "Alice", "said", "Bob", "may", "not", "leave", ",", "Eve", "waits", "."]
    private val tags = s_["IN", "NNP", "VBD", "NNP", "MD", "RB", "VB", ",", "NNP", "VBZ", "."]
    private val tokens: Series<NlpToken> = words.size j { i: Int ->
        val word = words[i]
        val begin = text.indexOf(word)
        NlpToken(i + 1, begin, begin + word.length, word, word.lowercase(), tags[i],
            if (word in s_["Alice", "Bob", "Eve"]) "PERSON" else "O")
    }
    private val dependencies = s_[
        NlpDependency(3, 1, "mark"), NlpDependency(3, 2, "nsubj"), NlpDependency(10, 3, "advcl"),
        NlpDependency(7, 4, "nsubj"), NlpDependency(7, 5, "aux"), NlpDependency(7, 6, "neg"),
        NlpDependency(3, 7, "ccomp"), NlpDependency(3, 8, "punct"), NlpDependency(10, 9, "nsubj"),
        NlpDependency(0, 10, "root"), NlpDependency(10, 11, "punct"),
    ]
    private val sentence = NlpSentence(0, 0, text.length, tokens, dependencies)
    private val nlp = NlpDocument(text, s_[sentence])

    @Test
    fun projectionDefersTokenReadsAndRetainsUtf16Coordinates() {
        var reads = 0
        val counted = tokens.size j { i: Int -> reads++; tokens[i] }
        val document = nlp.copy(sentences = s_[sentence.copy(tokens = counted)])
        val index = source.curationIndex(document)
        assertSame(source, index.facet(DocumentCurationIndexK.Source))
        assertSame(document, index.facet(DocumentCurationIndexK.Nlp))
        val spans = index.facet(DocumentCurationIndexK.TokenSpans)(0)
        index.facet(DocumentCurationIndexK.SentenceCursor)
        assertEquals(0, reads)
        val span = spans[5]
        assertEquals("not", source.text.substring(span.a, span.b))
        assertEquals(1, reads)
        assertEquals(DocumentNlpStatus.AVAILABLE, index.facet(DocumentCurationIndexK.NlpStatus))
        val validatedReads = reads
        assertEquals(DocumentNlpStatus.AVAILABLE, index.facet(DocumentCurationIndexK.NlpStatus))
        assertEquals(0, index.facet(DocumentCurationIndexK.Reasons).size)
        assertEquals(validatedReads, reads, "Validation must reuse its result")

        val unicodeText = "\uD83D\uDE80 runs."
        val unicodeSource = source.copy(text = unicodeText,
            extractedTextCid = ContentId.of(unicodeText.encodeToByteArray()))
        val unicodeToken = NlpToken(1, 0, 2, "\uD83D\uDE80", "\uD83D\uDE80", "NN", "O")
        val unicode = NlpDocument(unicodeSource.text, s_[NlpSentence(0, 0, unicodeSource.text.length,
            s_[unicodeToken], s_[NlpDependency(0, 1, "root")])])
        val unicodeSpan = unicodeSource.curationIndex(unicode).facet(DocumentCurationIndexK.TokenSpans)(0)[0]
        assertEquals(2, unicodeSpan.b, "Offsets must not become code-point or byte indices")
        assertEquals("\uD83D\uDE80", unicodeSource.text.substring(unicodeSpan.a, unicodeSpan.b))
    }

    @Test
    fun cursorMetadataAndDependencyReferencesRetainSourceIdentity() {
        val index = source.curationIndex(nlp)
        val token = index.facet(DocumentCurationIndexK.TokenCursor)(0)[5]
        assertEquals(source.originalCid.value, token.cell("originalCid"))
        assertEquals(source.extractedTextCid.value, token.cell("extractedTextCid"))
        assertEquals(0, token.cell("sentence"))
        assertEquals(6, token.cell("index"))
        assertEquals("not", token.cell("word"))
        assertEquals("not", source.text.substring(token.cell("begin") as Int, token.cell("end") as Int))
        assertEquals(IOMemento.IoInt, token.view.first { it.b().name == "begin" }.b().type)
        val edge = index.facet(DocumentCurationIndexK.DependencyCursor)(0)[5]
        assertEquals("neg", edge.cell("relation"))
        assertEquals(7, edge.cell("governor"))
        assertEquals(token.cell("index"), edge.cell("dependent"))
        assertEquals(source.extractedTextCid.value, edge.cell("extractedTextCid"))
        assertEquals("PERSON", index.facet(DocumentCurationIndexK.Ner)(0)[1])
        val root = index.facet(DocumentCurationIndexK.Dependencies)(0)[9]
        assertEquals(0, root.governor, "Parser root is retained, not made into a token")
    }

    @Test
    fun qualificationEvidenceRemainsSeparateFromModelConfidenceAndVerification() {
        val proposal = DocumentProposal("fixture proposal", subject = "Bob", predicate = "leave",
            confidence = 0.99, polarity = false, modality = "may", reasons = s_["conditional attribution unresolved"])
        val model = ModelResponse("fixture model response", ModelUsage(1, 2, 3), "fixture", "fixture-model")
        val record = DocumentCurationRecord(source, nlp, model, "fixture-model", s_[proposal], emptySeriesOf())
        val index = record.curationIndex()
        assertSame(proposal, index.facet(DocumentCurationIndexK.Proposals)[0])
        assertSame(model, index.facet(DocumentCurationIndexK.Model))
        assertEquals("fixture-model", index.facet(DocumentCurationIndexK.ModelId))
        val relations = index.facet(DocumentCurationIndexK.Dependencies)(0) α { it.relation }
        for (relation in s_["mark", "advcl", "ccomp", "aux", "neg"].view) assertTrue(relation in relations)
        assertNull(index.facet(DocumentCurationIndexK.ParseConfidence))
        assertNull(index.facet(DocumentCurationIndexK.ExpressedCertainty))
        assertEquals(DocumentVerificationStatus.UNAVAILABLE, index.facet(DocumentCurationIndexK.ExternalVerification))
    }

    @Test
    fun missingAndEmptyAnalysisAreExplicit() {
        val missing = source.curationIndex(null, s_["nlp: managed runtime failed"])
        assertEquals(DocumentNlpStatus.UNAVAILABLE, missing.facet(DocumentCurationIndexK.NlpStatus))
        assertEquals(0, missing.facet(DocumentCurationIndexK.SentenceCursor).size)
        assertTrue("nlp: managed runtime failed" in missing.facet(DocumentCurationIndexK.Reasons))
        assertTrue("nlp unavailable" in missing.facet(DocumentCurationIndexK.Reasons))
        assertNull(missing.facet(DocumentCurationIndexK.Model))
        assertEquals(0, missing.facet(DocumentCurationIndexK.Proposals).size)
        val empty = source.curationIndex(NlpDocument(text, emptySeriesOf()))
        assertEquals(DocumentNlpStatus.INVALID, empty.facet(DocumentCurationIndexK.NlpStatus))
        assertTrue("nlp sentences unavailable for nonblank text" in empty.facet(DocumentCurationIndexK.Reasons))
        val noDependencies = source.curationIndex(nlp.copy(sentences = s_[sentence.copy(dependencies = emptySeriesOf())]))
        assertEquals(DocumentNlpStatus.INVALID, noDependencies.facet(DocumentCurationIndexK.NlpStatus))
        assertTrue("nlp sentence 0 dependencies unavailable" in noDependencies.facet(DocumentCurationIndexK.Reasons))
    }

    @Test
    fun invalidTextSpansAndDependencyEndpointsRemainInspectableWithoutNormalization() {
        val invalidToken = tokens[0].copy(begin = -1)
        val invalid = NlpDocument("different text", s_[sentence.copy(tokens = s_[invalidToken],
            dependencies = s_[NlpDependency(7, 999, "neg")])])
        val index = source.curationIndex(invalid)
        assertEquals(DocumentNlpStatus.INVALID, index.facet(DocumentCurationIndexK.NlpStatus))
        assertSame(invalid, index.facet(DocumentCurationIndexK.Nlp))
        assertEquals(-1, index.facet(DocumentCurationIndexK.TokenSpans)(0)[0].a)
        val reasons = index.facet(DocumentCurationIndexK.Reasons)
        assertTrue("nlp text differs from extracted text" in reasons)
        assertTrue(reasons.view.any { "token 1 span invalid" in it })
        assertTrue(reasons.view.any { "dependency endpoint invalid: 7->999" in it })
        val overlap = source.curationIndex(nlp.copy(sentences = s_[sentence,
            sentence.copy(index = 1)]))
        assertEquals(DocumentNlpStatus.INVALID, overlap.facet(DocumentCurationIndexK.NlpStatus))
        assertTrue("nlp sentence 1 span overlaps preceding sentence" in overlap.facet(DocumentCurationIndexK.Reasons))
    }

    private fun RowVec.cell(name: String): Any? = view.first { it.b().name == name }.a
}
