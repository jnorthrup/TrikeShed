package borg.trikeshed.narsese

import borg.trikeshed.job.*
import borg.trikeshed.lib.*
import borg.trikeshed.nlp.*
import borg.trikeshed.rdf.EnglishObservationGraph
import kotlin.test.*

/** Source-bound synthetic annotations exercise the retained-input seam, not Stanford execution. */
class RetainedNalImplicationTest {
    private class BoundedMemory : CasStore(), BoundedCasReader {
        override fun getBounded(cid: ContentId, maxBytes: Int): ByteArray? = super.get(cid)?.also {
            require(it.size <= maxBytes)
        }
    }

    private val chain = "ALPHA implies BETA.\nBETA implies GAMMA."
    private val s12 = chain + "\nEPSILON implies GAMMA.\nEPSILON implies DELTA.\nALPHA implies BETA."

    private fun parsed(text: String, identity: String? = "token-index+copy-count/1",
        normalization: String = "identity/1", copied: Boolean = false,
        runtimeChange: (Map<String, String>) -> Map<String, String> = { it }): NlpDocument {
        val sentences = Regex("[^.]+\\.\"?").findAll(text).mapIndexed { ordinal, raw ->
            val begin = raw.range.first + raw.value.indexOfFirst { !it.isWhitespace() }
            val end = raw.range.last + 1
            val words = Regex("[A-Za-z0-9_]+|[^\\sA-Za-z0-9_]").findAll(text.substring(begin, end)).mapIndexed { i, match ->
                val word = match.value
                val lemma = when (word.lowercase()) {
                    "implies", "imply" -> "imply"; "entails" -> "entail"; "causes" -> "cause"
                    "does" -> "do"; "pays" -> "pay"; else -> word.lowercase()
                }
                val tag = when (word.lowercase()) {
                    "implies", "entails", "causes", "does", "pays" -> "VBZ"
                    "imply" -> "VB"; "may" -> "MD"; "not" -> "RB"
                    "." -> "."; "\"" -> "``"; else -> "NNP"
                }
                NlpToken(i + 1, begin + match.range.first, begin + match.range.last + 1, word, lemma, tag, "O")
            }.toList()
            val root = words.first { it.lemma in _s["imply", "entail", "cause", "pay"] }
            val subject = words.first { it.tag == "NNP" }
            val obj = words.last { it.tag == "NNP" }
            val dependencies = words.map { token ->
                val relation = when {
                    token.index == root.index -> "root"
                    token.index == subject.index -> "nsubj"
                    token.index == obj.index -> "obj"
                    token.lemma == "do" || token.tag == "MD" -> "aux"
                    token.word == "not" -> "neg"
                    token.word in _s[".", "\""] -> "punct"
                    else -> "obl"
                }
                NlpDependency(if (relation == "root") 0 else root.index, token.index, relation)
            }.toMutableList()
            if (copied) dependencies.add(NlpDependency(root.index, root.index, "conj", 0, 1))
            NlpSentence(ordinal, begin, end, words.toSeries(), dependencies.toSeries())
        }.toList()
        val textCid = ContentId.of(text.encodeToByteArray()).value
        val runtime = _m["parserNormalization" j normalization, "sourceTextCid" j textCid, "parserInputCid" j textCid] +
            (identity?.let { _m["dependencyNodeIdentity" j it] } ?: emptyMap())
        return NlpDocument(text, sentences.toSeries(), NlpMetadata("synthetic-fixture", "RetainedNalImplicationTest", runtimeChange(runtime)))
    }

