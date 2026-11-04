package borg.trikeshed.narsese

import borg.trikeshed.job.*
import borg.trikeshed.lib.*
import borg.trikeshed.nlp.*
import kotlin.test.*

/** Typed observation fixtures verify retention only; they are not real producer or legal-accuracy claims. */
class DocumentCuratorNlpRetentionTest {
    private val text = "Alice 📚 joins Acme. She reads."
    private fun sentence(index: Int, begin: Int, end: Int): NlpSentence {
        val tokens = Regex("[\\p{L}]+|[^\\s]").findAll(text.substring(begin, end)).mapIndexed { i, match ->
            NlpToken(i + 1, begin + match.range.first, begin + match.range.last + 1,
                match.value, match.value.lowercase(), "NN", "O")
        }.toList().toSeries()
        return NlpSentence(index, begin, end, tokens, s_[NlpDependency(0, 1, "root")])
    }
    private fun document(): NlpDocument {
        val firstEnd = text.indexOf('.') + 1
        val first = sentence(0, 0, firstEnd).copy(relations = _l[
            NlpRelation(1..1, 3..3, 4..4, "Alice", "joins", "Acme", 0.875),
            // An empty relation range can still have a parser gloss; it does not create source words.
            NlpRelation(1..1, 5..3, 4..4, "Alice", "[is associated with]", "Acme", 0.625)],
            kbp = _l[NlpRelation(1..1, 1..0, 4..4, "ALICE", "per:employee_of", "Acme Corporation", 0.8125)])
        val second = sentence(1, firstEnd + 1, text.length).copy(relations = _l[
            NlpRelation(1..1, 2..2, 1..0, "She", "reads", "", 0.5)])
        return NlpDocument(text, s_[first, second], NlpMetadata("explicit-fixture", "retention-test",
            _m["observationOrigin" j "typed-fixture"], _m["annotators" j "fixture"]),
            _l[NlpMention(0, 1..1, "Alice", 7, true), NlpMention(1, 1..1, "She", 7, false),
                NlpMention(0, 4..4, "Acme", 9, true)])
    }
    private fun record(document: NlpDocument = document()): DocumentCurationRecord {
        val cid = ContentId.of(text.encodeToByteArray())
        return DocumentCurationRecord(DocumentSource(cid, cid, text, "fixture.txt", "text/plain", "fixture"),
            document, null, "none", emptySeriesOf(), s_["fixture observation only"])
    }
    private fun map(value: Any?) = (value as Map<*, *>).entries.associate { (it.key as String j it.value).pair }
    private fun changed(change: (Map<String, Any?>) -> Map<String, Any?>): ByteArray {
        val stored = DocumentCuratorCodec.record(record())
        return CanonicalCbor.encodeMap(stored + _m["nlp" j change(map(stored["nlp"]))])
    }
    private fun firstSentence(nlp: Map<String, Any?>, change: (Map<String, Any?>) -> Map<String, Any?>): Map<String, Any?> {
        val sentences = nlp["sentences"] as List<*>
        return nlp + _m["sentences" j sentences.mapIndexed { index, value -> if (index == 0) change(map(value)) else value }]
    }
    private fun endpoints(relation: NlpRelation) = _l[relation.subject.first, relation.subject.last,
        relation.relation.first, relation.relation.last, relation.`object`.first, relation.`object`.last]

    @Test fun storedRoundTripPreservesAllOutputsAndUnicodeWithoutInventingTokens() {
        val original = record()
        val bytes = DocumentCuratorCodec.encode(original)
        val restored = DocumentCuratorCodec.decode(bytes)
        val parsed = assertNotNull(restored.nlp)
        assertEquals(text, parsed.text)
        assertEquals(original.source, restored.source)
        assertEquals(original.reasons.view.toList(), restored.reasons.view.toList())
        assertEquals(original.nlp!!.metadata, parsed.metadata)
        assertEquals(original.nlp!!.mentions, parsed.mentions)
        for (index in 0 until parsed.sentences.size) {
            val before = original.nlp!!.sentences[index]
            val after = parsed.sentences[index]
            assertEquals(before.tokens.view.toList(), after.tokens.view.toList())
            assertEquals(before.relations, after.relations)
            assertEquals(before.kbp, after.kbp)
            // IntRange equality treats every empty range as equal; check the original endpoints explicitly.
            assertEquals(before.relations.map(::endpoints), after.relations.map(::endpoints))
            assertEquals(before.kbp.map(::endpoints), after.kbp.map(::endpoints))
            for (token in after.tokens.view) assertEquals(token.word, text.substring(token.begin, token.end))
        }
        assertEquals("[is associated with]", parsed.sentences[0].relations[1].relationText)
        assertEquals("per:employee_of", parsed.sentences[0].kbp.single().relationText)
        assertEquals("ALICE", parsed.sentences[0].kbp.single().subjectText)
        assertEquals(5, parsed.sentences[0].tokens.size)
        assertEquals("📚", parsed.sentences[0].tokens[1].word)
        assertContentEquals(bytes, DocumentCuratorCodec.encode(restored))
        assertEquals(_m["nlp" j true, "relations" j true, "kbp" j true, "mentions" j true],
            DocumentCuratorCodec.nlpRetentionCoverage(bytes))
    }

