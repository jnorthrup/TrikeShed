package borg.trikeshed.lcnc

import borg.trikeshed.job.*
import borg.trikeshed.lib.*
import borg.trikeshed.narsese.*
import borg.trikeshed.nlp.*
import borg.trikeshed.rdf.*
import kotlin.test.*

/** Synthetic source-bound annotations test the carrier contract, not a real parser or legal truth. */
class EnglishTaxonomyCarrierTest {
    private class BoundedMemory : CasStore(), BoundedCasReader {
        override fun getBounded(cid: ContentId, maxBytes: Int): ByteArray? = super.get(cid)?.also {
            require(it.size <= maxBytes) { "Fixture object exceeds bounded read" }
        }
    }
    private val text = "Tenant is a Person. A Person is an Agent."
    private val context = "urn:agreement:pilot"
    private fun parsed(source: String, identity: String? = "token-index+copy-count/1",
        copied: Boolean = false, swapped: Boolean = false): NlpDocument {
        val sentences = Regex("[^.]+\\.").findAll(source).mapIndexed { sentenceIndex, raw ->
            val begin = raw.range.first + raw.value.indexOfFirst { !it.isWhitespace() }
            val end = raw.range.last + 1
            val tokens = Regex("[\\p{L}]+|[^\\s\\p{L}]").findAll(source.substring(begin, end)).mapIndexed { i, match ->
                val word = match.value
                val tag = when (word.lowercase()) { "a", "an", "the" -> "DT"; "is", "are" -> if (word == "is") "VBZ" else "VBP"; "." -> "."; else -> "NNP" }
                NlpToken(i + 1, begin + match.range.first, begin + match.range.last + 1, word,
                    if (word.lowercase() in _s["is", "are"]) "be" else word.lowercase(), tag, "O")
            }.toList()
            val nouns = tokens.filter { it.tag.startsWith("NN") }
            val subject = nouns.first(); val head = nouns.last(); val copula = tokens.firstOrNull { it.lemma == "be" }
            val dependencies = tokens.map { token ->
                val relation = when {
                    token.index == head.index -> "root"
                    token.index == subject.index -> if (swapped) "obj" else "nsubj"
                    token.index == copula?.index -> "cop"
                    token.word == "." -> "punct"
                    token.tag == "DT" -> "det"
                    else -> "dep"
                }
                val governor = when { relation == "root" -> 0; relation == "det" && token.index < subject.index -> subject.index; else -> head.index }
                NlpDependency(governor, token.index, relation)
            }.toMutableList()
            if (copied) dependencies.add(NlpDependency(head.index, head.index, "conj", 0, 1))
            NlpSentence(sentenceIndex, begin, end, tokens.toSeries(), dependencies.toSeries())
        }.toList()
        return NlpDocument(source, sentences.toSeries(), NlpMetadata("synthetic-fixture", "EnglishTaxonomyCarrierTest",
            if (identity == null) emptyMap() else _m["dependencyNodeIdentity" j identity]))
    }
    private fun snapshot(cas: BoundedMemory, source: String = text, parsed: NlpDocument = parsed(source)): EnglishObservationGraph.Snapshot {
        val cid = cas.put(source.encodeToByteArray())
        val revision = DocumentRevision("legal", "agreement.txt", cid.value, "1-fixture", 1)
        val document = DocumentSource(cid, cid, source, "agreement.txt", "text/plain", "synthetic-fixture",
            project = revision, extraction = revision)
        val inventory = EnglishInspection.inspect(document, parsed, 0, source.length)
        val row = ProjectDoc("legal", "agreement.txt", cid.value, revision.rev, revision.seq, source.encodeToByteArray().size.toLong(), "text/plain")
        val receipt = cas.put(CanonicalCbor.encodeMap(inventory + _m["source" j (DocumentCuratorCodec.source(document) - "text"),
            "selection" j revision.toMap(), "sourceSnapshots" j _l[row.toMap()]]))
        return EnglishObservationGraph.read(cas, receipt)
    }
    private fun declaration(id: Int, relation: String, subject: String, obj: String,
        subjectSort: String = if (relation == "instance") "individual" else "class"): Map<String, Any?> = _m[
        "id" j "sentence:$id", "relation" j relation, "subjectIri" j subject, "subjectSort" j subjectSort,
        "objectIri" j obj, "objectSort" j "class"]
    private fun choices() = _l[declaration(0, "instance", "urn:legal:Tenant", "urn:legal:Person"),
        declaration(1, "subclass", "urn:legal:Person", "urn:legal:Agent")]
    private fun selected(value: EnglishTaxonomyCarrier.Verified) = (EnglishTaxonomyCarrier.projection(value)["selections"] as List<*>).map { (it as Map<*, *>)["id"] as String }
    private fun tampered(cas: BoundedMemory, value: EnglishTaxonomyCarrier.Verified, change: (Map<String, Any?>) -> Map<String, Any?>): ContentId {
        val changed = change(value.built.declaration)
        val declaration = cas.put(CanonicalCbor.encodeMap(changed))
        val record = value.record.copy(source = value.record.source.copy(provenance = value.record.source.provenance +
            _m[EnglishTaxonomyCarrier.DECLARATION j declaration.value]))
        return cas.put(DocumentCuratorCodec.encode(record))
    }