    private fun snapshot(text: String = chain, end: Int = text.length,
        identity: String? = "token-index+copy-count/1", normalization: String = "identity/1",
        copied: Boolean = false, documentId: String = "symbols.txt",
        runtimeChange: (Map<String, String>) -> Map<String, String> = { it }): EnglishObservationGraph.Snapshot {
        val cas = BoundedMemory()
        val sourceCid = cas.put(text.encodeToByteArray())
        val revision = DocumentRevision("fixture", documentId, sourceCid.value, "1-fixture", 1)
        val source = DocumentSource(sourceCid, sourceCid, text, documentId, "text/plain", "synthetic-fixture",
            project = revision, extraction = revision)
        val observations = EnglishInspection.inspect(source, parsed(text.substring(0, end), identity, normalization, copied, runtimeChange), 0, end)
        val receipt = cas.put(CanonicalCbor.encodeMap(observations + _m[
            "source" j (DocumentCuratorCodec.source(source) - "text"), "selection" j revision.toMap()]))
        return EnglishObservationGraph.read(cas, receipt)
    }

    private fun RetainedNalReport.results(kind: RuleIdentity, from: String, to: String) = derivations.view.filter {
        it.kind == kind && termLabels[it.subject] == from && termLabels[it.predicate] == to
    }
    private fun refused(report: RetainedNalReport, status: RetainedNalStatus = RetainedNalStatus.UNSUPPORTED) {
        assertEquals(status, report.status)
        assertEquals(0, report.premises.size)
        assertEquals(0, report.derivations.size)
        assertTrue(report.terms.isEmpty())
        assertEquals(0, report.attemptedPairs)
        assertTrue(report.issues.size > 0)
    }

    @Test fun heldOutDeductionKeepsExactSourceAndFullIdentityPreimages() {
        val input = snapshot()
        val report = SyntheticNalExperiment.run(input)
        assertEquals(RetainedNalStatus.EVALUATED, report.status)
        assertEquals(2, report.premises.size)
        assertEquals(2, report.attemptedPairs)
        val deduction = report.results(RuleIdentity.DEDUCTION, "ALPHA", "GAMMA").single()
        assertEquals("deduced-hypothesis", deduction.status)
        assertTrue(report.premises.view.none { it.contribution.proposition == deduction.propositionCid })
        assertEquals(report.premises.view.map { it.contribution.cid }, deduction.premiseCids)
        assertEquals(_l[input.source.extractedTextCid], deduction.basis)
        assertTrue(deduction.positive > 0)
        assertEquals(0L, deduction.negative)
        assertEquals(input.receiptCid, report.inspectionCid)
        assertEquals(input.graph, report.graphIri)
        assertEquals(ContentId.of(CanonicalCbor.encodeMap(report.framePreimage)), report.frameCid)
        assertEquals(ContentId.of(CanonicalCbor.encodeMap(report.evaluatorPreimage)), report.evaluatorCid)
        assertEquals(ContentId.of(deduction.receiptPreimage.encodeToByteArray()), deduction.receiptCid)
        for (premise in report.premises.view) for (occurrence in premise.occurrences.view) {
            val span = occurrence.span
            assertEquals(input.source.text.substring(span.begin, span.end), span.quote)
            assertEquals(ContentId.of(CanonicalCbor.encodeMap(occurrence.occurrencePreimage)), span.occurrenceCid)
            assertEquals(ContentId.of(CanonicalCbor.encodeMap(occurrence.annotationPreimage)), span.annotationCid)
            assertEquals(ContentId.of(occurrence.recognizerRulePreimage.encodeToByteArray()), occurrence.recognizerRuleCid)
            assertEquals(input.graph + "/sentence/${occurrence.sentenceIndex}", occurrence.sentenceIri)
            assertEquals(occurrence.sentenceIri + "/node/1:0", occurrence.subjectNodeIri)
            assertEquals(occurrence.sentenceIri + "/node/2:0", occurrence.predicateNodeIri)
            assertEquals(occurrence.sentenceIri + "/node/3:0", occurrence.objectNodeIri)
            assertEquals(900L, premise.contribution.evidence.positive)
            assertEquals(_l[input.source.extractedTextCid], premise.contribution.basis)
        }
        val rendered = report.toMap()
        assertEquals("not-performed", rendered["admission"])
        assertEquals("not-checked", rendered["currentness"])
        assertEquals("not-reverified", rendered["parserExecution"])
        assertEquals(report.toMap(), SyntheticNalExperiment.run(input).toMap())
        assertTrue(report.unitPolicy.contains("not parser probability"))
    }