    @Test fun promptDeclaresCompleteRelationAndMentionRows() {
        val prompt = DocumentCuratorCodec.nlpPrompt(document())
        val columns = prompt["columns"] as Map<*, *>
        assertEquals(_l["index", "begin", "end", "tokens", "dependencies", "relations", "kbp"], columns["sentence"])
        assertEquals(_l["subjectFirstToken", "subjectLastToken", "relationFirstToken", "relationLastToken",
            "objectFirstToken", "objectLastToken", "subjectText", "relationText", "objectText", "confidence"], columns["relation"])
        assertEquals(_l["sentence", "firstToken", "lastToken", "text", "chain", "representative"], columns["mention"])
        val first = (prompt["sentences"] as List<*>).first() as List<*>
        assertEquals(_l[1, 1, 3, 3, 4, 4, "Alice", "joins", "Acme", 0.875], (first[5] as List<*>)[0])
        assertEquals(_l[1, 1, 5, 3, 4, 4, "Alice", "[is associated with]", "Acme", 0.625], (first[5] as List<*>)[1])
        assertEquals(_l[1, 1, 1, 0, 4, 4, "ALICE", "per:employee_of", "Acme Corporation", 0.8125], (first[6] as List<*>).single())
        assertEquals(_l[_l[0, 1, 1, "Alice", 7, true], _l[1, 1, 1, "She", 7, false], _l[0, 4, 4, "Acme", 9, true]], prompt["mentions"])
        assertFalse(prompt.containsKey("metadata"))
    }

    @Test fun legacyAbsenceIsEmptyButRawCoverageRemainsUnreportedWithoutChangingHistory() {
        val original = record()
        val legacy = changed { nlp ->
            nlp.filterKeys { it != "mentions" } + _m["sentences" j (nlp["sentences"] as List<*>).map {
                map(it).filterKeys { key -> key != "relations" && key != "kbp" }
            }]
        }
        assertEquals(_m["nlp" j true, "relations" j false, "kbp" j false, "mentions" j false],
            DocumentCuratorCodec.nlpRetentionCoverage(legacy))
        val restored = DocumentCuratorCodec.decode(legacy)
        val parsed = assertNotNull(restored.nlp)
        assertTrue(parsed.mentions.isEmpty())
        assertTrue(parsed.sentences.view.all { it.relations.isEmpty() && it.kbp.isEmpty() })
        assertEquals(original.reasons.view.toList(), restored.reasons.view.toList())
        assertEquals(original.nlp!!.metadata, parsed.metadata)
        // Presence of newly encoded empty fields never establishes that a historical producer returned none.
        assertEquals(_m["nlp" j true, "relations" j true, "kbp" j true, "mentions" j true],
            DocumentCuratorCodec.nlpRetentionCoverage(DocumentCuratorCodec.encode(restored)))
        assertEquals(_m["nlp" j false, "relations" j false, "kbp" j false, "mentions" j false],
            DocumentCuratorCodec.nlpRetentionCoverage(DocumentCuratorCodec.encode(original.copy(nlp = null))))
    }

    @Test fun partialOrMalformedPresentObservationFieldsRefuseRatherThanDisappear() {
        val relation = map(((DocumentCuratorCodec.nlp(document())["sentences"] as List<*>).first() as Map<*, *>)["relations"].let { (it as List<*>).first() })
        val badRelations = _l.get<Any?>(null, "not-array", _l[relation - "confidence"],
            _l[relation + _m["confidence" j "high"]], _l[relation + _m["confidence" j Double.NaN]],
            _l[relation + _m["confidence" j Double.POSITIVE_INFINITY]], _l[relation + _m["subject" j _l[1]]],
            _l[relation + _m["relation" j _l[1, 2, 3]]], _l[relation + _m["object" j _l[1.5, 2]]],
            _l[relation + _m["subject" j _l[1, Long.MAX_VALUE]]], _l[relation + _m["modality" j "asserted"]])
        for (value in badRelations) for (key in _l["relations", "kbp"])
            assertFails { DocumentCuratorCodec.decode(changed { firstSentence(it) { sentence -> sentence + _m[key j value] } }) }
        val mention = map((DocumentCuratorCodec.nlp(document())["mentions"] as List<*>).first())
        for (value in _l.get<Any?>(null, "not-array", _l[mention - "representative"],
            _l[mention + _m["representative" j "true"]], _l[mention + _m["span" j _l[1]]],
            _l[mention + _m["sentence" j 0.5]], _l[mention + _m["chain" j Long.MAX_VALUE]]))
            assertFails { DocumentCuratorCodec.decode(changed { it + _m["mentions" j value] }) }
    }

    @Test fun nonFiniteConfidenceIsRefusedBeforeStorageAndPromptProjection() {
        for (confidence in _l[Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY]) {
            val parsed = document()
            val sentence = parsed.sentences[0]
            val changed = parsed.copy(sentences = s_[sentence.copy(relations = _l[sentence.relations[0].copy(confidence = confidence)]), parsed.sentences[1]])
            assertFailsWith<IllegalArgumentException> { DocumentCuratorCodec.nlp(changed) }
            assertFailsWith<IllegalArgumentException> { DocumentCuratorCodec.nlpPrompt(changed) }
            assertFailsWith<IllegalArgumentException> { DocumentCuratorCodec.encode(record(changed)) }
        }
    }
}
