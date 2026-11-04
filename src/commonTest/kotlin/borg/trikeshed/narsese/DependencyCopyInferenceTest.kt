package borg.trikeshed.narsese

import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.job.CanonicalCbor
import borg.trikeshed.lib.*
import borg.trikeshed.nlp.*
import kotlin.test.*

/** Copy vertices remain observable; these index-only recognizers do not establish their interpretation. */
class DependencyCopyInferenceTest {
    private fun source(text: String): DocumentSource {
        val cid = ContentId.of(text.encodeToByteArray())
        return DocumentSource(cid, cid, text, "copy-fixture.txt", "text/plain", "copy-fixture")
    }
    private fun sentence(text: String, index: Int = 0, offset: Int = 0): NlpSentence {
        val tokens = Regex("[A-Za-z]+|[^\\sA-Za-z]").findAll(text).mapIndexed { ordinal, match ->
            val word = match.value; val lower = word.lowercase()
            val lemma = when (lower) { "causes" -> "cause"; "is" -> "be"; else -> lower }
            val tag = when (lower) {
                "causes", "is" -> "VBZ"; "pay" -> "VB"; "must" -> "MD"; "due" -> "JJ"; "." -> "."; else -> "NN"
            }
            NlpToken(ordinal + 1, offset + match.range.first, offset + match.range.last + 1, word, lemma, tag, "O")
        }.toList().toSeries()
        fun token(word: String) = tokens.view.single { it.word.equals(word, true) }.index
        val dependencies = when {
            "causes" in text -> s_[NlpDependency(0, 2, "root"), NlpDependency(2, 1, "nsubj"),
                NlpDependency(2, 3, "obj"), NlpDependency(2, 4, "punct")]
            "due" in text -> s_[NlpDependency(0, 3, "root"), NlpDependency(3, 1, "nsubj"),
                NlpDependency(3, 2, "cop"), NlpDependency(3, 4, "punct")]
            else -> s_[NlpDependency(0, token("pay"), "root"), NlpDependency(token("pay"), token("Tenant"), "nsubj"),
                NlpDependency(token("pay"), token("must"), "aux"), NlpDependency(token("pay"), token("rent"), "obj"),
                NlpDependency(token("pay"), token("."), "punct")]
        }
        return NlpSentence(index, offset, offset + text.length, tokens, dependencies)
    }
    private fun copied(sentence: NlpSentence): NlpSentence {
        val edge = sentence.dependencies.view.single { it.relation == "nsubj" }
        val extra = s_[edge.copy(dependentCopy = 1), NlpDependency(edge.dependent, edge.dependent, "ref", governorCopy = 1)]
        return sentence.copy(dependencies = (sentence.dependencies.size + extra.size) j { ordinal: Int ->
            if (ordinal < sentence.dependencies.size) sentence.dependencies[ordinal] else extra[ordinal - sentence.dependencies.size]
        })
    }
    private fun record(source: DocumentSource, parsed: NlpDocument, axioms: Series<NlpcoreAxiom>? = null) =
        DocumentCurationRecord(source, parsed, null, "fixture", emptySeriesOf(), emptySeriesOf(), axioms = axioms)

    @Test fun copiedNormSentenceCannotFallBackToGenericOpenIeOrKbp() {
        val text = "Tenant must pay rent."
        val plain = NlpDocument(text, s_[sentence(text)])
        assertEquals(1, NormClauses.extract(plain).size)
        val triples = _l[NlpRelation(1..1, 3..3, 4..4, "Tenant", "pay", "rent", 1.0)]
        val copied = NlpDocument(text, s_[copied(plain.sentences[0]).copy(relations = triples, kbp = triples)])
        assertEquals(NormConditionIssue.COPIED_NODE, NormClauses.conditionIssue(copied, copied.sentences[0]))
        assertTrue(NormClauses.extract(copied).isEmpty())
        assertEquals(text, copied.text)
    }