    @Test fun exactTwoOccurrenceTranslationHasOwnKifSourceAndResolvableSidecars() {
        val cas = BoundedMemory(); val origin = snapshot(cas)
        val prepared = EnglishTaxonomyCarrier.prepare(cas, origin, context, choices())
        val checked = EnglishTaxonomyCarrier.verify(cas, prepared.receiptCid)
        assertEquals("(instance urn:legal:Tenant urn:legal:Person)\n(subclass urn:legal:Person urn:legal:Agent)\n", checked.built.kif)
        assertNotEquals(origin.source.originalCid, checked.record.source.originalCid)
        assertEquals(checked.record.source.originalCid, checked.record.source.extractedTextCid)
        assertEquals(checked.built.kif, cas.get(checked.record.source.originalCid)!!.decodeToString())
        assertNotNull(cas.get(prepared.declarationCid)); assertNull(checked.record.nlp)
        val candidate = EnglishTaxonomyCarrier.candidate(cas, prepared.receiptCid, selected(prepared))
        val evidence = assertNotNull(EnglishTaxonomyCarrier.evidence(cas, candidate))
        assertEquals(false, evidence["independentDocumentsOrNarsEvidence"])
        val witnesses = evidence["witnesses"] as List<*>
        assertEquals(2, witnesses.size)
        for ((i, raw) in witnesses.withIndex()) {
            val witness = raw as Map<*, *>; val quote = witness["englishQuotation"] as Map<*, *>
            assertEquals(origin.source.originalCid.value, quote["originalCid"])
            assertEquals(if (i == 0) "Tenant is a Person." else "A Person is an Agent.", quote["quote"])
            assertTrue((witness["bindings"] as List<*>).all { (it as Map<*, *>)["governorNode"] is String })
            assertEquals(prepared.declarationCid.value, witness["translationDecisionCid"])
        }
        assertFalse(prepared.built.declaration.containsKey("approvalCid"), "Declaration cannot refer to a future approval")
    }

    @Test fun sourceProfileRefusesQualifiersPastQuotedCoordinationAndUnknownIdentityWithoutInvalidatingObservations() {
        val unsupported = _l["Tenant is not a Person.", "Tenant may be a Person.", "Tenant was a Person.",
            "Tenant is a Person unless Owner agrees.", "Tenant is a Person except Tenant.",
            "\"Tenant is a Person.\"", "Tenant and Owner are a Person.", "If Tenant is a Person."]
        for (source in unsupported) {
            val cas = BoundedMemory(); val origin = snapshot(cas, source)
            assertTrue(EnglishTaxonomyCarrier.inspect(origin).exclusions.isNotEmpty(), source)
            assertFailsWith<IllegalArgumentException>(source) { EnglishTaxonomyCarrier.prepare(cas, origin, context, choices().take(1)) }
            assertEquals(source, EnglishObservationGraph.read(cas, origin.receiptCid).source.text)
        }
        for (identity in _l[null, "legacy-token-only/1", "invented"]) {
            val cas = BoundedMemory(); val origin = snapshot(cas, parsed = parsed(text, identity))
            assertTrue(EnglishTaxonomyCarrier.inspect(origin).exclusions.contains("reported-full-dependency-node-identity-required"))
        }
        for (annotations in _l[parsed(text, copied = true), parsed(text, swapped = true)]) {
            val cas = BoundedMemory(); val origin = snapshot(cas, parsed = annotations)
            assertTrue(EnglishTaxonomyCarrier.inspect(origin).exclusions.isNotEmpty())
            assertEquals(2, (origin.inventory["sentences"] as List<*>).size)
        }
    }

