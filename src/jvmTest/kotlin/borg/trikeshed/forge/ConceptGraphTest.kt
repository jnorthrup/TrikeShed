package borg.trikeshed.forge

import borg.trikeshed.forge.concept.ConceptGraph
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The concept lattice names real code: every file exists, every edge joins declared nodes, layers only go up. */
class ConceptGraphTest {

    @Test
    fun everyNodeNamesAnExistingFile() {
        val missing = ConceptGraph.nodes.filter { !File("src/commonMain/kotlin/" + it.file).isFile }.map { it.id + " → " + it.file }
        assertTrue(missing.isEmpty(), "concept nodes pointing at missing files: $missing")
    }

    @Test
    fun edgesJoinDeclaredNodesAndClimbLayers() {
        val ids = ConceptGraph.nodes.associateBy { it.id }
        assertEquals(ConceptGraph.nodes.size, ids.size, "node ids are unique")
        for (e in ConceptGraph.edges) {
            val from = ids[e.from] ?: error("edge from unknown node ${e.from}")
            val to = ids[e.to] ?: error("edge to unknown node ${e.to}")
            assertTrue(
                ConceptGraph.layerIndex(from.layer) <= ConceptGraph.layerIndex(to.layer),
                "edge ${e.from} → ${e.to} must not descend layers (${from.layer} → ${to.layer})",
            )
        }
        val reachable = ConceptGraph.edges.flatMap { listOf(it.from, it.to) }.toSet()
        val isolated = ids.keys - reachable
        assertTrue(isolated.isEmpty(), "isolated concept nodes: $isolated")
    }

    @Test
    fun layoutSeedHasTheGraphShapeScriptJsReads() {
        val seed = ConceptGraph.layoutSeed()
        val nodes = seed["nodes"] as List<*>
        assertEquals(ConceptGraph.nodes.size, nodes.size)
        val xs = nodes.map { (it as Map<*, *>)["x"] as Double }.toSet()
        assertEquals(ConceptGraph.LAYERS.size, xs.size, "one column per layer")
        val positions = nodes.map { (it as Map<*, *>).let { m -> m["x"] to m["y"] } }
        assertEquals(positions.size, positions.toSet().size, "no two nodes share a position")
        assertTrue((seed["camera"] as Map<*, *>).containsKey("zoom"))
    }
}