    @Test fun copiedPositivePremisesAreUnknownWhileRawEvidenceStaysAvailable() {
        val text = "Rent is due."
        val original = source(text)
        val plain = NlpDocument(text, s_[sentence(text)])
        assertEquals(_s["rent be due"], NormClauses.facts(plain))
        assertEquals(_s["rent be due"], NormClauses.applicabilityFacts(plain))
        val copied = NlpDocument(text, s_[copied(plain.sentences[0])])
        assertTrue(NormClauses.facts(copied).isEmpty())
        assertTrue(NormClauses.applicabilityFacts(copied).isEmpty())
        val index = original.curationIndex(copied)
        assertEquals(DocumentNlpStatus.AVAILABLE, index.facet(DocumentCurationIndexK.NlpStatus))
        val observed = index.facet(DocumentCurationIndexK.Dependencies)(0)
        assertEquals(copied.sentences[0].dependencies.size, observed.size)
        assertEquals(1, observed[observed.size - 1].governorCopy)
        val columns = index.facet(DocumentCurationIndexK.DependencyColumns)
        assertEquals("governorCopy", columns[6].name.toString())
        assertEquals("dependentCopy", columns[7].name.toString())
        val cursor = index.facet(DocumentCurationIndexK.DependencyCursor)(0)
        assertEquals(1, cursor.b(cursor.size - 1).b(6).a)
        assertEquals(0, cursor.b(cursor.size - 1).b(7).a)
        val inspection = EnglishInspection.inspect(original, copied, 0, text.length)
        assertEquals("unreviewed", inspection["reviewStatus"])
        assertEquals(5, (inspection["counts"] as Map<*, *>)["nodes"])
    }

    @Test fun copiedAxiomAndPersistedReplayCannotProduceRuleCandidates() {
        val text = "Rain causes floods."
        val original = source(text)
        val plain = NlpDocument(text, s_[sentence(text)])
        val candidate = NlpcoreAxiomatics.recognize(plain, original.originalCid.value)
        assertEquals(1, candidate.size)
        val copied = NlpDocument(text, s_[copied(plain.sentences[0])])
        assertEquals(0, NlpcoreAxiomatics.recognize(copied, original.originalCid.value).size)
        val retained = record(original, copied, candidate)
        assertEquals(DocumentNlpStatus.AVAILABLE, retained.curationIndex().facet(DocumentCurationIndexK.NlpStatus))
        assertEquals(0, retained.nlpAxioms.size)
        assertEquals(0, DocumentCurationFacts.ruleCandidates(retained).size)
        assertEquals(0, DocumentCurationFacts.ruleFacts(ContentId.of("receipt".encodeToByteArray()), retained).size)
        assertEquals(1, record(original, plain, candidate).nlpAxioms.size)
    }

    @Test fun unrelatedNoCopySentenceRetainsItsCandidateOnLiveAndPersistedPaths() {
        val first = "Rain causes floods."; val second = "Heat causes drought."
        val text = "$first $second"; val original = source(text)
        val firstSentence = sentence(first)
        val secondSentence = sentence(second, 1, first.length + 1)
        val plain = NlpDocument(text, s_[firstSentence, secondSentence])
        val retained = NlpcoreAxiomatics.recognize(plain, original.originalCid.value)
        assertEquals(2, retained.size)
        val changed = plain.copy(sentences = s_[copied(firstSentence), secondSentence])
        assertEquals(1, NlpcoreAxiomatics.recognize(changed).size)
        assertEquals(1, NlpcoreAxiomatics.recognize(changed)[0].sentenceIndex)
        val replay = record(original, changed, retained).nlpAxioms
        assertEquals(1, replay.size); assertEquals(1, replay[0].sentenceIndex)
        assertEquals(retained[1], replay[0])
    }

    @Test fun modelSemanticGroundingRefusesCopiesButIndependentQuotationRemains() {
        val text = "Rain causes floods."; val original = source(text)
        val plain = NlpDocument(text, s_[sentence(text)])
        val proposal = DocumentProposal(raw = "fixture", subject = "Rain", predicate = "cause", obj = "floods", quote = text,
            begin = 0, end = text.length, polarity = true, modality = "asserted")
        assertEquals(0, DocumentCuratorGrounding.reconcile(original, plain, s_[proposal])[0].reasons.size)
        val copied = plain.copy(sentences = s_[copied(plain.sentences[0])])
        val checked = DocumentCuratorGrounding.reconcile(original, copied, s_[proposal])[0]
        assertTrue(checked.reasons.view.any { "copy-node" in it })
        val quote = DocumentCuratorGrounding.quotation(original, checked)
        assertEquals(0, quote.quotationBegin); assertEquals(text.length, quote.quotationEnd)
        val cas = CasStore.inMemory()
        val quoteCid = cas.put(DocumentCuratorGrounding.quotationReceipt(original, quote))
        val attributed = DocumentCuratorGrounding.quotationAttribution(original, quote.copy(quotationReceiptCid = quoteCid), cas)
        assertFalse(attributed.isSemantic)
        assertEquals(text, quote.quote)
    }