    @Test fun declarationsRequireExactAbsoluteKifConstantsSortsAndNoUndeclaredFields() {
        val cas = BoundedMemory(); val origin = snapshot(cas)
        for (iri in _l["relative", "urn:bad space", "urn:bad> <urn:p> <urn:o> . #", "urn:bad)", "urn:bad\nvalue", ""]) {
            assertFails { EnglishTaxonomyCarrier.prepare(cas, origin, context, _l[choices()[0] + _m["subjectIri" j iri]]) }
        }
        assertFailsWith<IllegalArgumentException> { EnglishTaxonomyCarrier.prepare(cas, origin, context,
            _l[choices()[0] + _m["subjectSort" j "class"]]) }
        assertFailsWith<IllegalArgumentException> { EnglishTaxonomyCarrier.prepare(cas, origin, context,
            _l[choices()[0] + _m["approved" j true]]) }
        assertFailsWith<IllegalArgumentException> { EnglishTaxonomyCarrier.prepare(cas, origin, context,
            _l[choices()[0], declaration(1, "subclass", "urn:legal:Tenant", "urn:legal:Agent")]) }
        val unicode = EnglishTaxonomyCarrier.prepare(cas, origin, "urn:agreement:é", _l[
            choices()[0] + _m["subjectIri" j "urn:legal:Ténant"]])
        assertTrue(unicode.built.kif.contains("urn:legal:Ténant"))
        val decomposed = EnglishTaxonomyCarrier.prepare(cas, origin, "urn:agreement:e\u0301", _l[
            choices()[0] + _m["subjectIri" j "urn:legal:Te\u0301nant"]])
        assertNotEquals(unicode.built.contextKey, decomposed.built.contextKey, "No Unicode identity normalization")
    }

    @Test fun sidecarQuoteNodeDeclarationAndKifIdentityTamperingFailsClosed() {
        val cas = BoundedMemory(); val origin = snapshot(cas)
        val prepared = EnglishTaxonomyCarrier.prepare(cas, origin, context, choices())
        fun changeForm(field: String, value: Any?): ContentId = tampered(cas, prepared) { d ->
            val forms = (d["forms"] as List<*>).map { it as Map<String, Any?> }
            d + _m["forms" j (_l[forms[0] + _m[field j value]] + forms.drop(1))]
        }
        for (cid in _l[
            changeForm("englishQuotation", _m["quote" j "Owner is a Person."]),
            changeForm("subject", _m["id" j "999:0"]),
            changeForm("declared", choices()[0] + _m["subjectIri" j "urn:legal:Owner"]),
            tampered(cas, prepared) { it + _m["derivedKifCid" j ContentId.of("different".encodeToByteArray()).value] },
            tampered(cas, prepared) { it + _m["approved" j true] }]) {
            assertFailsWith<IllegalArgumentException> { EnglishTaxonomyCarrier.verify(cas, cid) }
        }
        val missing = prepared.record.copy(source = prepared.record.source.copy(provenance = emptyMap()), modelId = "")
        assertTrue(EnglishTaxonomyCarrier.marked(missing), "Suffix alone prevents ordinary KIF fallback")
        assertFailsWith<IllegalArgumentException> { EnglishTaxonomyCarrier.verify(cas, cas.put(DocumentCuratorCodec.encode(missing))) }
        val candidate = EnglishTaxonomyCarrier.candidate(cas, prepared.receiptCid, selected(prepared))
        assertFailsWith<IllegalArgumentException> { EnglishTaxonomyCarrier.evidence(cas, candidate.copy(
            selections = candidate.selections α { it.copy(value = it.value + _m["englishOrigin" j "forged"]) })) }
        cas.corrupt(prepared.declarationCid)
        assertFails { EnglishTaxonomyCarrier.verify(cas, prepared.receiptCid) }
    }

    @Test fun familyUsesStableProjectDocumentContextNotTranslationCidAndNoPreparationApproval() {
        val cas = BoundedMemory(); val origin = snapshot(cas)
        val first = EnglishTaxonomyCarrier.prepare(cas, origin, context, choices())
        val changed = EnglishTaxonomyCarrier.prepare(cas, origin, context, _l[choices()[0] + _m["subjectIri" j "urn:legal:OtherTenant"], choices()[1]])
        val other = EnglishTaxonomyCarrier.prepare(cas, origin, "urn:agreement:other", choices())
        fun candidate(v: EnglishTaxonomyCarrier.Verified) = EnglishTaxonomyCarrier.candidate(cas, v.receiptCid, selected(v))
        assertNotEquals(first.declarationCid, changed.declarationCid)
        assertEquals(candidate(first).family, candidate(changed).family)
        assertNotEquals(candidate(first).family, candidate(other).family)
        assertEquals("unreviewed", EnglishTaxonomyCarrier.projection(first)["reviewStatus"])
        assertNull(EnglishTaxonomyCarrier.projection(first)["approvalCid"])
    }
}
