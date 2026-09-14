package borg.trikeshed.graal

import borg.trikeshed.lib.*
import borg.trikeshed.ontology.SumoClassifier
import kotlin.test.*

class BlackboardNeighborsTest {
    val sumo = SumoClassifier.parse("""
        (subclass Role Entity)
        (subclass SoftwareRole Role)
        (subclass Engineer SoftwareRole)
        (subclass Developer SoftwareRole)
        (subclass Organism Entity)
        (subclass Tree Organism)
    """.trimIndent())

    @Test fun neighboringCrossesNamespacesWithoutSharedWordsOrOnlyTheUniversalRoot() {
        val board = ConfixBlackboard.empty()
        board.put("listing/a", "Engineer", "source")
        board.put("experience/b", "Developer", "user")
        board.put("lcnc/c", "Tree", "program")
        board.put("unknown/d", null, "user")
        val index = BlackboardNeighbors(board.snapshot(), sumo)
        assertEquals(board.keys().size, index.terms.size)
        val neighbors = index.neighbors("listing/a")
        assertEquals(1, neighbors.size)
        assertEquals("experience/b", neighbors[0].a)
        assertTrue("SoftwareRole" in neighbors[0].b[NeighborK.Concepts])
        assertEquals(0, neighbors[0].b[NeighborK.Terms].size)
        assertEquals("user", neighbors[0].b[NeighborK.Provenance]?.language)
    }

    @Test fun updatesAndDeletionChangeNeighborsWithoutMutatingRetainedResults() {
        val board = ConfixBlackboard.empty()
        board.put("listing/a", "Engineer", "source")
        board.put("profile/b", "Developer", "user")
        val before = BlackboardNeighbors(board.snapshot(), sumo).neighbors("listing/a")
        board.put("profile/b", "Tree", "user")
        assertEquals(0, BlackboardNeighbors(board.snapshot(), sumo).neighbors("listing/a").size)
        board.put("new/c", "Developer", "user")
        val after = BlackboardNeighbors(board.snapshot(), sumo).neighbors("listing/a")
        assertEquals("new/c", after[0].a)
        board.remove("new/c")
        assertEquals(0, BlackboardNeighbors(board.snapshot(), sumo).neighbors("listing/a").size)
        assertEquals("profile/b", before[0].a)
        assertTrue("SoftwareRole" in before[0].b[NeighborK.Concepts])
        assertFailsWith<IllegalArgumentException> { BlackboardNeighbors(board.snapshot(), sumo).neighbors("new/c") }
    }

    @Test fun seriesProductsAndCharacterViewsRetainConceptsAndReferencesRetainDirection() {
        val chars = "Engineer".let { text -> text.length j { i: Int -> text[i] } }
        val index = BlackboardNeighbors(ConfixBlackboard.Snapshot(7, mapOf(
            "listing/a" to s_["role" j chars],
            "private/b" to "Developer",
            "contact/c" to mapOf("record" to "listing/a"),
            "opaque/d" to ("key" j { _: String -> "not enumerable" }),
        ), emptyMap()), sumo)
        val neighbors = index.neighbors("listing/a")
        assertEquals("contact/c", neighbors[0].a)
        val reference = neighbors[0].b[NeighborK.References][0]
        assertEquals("contact/c", reference.a)
        assertEquals("listing/a", reference.b)
        assertTrue(neighbors.view.any { it.a == "private/b" && "SoftwareRole" in it.b[NeighborK.Concepts] })
        assertEquals(setOf("opaque/d"), index.opaque)
        assertEquals(1, index.neighbors("listing/a", 1).size)
    }

    @Test fun lexicalNeighborsWorkWithoutOntologyOrAModelAndDoNotCrossBoards() {
        val empty = SumoClassifier.parse("")
        val first = BlackboardNeighbors(ConfixBlackboard.Snapshot(1, mapOf(
            "a" to "Kotlin coroutine scheduling", "b" to "Kotlin structured concurrency", "c" to "orchard irrigation",
        ), emptyMap()), empty)
        val second = BlackboardNeighbors(ConfixBlackboard.Snapshot(1, mapOf("a" to "orchard"), emptyMap()), empty)
        assertEquals("b", first.neighbors("a")[0].a)
        assertEquals(0, first.neighbors("a")[0].b[NeighborK.Concepts].size)
        assertEquals(0, second.neighbors("a").size)
        assertEquals(0, first.neighbors("a", 0).size)
    }
}
