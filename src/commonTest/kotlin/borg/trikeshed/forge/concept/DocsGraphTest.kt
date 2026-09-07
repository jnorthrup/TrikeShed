package borg.trikeshed.forge.concept

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The docs mindmap as a pure function over text — no directory, no bake.
 *
 * Everything the picture claims comes from the corpus itself, so the tests hand it strings and
 * assert the claim: which column a document lands in, what its title and gloss are, and which of
 * its links become edges. A derived map's whole advantage over [ConceptGraph]'s hand-written one
 * is that it cannot drift from the corpus; these hold it to that.
 */
class DocsGraphTest {

    private fun doc(path: String, text: String) = DocSource(path, text)

    @Test
    fun theColumnComesFromTheNameAndThePrecedenceIsDeliberate() {
        assertEquals("index", DocsGraph.layerOf("guides-index"), "an index before it is a guide")
        assertEquals("guide", DocsGraph.layerOf("guide-kanban-board"))
        assertEquals("spec", DocsGraph.layerOf("oroboros-service-spec"))
        assertEquals("contract", DocsGraph.layerOf("nio-spi-contract"))
        assertEquals("contract", DocsGraph.layerOf("ccek-covenant"))
        assertEquals("contract", DocsGraph.layerOf("gates"))
        assertEquals("plan", DocsGraph.layerOf("forge-substrate-plan"))
        // An analysis before its subject prefix means anything — the check order is the ruling.
        assertEquals("analysis", DocsGraph.layerOf("oroboros-gap-analysis-2026-08-23"))
        assertEquals("analysis", DocsGraph.layerOf("marketability-kanban-mcp-audit"))
        assertEquals("note", DocsGraph.layerOf("escape-velocity"))
        for (id in listOf("guides-index", "guide-x", "a-spec", "a-contract", "a-plan", "a-audit", "x"))
            assertTrue(DocsGraph.layerOf(id) in DocsGraph.LAYERS, id)
    }

    @Test
    fun theTitleIsTheHeadingAndTheNameIsTheFallback() {
        assertEquals("The CCEK Covenant", DocsGraph.titleOf(doc("docs/ccek-covenant.md", "# The CCEK Covenant\n\nbody\n")))
        assertEquals("guide kanban board", DocsGraph.titleOf(doc("docs/guide-kanban-board.md", "no heading here\n")))
    }

    @Test
    fun theGlossIsTheFirstProseLineNotTheScaffolding() {
        val text = """
            # Title
            ## Subtitle
            > a quote
            - a bullet
            | a | table |
            ```
            code that mentions prose
            ```
            The daemon absorbs [the tree](oroboros-service-spec.md) as `data`.
        """.trimIndent()
        // Link syntax and backticks are stripped: the gloss is read, not parsed.
        assertEquals("The daemon absorbs the tree as data.", DocsGraph.glossOf(doc("docs/x.md", text)))
        assertEquals("", DocsGraph.glossOf(doc("docs/empty.md", "# Only a heading\n")))
    }

    @Test
    fun aLongGlossIsElidedRatherThanWrapped() {
        val gloss = DocsGraph.glossOf(doc("docs/x.md", "word ".repeat(200)), limit = 40)
        assertTrue(gloss.length <= 40, "gloss must respect the limit: ${gloss.length}")
        assertTrue(gloss.endsWith("…"), "an elision must be visible: $gloss")
    }

    @Test
    fun edgesAreTheCorpusOwnLinksAndOnlyThose() {
        val corpus = listOf(
            doc("docs/a.md", "# A\nsee [b](b.md) and [b again](b.md#section) and [out](../README.md) and [me](a.md)"),
            doc("docs/b.md", "# B\nsee [c](./c.md)"),
            doc("docs/c.md", "# C\nno links"),
        )
        val edges = DocsGraph.edgesOf(corpus)
        // A link named twice is one relation; a self-link is none; a link out of the corpus is none.
        assertEquals(listOf("a" to "b", "b" to "c"), edges.map { it.from to it.to })
        assertTrue(edges.all { it.rel == "cites" })
        assertFalse(edges.any { it.to == "README" }, "a map of docs/ must not invent nodes outside it")
    }

    @Test
    fun theSeedIsTheShapeTheGraphViewAlreadyDraws() {
        val corpus = listOf(
            doc("docs/guides-index.md", "# Guides\n[one](guide-one.md)"),
            doc("docs/guide-one.md", "# One\n[spec](thing-spec.md)"),
            doc("docs/thing-spec.md", "# Spec\nprose."),
        )
        val seed = DocsGraph.layoutSeed(corpus)
        @Suppress("UNCHECKED_CAST") val nodes = seed["nodes"] as List<Map<String, Any?>>
        assertEquals(3, nodes.size)
        assertEquals(2, (seed["edges"] as List<*>).size)
        assertEquals(DocsGraph.LAYERS, seed["layers"])
        assertTrue(seed["camera"] is Map<*, *>)
        for (n in nodes) for (k in listOf("id", "title", "layer", "symbol", "file", "x", "y", "topo"))
            assertTrue(k in n, "node is missing $k: $n")
        // Columns are the layer order, so the picture reads left to right.
        val byId = nodes.associateBy { it["id"] }
        val col = { id: String -> byId.getValue(id)["topo"] as Int }
        assertTrue(col("guides-index") < col("guide-one"), "an index sits left of the guides it lists")
        assertTrue(col("guide-one") < col("thing-spec"), "a guide sits left of the spec it cites")
    }

    @Test
    fun aDocumentInNoColumnIsDroppedWithItsEdgesRatherThanPiledAtTheOrigin() {
        val nodes = listOf(
            LayeredNode("kept", "Kept", "guide", "", "docs/kept.md"),
            LayeredNode("lost", "Lost", "not-a-layer", "", "docs/lost.md"),
        )
        val seed = layeredLayoutSeed(nodes, listOf(LayeredEdge("kept", "lost", "cites")), DocsGraph.LAYERS)
        @Suppress("UNCHECKED_CAST") val out = seed["nodes"] as List<Map<String, Any?>>
        assertEquals(listOf("kept"), out.map { it["id"] })
        assertEquals(0, (seed["edges"] as List<*>).size, "an edge to a dropped node draws to nothing")
    }

    @Test
    fun orphansAreTheDocumentsNothingCitesAndWhichCiteNothing() {
        val corpus = listOf(
            doc("docs/a.md", "# A\n[b](b.md)"),
            doc("docs/b.md", "# B\n"),
            doc("docs/lonely.md", "# Lonely\n"),
        )
        assertEquals(listOf("lonely"), DocsGraph.orphans(corpus))
    }
}
