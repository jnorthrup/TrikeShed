package borg.trikeshed.lcnc

import borg.trikeshed.lib.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The exact typing of a ring's `each`: a list of what its item declares; yields become lists. */
class LcncEachTypeTest {

    private fun ring(itemKind: String?) = LcncNode("ring", LcncContracts.SCOPE, params = mapOf("item" to "doc"), children = listOf(
        LcncNode("in", LcncContracts.SCOPE_IN, params = itemKind?.let { mapOf("name" to "doc", "kind" to it) } ?: mapOf("name" to "doc")),
        LcncNode("read", ProjectNodes.READ),
        LcncNode("out", LcncContracts.SCOPE_OUT, params = mapOf("name" to "summary", "kind" to "text")),
    ).toSeries())

    @Test
    fun eachIsTheListOfTheItemKindAndYieldsBecomeLists() {
        val program = LcncProgram(
            "typed-each",
            listOf(LcncNode("docs", ProjectNodes.DOCS, params = mapOf("project" to "notes")), ring(ProjectDoc.KIND), LcncNode("show", "display")).toSeries(),
            listOf(
                LcncWire("docs", "docs", "ring", "each?"),
                LcncWire("in", "value", "read", "doc?"),
                LcncWire("read", "text", "out", "value"),
                LcncWire("ring", "summary", "show", "x"),
                LcncWire("ring", "returns", "show", "x"),
            ).toSeries(),
        )
        assertEquals(emptyList(), LcncTypeCheck.check(program))
        val types = LcncTypeCheck.cableTypes(program)
        assertEquals(ProjectDoc.LIST_KIND, types[0], "docs → each is List<ProjectDoc> on both ends")
        assertEquals(ProjectDoc.KIND, types[1], "the item parameter is one ProjectDoc")
        assertEquals("text", types[2])
        assertEquals("List<text>", types[3], "a per-name yield under each is the list of one iteration's yield")
        assertEquals("json", types[4], "the composed returns envelope is json even when it contains an array of iteration maps")
    }

    @Test
    fun aSingleDocumentIntoEachIsAKindMismatch() {
        val program = LcncProgram(
            "wrong-each",
            listOf(LcncNode("one", ProjectNodes.READ, params = mapOf("project" to "notes", "id" to "a.md")), ring(ProjectDoc.KIND)).toSeries(),
            listOf(LcncWire("one", "doc", "ring", "each?"), LcncWire("in", "value", "read", "doc?"), LcncWire("read", "text", "out", "value")).toSeries(),
        )
        val v = LcncTypeCheck.check(program)
        assertEquals(1, v.size, "$v")
        assertEquals("kind-mismatch", v[0].rule)
    }

    @Test
    fun anUndeclaredItemTakesTheCableThatFeedsEach() {
        val program = LcncProgram(
            "cable-fixed-each",
            listOf(LcncNode("docs", ProjectNodes.DOCS, params = mapOf("project" to "notes")), ring(null)).toSeries(),
            listOf(LcncWire("docs", "docs", "ring", "each?"), LcncWire("in", "value", "read", "doc?"), LcncWire("read", "text", "out", "value")).toSeries(),
        )
        assertEquals(emptyList(), LcncTypeCheck.check(program))
        assertEquals(ProjectDoc.LIST_KIND, LcncTypeCheck.cableTypes(program)[0])
        assertTrue(LcncTypeCheck.inputsOf(ring(null), LcncContracts.all().associateBy { it.type }).contains("each?"))
    }

    private fun release() = LcncProgram("release", s_[
        LcncNode("doc", "scope.in", mapOf("name" to "doc", "kind" to ProjectDoc.KIND)),
        LcncNode("relationships", "scope.out", mapOf("name" to "relationships", "kind" to "ReviewedRelationships")),
        LcncNode("output", "scope.out", mapOf("name" to "outputCid", "kind" to "id")),
    ], emptySeriesOf())

    @Test
    fun namedEachUsesRetainedFormalKindsAndComposesLists() {
        val scope = LcncNode("ring", "scope", mapOf("program" to "release", "item" to "doc"))
        val vocabulary = LcncVocabulary.resolve(mapOf("release" to release()))
        val program = LcncProgram("caller", s_[
            LcncNode("docs", ProjectNodes.DOCS), scope, LcncNode("show", "display"),
        ], s_[
            LcncWire("docs", "docs", "ring", "each?"),
            LcncWire("ring", "relationships", "show", "x"),
            LcncWire("ring", "outputCid", "show", "x"),
            LcncWire("ring", "returns", "show", "x"),
        ])
        assertEquals(emptyList(), LcncTypeCheck.check(program, vocabulary))
        assertEquals(listOf(ProjectDoc.LIST_KIND, "List<ReviewedRelationships>", "List<id>", "json"),
            LcncTypeCheck.cableTypes(program, vocabulary))
        assertEquals(ProjectDoc.LIST_KIND, LcncTypeCheck.portKind(scope, "in", "each?", vocabulary).kind)
        assertTrue("doc" in LcncTypeCheck.inputsOf(scope, vocabulary))
        assertTrue("outputCid" in LcncTypeCheck.outputsOf(scope, vocabulary))
    }

    @Test
    fun namedSingleInvocationPreservesItsFormalKindsAndRejectsFalsePorts() {
        val vocabulary = LcncVocabulary.resolve(mapOf("release" to release()))
        val scope = LcncNode("ring", "scope", subprogram = "release")
        assertEquals("ReviewedRelationships", LcncTypeCheck.portKind(scope, "out", "relationships", vocabulary).kind)
        assertEquals("json", LcncTypeCheck.portKind(scope, "out", "returns", vocabulary).kind)
        val program = LcncProgram("caller", s_[scope, LcncNode("sink", "mux.chat")], s_[
            LcncWire("ring", "outputCid", "sink", "prompt?"),
            LcncWire("ring", "absent", "sink", "prompt?"),
        ])
        assertEquals(listOf("kind-mismatch", "undeclared-port"), LcncTypeCheck.check(program, vocabulary).map { it.rule })
    }

    @Test
    fun aMissingNamedBodyRemainsUnresolvedAndInlineChildrenOwnTheirKinds() {
        val vocabulary = LcncVocabulary.resolve(mapOf("release" to release()))
        val missing = LcncNode("ring", "scope", mapOf("program" to "missing"))
        val program = LcncProgram("caller", s_[missing, LcncNode("show", "display")],
            s_[LcncWire("ring", "relationships", "show", "x")])
        assertEquals(listOf(null), LcncTypeCheck.cableTypes(program, vocabulary))
        assertTrue(LcncTypeCheck.portKind(missing, "out", "relationships", vocabulary).generic)
        val inline = missing.copy(params = mapOf("program" to "release"), children = s_[
            LcncNode("out", "scope.out", mapOf("name" to "relationships", "kind" to "text")),
        ])
        assertEquals("text", LcncTypeCheck.portKind(inline, "out", "relationships", vocabulary).kind)
    }
}