    @Test fun directionAndMissingPremiseDiscriminateDeductionFromWeakInduction() {
        val removed = SyntheticNalExperiment.run(snapshot("ALPHA implies BETA."))
        assertEquals(RetainedNalStatus.EVALUATED, removed.status)
        assertEquals(0, removed.derivations.size)
        val reversed = SyntheticNalExperiment.run(snapshot("ALPHA implies BETA.\nGAMMA implies BETA."))
        assertEquals(RetainedNalStatus.EVALUATED, reversed.status)
        assertEquals(0, reversed.derivations.size)
        val sharedSubject = SyntheticNalExperiment.run(snapshot("EPSILON implies GAMMA.\nEPSILON implies DELTA."))
        assertTrue(sharedSubject.derivations.view.none { it.kind == RuleIdentity.DEDUCTION })
        assertEquals("weak-induction-hypothesis", sharedSubject.results(RuleIdentity.INDUCTION, "GAMMA", "DELTA").single().status)
        assertEquals("weak-induction-hypothesis", sharedSubject.results(RuleIdentity.INDUCTION, "DELTA", "GAMMA").single().status)
        assertTrue(sharedSubject.derivations.view.all { it.basis == _l[sharedSubject.sourceCid] })
        val entailed = SyntheticNalExperiment.run(snapshot("ALPHA entails BETA.\nBETA implies GAMMA."))
        assertEquals(1, entailed.results(RuleIdentity.DEDUCTION, "ALPHA", "GAMMA").size)
    }

    @Test fun s12OccurrencesRemainDependentAndOnlyOriginalPremisesFeedOneLevel() {
        val report = SyntheticNalExperiment.run(snapshot(s12))
        assertEquals(RetainedNalStatus.EVALUATED, report.status)
        assertEquals(4, report.premises.size)
        assertEquals(5, report.premises.view.sumOf { it.occurrences.size })
        assertEquals(12, report.attemptedPairs)
        val repeated = report.premises.view.single { it.occurrences.size == 2 }
        assertEquals(900L, repeated.contribution.evidence.positive)
        assertEquals(2, repeated.occurrences.view.map { it.span.occurrenceCid }.distinct().size)
        assertEquals(1, repeated.occurrences.view.map { it.recognizerRuleCid }.distinct().size)
        val withoutRepeat = SyntheticNalExperiment.run(snapshot(s12.substringBeforeLast('\n')))
        assertEquals(report.attemptedPairs, withoutRepeat.attemptedPairs)
        assertEquals(report.results(RuleIdentity.DEDUCTION, "ALPHA", "GAMMA").single().positive,
            withoutRepeat.results(RuleIdentity.DEDUCTION, "ALPHA", "GAMMA").single().positive)
        assertTrue(report.derivations.view.any { it.status == "weak-induction-hypothesis" })
        val deduction = report.results(RuleIdentity.DEDUCTION, "ALPHA", "GAMMA").single()
        val induction = report.results(RuleIdentity.INDUCTION, "GAMMA", "DELTA").single()
        assertTrue(deduction.positive > induction.positive)
        assertTrue(Nal.truthOf(repeated.contribution.evidence).confidence in 0.47f..0.48f,
            "900 structural milli-units use the existing 1000 milli-unit NAL horizon")
        val inputCids = report.premises.view.map { it.contribution.cid }.toSet()
        assertTrue(report.derivations.view.all { it.premiseCids.all { cid -> cid in inputCids } })
        assertTrue(report.derivations.view.all { it.basis == _l[report.sourceCid] })
        val longer = SyntheticNalExperiment.run(snapshot(chain + "\nGAMMA implies DELTA."))
        assertEquals(1, longer.results(RuleIdentity.DEDUCTION, "ALPHA", "GAMMA").size)
        assertEquals(1, longer.results(RuleIdentity.DEDUCTION, "BETA", "DELTA").size)
        assertTrue(longer.results(RuleIdentity.DEDUCTION, "ALPHA", "DELTA").isEmpty(), "No recursive feeding of a new hypothesis")
    }