    @Test fun legacyStanfordThreeFieldRecordDoesNotProveCopyAbsence() {
        val text = "Rain causes floods."; val original = source(text)
        val plain = NlpDocument(text, s_[sentence(text)])
        val baseline = NlpcoreAxiomatics.recognize(plain, original.originalCid.value)
        assertEquals(1, baseline.size)
        val oldReader = plain.copy(metadata = NlpMetadata("edu.stanford.nlp.pipeline.StanfordCoreNLP",
            "borg.trikeshed.graal.subvm.CoreNlpRuntime", _m["module" j "corenlp"], _m["annotators" j "depparse"]))
        val retained = DocumentCuratorCodec.record(record(original, oldReader, baseline))
        val storedNlp = retained["nlp"] as Map<*, *>
        val legacySentences = (storedNlp["sentences"] as List<*>).map { raw ->
            val row = raw as Map<*, *>
            row + _m["dependencies" j (row["dependencies"] as List<*>).map { edge ->
                (edge as Map<*, *>).filterKeys { it in _s["governor", "dependent", "relation"] }
            }]
        }
        val bytes = CanonicalCbor.encodeMap(retained + _m["nlp" j (storedNlp + _m["sentences" j legacySentences])])
        val restored = DocumentCuratorCodec.decode(bytes)
        val parsed = requireNotNull(restored.nlp)
        assertFalse(parsed.sentences[0].hasCopiedNodes())
        assertEquals(NlpProjectionIssue.UNREPORTED_IDENTITY, parsed.projectionIssue(parsed.sentences[0]))
        assertEquals(NormConditionIssue.UNREPORTED_IDENTITY, NormClauses.conditionIssue(parsed, parsed.sentences[0]))
        assertEquals(DocumentNlpStatus.AVAILABLE, restored.curationIndex().facet(DocumentCurationIndexK.NlpStatus))
        assertEquals(0, NlpcoreAxiomatics.recognize(parsed).size)
        assertEquals(0, restored.nlpAxioms.size)
        assertEquals(0, DocumentCurationFacts.ruleCandidates(restored).size)
        assertTrue(NormClauses.extract(parsed, _s["rain"]).isEmpty())
        assertTrue(NormClauses.facts(parsed).isEmpty()); assertTrue(NormClauses.applicabilityFacts(parsed).isEmpty())
        val proposal = DocumentProposal(raw = "fixture", subject = "Rain", predicate = "cause", obj = "floods", quote = text,
            begin = 0, end = text.length, polarity = true, modality = "asserted")
        val checked = DocumentCuratorGrounding.reconcile(original, parsed, s_[proposal])[0]
        assertTrue(checked.reasons.view.any { "unreported dependency node identity" in it })
        val quotation = DocumentCuratorGrounding.quotation(original, checked)
        assertEquals(0, quotation.quotationBegin); assertEquals(text.length, quotation.quotationEnd)
        assertEquals(text, quotation.quote)
        // A new explicitly reported identity allows the same simple fragment to be examined normally.
        val current = parsed.copy(metadata = requireNotNull(parsed.metadata).copy(runtime = _m[
            "module" j "corenlp", "dependencyNodeIdentity" j "token-index+copy-count/1"]))
        assertNull(current.projectionIssue(current.sentences[0]))
        assertEquals(1, NlpcoreAxiomatics.recognize(current).size)
        val basic = plain.copy(metadata = NlpMetadata("explicit-basic-fixture", "fixture", _m["module" j "basic"]))
        assertNull(basic.projectionIssue(basic.sentences[0]))
        assertEquals(1, NlpcoreAxiomatics.recognize(basic).size)
    }

}
