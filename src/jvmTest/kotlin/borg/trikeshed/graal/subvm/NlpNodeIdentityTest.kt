package borg.trikeshed.graal.subvm

import borg.trikeshed.job.*
import borg.trikeshed.lib.*
import borg.trikeshed.narsese.*
import borg.trikeshed.nlp.*
import kotlin.test.*

/** Explicit parser graphs verify lossless transport, not language accuracy or reasoning. */
class NlpNodeIdentityTest {
    private val text = "Acme 📚 pays Beta."
    private val tokens = _l[_l[1, 0, 4, "Acme", "Acme", "NNP", "ORGANIZATION"],
        _l[2, 5, 7, "📚", "📚", "NN", "O"], _l[3, 8, 12, "pays", "pay", "VBZ", "O"],
        _l[4, 13, 17, "Beta", "Beta", "NNP", "ORGANIZATION"], _l[5, 17, 18, ".", ".", ".", "O"]]
    private val copyRows = _l[_l[0, 3, "root", 0, 0], _l[3, 1, "nsubj", 0, 0],
        _l[3, 4, "obj", 0, 0], _l[3, 1, "nsubj", 1, 0], _l[3, 3, "conj:and", 0, 1],
        _l[3, 4, "obj", 1, 0], _l[3, 2, "dep", 0, 0], _l[3, 5, "punct", 0, 0]]

    private fun wire(rows: List<List<Any?>>): Map<String, Any?> = _m["text" j text,
        "sentences" j _l[_l[0, 0, text.length, tokens, rows, _l.get<Any?>(), _l.get<Any?>()]],
        "mentions" j _l.get<Any?>()]

    private fun record(document: NlpDocument): DocumentCurationRecord {
        val cid = ContentId.of(text.encodeToByteArray())
        return DocumentCurationRecord(DocumentSource(cid, cid, text, "graph-fixture.txt", "text/plain", "fixture"),
            document, null, "none", emptySeriesOf(), emptySeriesOf())
    }

    private fun dependencies(document: NlpDocument) = document.sentences[0].dependencies.view.toList()

    private fun replaceNamedDependencies(document: NlpDocument, rows: List<Map<String, Any?>>): ByteArray {
        val nlp = DocumentCuratorCodec.nlp(document)
        val sentence = ((nlp["sentences"] as List<*>).single() as Map<*, *>).entries.associate { (it.key as String j it.value).pair }
        val replacement = nlp + _m["sentences" j _l[sentence + _m["dependencies" j rows]]]
        return CanonicalCbor.encodeMap(DocumentCuratorCodec.record(record(document)) + _m["nlp" j replacement])
    }

    @Test fun fiveFieldWirePreservesCopiedNodesAndExactOriginalTokens() {
        val document = NlpWire.decode(wire(copyRows))
        val edges = dependencies(document)
        assertEquals(8, edges.size)
        assertEquals(8, edges.toSet().size)
        assertTrue(edges.any { it.governor == it.dependent && it.governorCopy != it.dependentCopy })
        assertEquals(2, edges.count { it.governor == 3 && it.dependent == 4 && it.relation == "obj" })
        assertEquals(_s[0, 1], edges.filter { it.governor == 3 && it.dependent == 4 }.map { it.governorCopy }.toSet())
        assertEquals(5, document.sentences[0].tokens.size, "Graph copies must not duplicate source text tokens")
        assertEquals(text, document.text)
        for (token in document.sentences[0].tokens.view)
            assertEquals(token.word, text.substring(token.begin, token.end))
        assertEquals("📚", document.sentences[0].tokens[1].word)
        val rootCopy = NlpWire.decode(wire(_l[_l[0, 3, "root", 0, 1]]))
        assertEquals(1, dependencies(rootCopy).single().dependentCopy)
    }

    @Test fun storedAndPromptFormsRetainEveryCopyEndpoint() {
        val original = NlpWire.decode(wire(copyRows)).copy(metadata = NlpMetadata("fixture", "fixture",
            _m["dependencyGraph" j "enhanced-plus-plus", "dependencyNodeIdentity" j "token-index+copy-count/1"]))
        val restored = assertNotNull(DocumentCuratorCodec.decode(DocumentCuratorCodec.encode(record(original))).nlp)
        assertEquals(dependencies(original), dependencies(restored))
        assertEquals(original.text, restored.text)
        assertEquals(original.metadata, restored.metadata)
        for (index in 0 until original.sentences[0].tokens.size)
            assertEquals(original.sentences[0].tokens[index], restored.sentences[0].tokens[index])
        val prompt = DocumentCuratorCodec.nlpPrompt(restored)
        assertEquals(_l["governor", "dependent", "relation", "governorCopy", "dependentCopy"],
            (prompt["columns"] as Map<*, *>)["dependency"])
        val rows = ((prompt["sentences"] as List<*>).single() as List<*>)[4]
        assertEquals(copyRows, rows)
    }

    @Test fun historicalThreeFieldRowsAndAbsentNamedFieldsDefaultToZeroCopies() {
        val legacyRows = _l[_l[0, 3, "root"], _l[3, 1, "nsubj"], _l[3, 4, "obj"]]
        val document = NlpWire.decode(wire(legacyRows))
        assertTrue(dependencies(document).all { it.governorCopy == 0 && it.dependentCopy == 0 })
        val named = dependencies(document).map { _m["governor" j it.governor, "dependent" j it.dependent, "relation" j it.relation] }
        val restored = assertNotNull(DocumentCuratorCodec.decode(replaceNamedDependencies(document, named)).nlp)
        assertEquals(dependencies(document), dependencies(restored))
        assertEquals(NlpDependency(3, 4, "obj"), NlpDependency(3, 4, "obj", 0, 0))
    }

    @Test fun malformedCopyCountsAndAmbiguousPartialRowsAreRefused() {
        for (row in _l[_l[3, 4, "obj", 1], _l[3, 4, "obj", 0, 0, 0],
            _l[3, 4, "obj", -1, 0], _l[3, 4, "obj", 0.5, 0],
            _l[3, 4, "obj", null, 0], _l[3, 4, "obj", "1", 0], _l[0, 3, "root", 1, 0]])
            assertFails { NlpWire.decode(wire(_l[row])) }
        val document = NlpWire.decode(wire(copyRows))
        for (copy in _l.get<Any?>(-1, 0.5, null, "1", Double.POSITIVE_INFINITY)) {
            val row = _m["governor" j 3, "dependent" j 4, "relation" j "obj", "governorCopy" j copy, "dependentCopy" j 0]
            assertFails { DocumentCuratorCodec.decode(replaceNamedDependencies(document, _l[row])) }
        }
        assertFailsWith<IllegalArgumentException> { NlpDependency(3, 4, "obj", 0, -1) }
        assertFailsWith<IllegalArgumentException> { NlpDependency(0, 3, "root", 1, 0) }
    }
}