    @Test fun separateInspectionsNeverSupplyACombinedFrameOrSurfaceIdentity() {
        val first = SyntheticNalExperiment.run(snapshot("ALPHA implies BETA."))
        val second = SyntheticNalExperiment.run(snapshot("BETA implies GAMMA."))
        assertEquals(0, first.derivations.size)
        assertEquals(0, second.derivations.size)
        val firstBeta = first.termLabels.entries.single { it.value == "BETA" }.key
        val secondBeta = second.termLabels.entries.single { it.value == "BETA" }.key
        assertNotEquals(first.frameCid, second.frameCid)
        assertNotEquals(firstBeta, secondBeta)
        val sameTextAnotherDocument = SyntheticNalExperiment.run(snapshot("ALPHA implies BETA.", documentId = "another.txt"))
        assertEquals(first.sourceCid, sameTextAnotherDocument.sourceCid)
        assertNotEquals(first.inspectionCid, sameTextAnotherDocument.inspectionCid)
        assertNotEquals(first.frameCid, sameTextAnotherDocument.frameCid)
    }

    @Test fun anyUnaccountedNegativeModalQuotationRoleOrContextRefusesWholeFrame() {
        val unsupported = s_[
            "ALPHA does not imply BETA.", "ALPHA may imply BETA.", "\"ALPHA implies BETA.\"",
            "TENANT pays LANDLORD.", "ALPHA implies BETA in AGREEMENT.", "ALPHA causes BETA.",
            "Alpha implies BETA.", "ALPHA implies a BETA.", "ALPHA implies BETA unless GAMMA."
        ]
        for (clause in unsupported.view) {
            val input = snapshot("GAMMA implies DELTA.\n$clause")
            val report = SyntheticNalExperiment.run(input)
            refused(report)
            assertEquals(input.source.text, report.sourceText)
        }
    }

    @Test fun partialCopiedUnreportedAndNormalizedFramesRefuseBeforePairExecution() {
        val partial = SyntheticNalExperiment.run(snapshot(end = chain.indexOf('\n')))
        refused(partial)
        assertEquals("partial-frame", partial.issues[0].code)
        val copied = SyntheticNalExperiment.run(snapshot(copied = true))
        refused(copied)
        assertEquals("copied-node", copied.issues[0].code)
        val unreported = SyntheticNalExperiment.run(snapshot(identity = null))
        refused(unreported)
        assertEquals("unreported-dependency-identity", unreported.issues[0].code)
        val normalized = SyntheticNalExperiment.run(snapshot(normalization = "reader-specific/1"))
        refused(normalized)
        assertEquals("parser-normalization", normalized.issues[0].code)
        for (field in s_["sourceTextCid", "parserInputCid"].view) {
            val missing = SyntheticNalExperiment.run(snapshot(runtimeChange = { it - field }))
            refused(missing)
            assertEquals("parser-input-identity", missing.issues[0].code)
            val changed = SyntheticNalExperiment.run(snapshot(runtimeChange = { it + _m[field j ContentId.of("other".encodeToByteArray()).value] }))
            refused(changed)
            assertEquals("parser-input-identity", changed.issues[0].code)
        }
    }

    @Test fun rawClauseBoundPrecedesDeduplicationAndCapsOrderedPairs() {
        val text = (0 until 64).joinToString("\n") { "ATOM${it} implies RESULT${it}." }
        val atBound = SyntheticNalExperiment.run(snapshot(text))
        assertEquals(RetainedNalStatus.EVALUATED, atBound.status)
        assertEquals(64, atBound.premises.size)
        assertEquals(4032, atBound.attemptedPairs)
        assertEquals(0, atBound.derivations.size)
        refused(SyntheticNalExperiment.run(snapshot(text + "\nLAST implies OVERFLOW.")), RetainedNalStatus.BOUNDED)
        val repeated = (0 until 65).joinToString("\n") { "ALPHA implies BETA." }
        refused(SyntheticNalExperiment.run(snapshot(repeated)), RetainedNalStatus.BOUNDED)
    }
}
