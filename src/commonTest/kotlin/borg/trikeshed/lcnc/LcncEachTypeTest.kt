package borg.trikeshed.lcnc

import borg.trikeshed.lib.toSeries
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
            ).toSeries(),
        )
        assertEquals(emptyList(), LcncTypeCheck.check(program))
        val types = LcncTypeCheck.cableTypes(program)
        assertEquals(ProjectDoc.LIST_KIND, types[0], "docs → each is List<ProjectDoc> on both ends")
        assertEquals(ProjectDoc.KIND, types[1], "the item parameter is one ProjectDoc")
        assertEquals("text", types[2])
        assertEquals("List<text>", types[3], "a per-name yield under each is the list of one iteration's yield")
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
}
