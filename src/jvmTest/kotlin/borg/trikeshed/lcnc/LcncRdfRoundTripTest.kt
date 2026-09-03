package borg.trikeshed.lcnc

import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import borg.trikeshed.rdf.RdfGraph
import borg.trikeshed.rdf.RdfTerm
import borg.trikeshed.rdf.RdfTriple
import borg.trikeshed.rdf.TurtleRdf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The RDF crossing is a MAPPING, not a report: [LcncRdf.graph] projects a
 * program to triples and [LcncRdf.programOf] maps triples back. The proof that
 * nothing is lost is a fixpoint — projecting the mapped-back program must
 * yield the same triple set — checked over every shipped preset, in memory
 * and through Turtle text (which is what the panel's "apply" actually sends).
 */
class LcncRdfRoundTripTest {

    private val corpus: Map<String, LcncProgram> =
        LcncPresets.all().mapValues { (n, j) -> LcncProgramConfix.fromJson(n, j) }

    private fun flat(p: LcncProgram): List<Triple<String, String?, LcncNode>> {
        val out = ArrayList<Triple<String, String?, LcncNode>>()
        fun walk(ns: List<LcncNode>, parent: String?) {
            for (n in ns) {
                out.add(Triple(n.id, parent, n))
                val kids = ArrayList<LcncNode>(); for (j in 0 until n.children.size) kids.add(n.children[j])
                walk(kids, n.id)
            }
        }
        val top = ArrayList<LcncNode>(); for (j in 0 until p.nodes.size) top.add(p.nodes[j]); walk(top, null)
        return out
    }

    private fun wires(p: LcncProgram): Set<LcncWire> =
        (0 until p.wires.size).map { p.wires[it] }.mapTo(LinkedHashSet()) { it.copy(toPort = it.toPort.removeSuffix("?")) }

    /** What the vocabulary carries of a node: id, type, non-empty params, layout, ring. */
    private fun carried(p: LcncProgram) = flat(p).map { (id, parent, n) ->
        listOf(id, parent, n.type, n.params.filterValues { it.isNotEmpty() }, n.x, n.y)
    }

    @Test
    fun everyPresetIsAFixpointOfGraphThenProgramOf() {
        assertTrue(corpus.isNotEmpty())
        for ((name, program) in corpus) {
            val g = LcncRdf.graph(program)
            val back = LcncRdf.programOf(RdfGraph(g), name)
            assertEquals(carried(program), carried(back), "$name: nodes")
            assertEquals(wires(program), wires(back), "$name: cables")
            assertEquals(g.toSet(), LcncRdf.graph(back).toSet(), "$name: projection fixpoint")
            assertEquals(g.size, LcncRdf.graph(back).size, "$name: no duplicate triples")
        }
    }

    @Test
    fun everyPresetSurvivesTurtleTextTheWayThePanelSendsIt() {
        for ((name, program) in corpus) {
            val g = LcncRdf.graph(program)
            val parsed = TurtleRdf.parse(LcncRdf.turtle(g))
            assertEquals(g.size, parsed.triples.size, "$name: every emitted triple parses back")
            val back = LcncRdf.programOf(parsed, name)
            assertEquals(carried(program), carried(back), "$name: nodes through text")
            assertEquals(wires(program), wires(back), "$name: cables through text")
            assertEquals(g.toSet(), LcncRdf.graph(back).toSet(), "$name: fixpoint through text")
        }
    }

    @Test
    fun paramTextWithQuotesNewlinesAndBackslashesSurvivesTurtle() {
        val prompt = "say \"hi\" to C:\\Users\\jim\nthen stop \\n literally"
        val p = LcncProgram(
            "quoted",
            listOf(LcncNode("n1", "prompt.chat", mapOf("prompt" to prompt), x = 12.5, y = -3.0)).toSeries(),
            emptyList<LcncWire>().toSeries(),
        )
        val back = LcncRdf.programOf(TurtleRdf.parse(LcncRdf.turtle(LcncRdf.graph(p))), "quoted")
        assertEquals(1, back.nodes.size)
        assertEquals(prompt, back.nodes[0].params["prompt"])
        assertEquals(12.5, back.nodes[0].x); assertEquals(-3.0, back.nodes[0].y)
    }

    @Test
    fun ringsComeBackAsRingsAndInwardCablesSurvive() {
        val inner = LcncNode("i1", "prompt.chat", mapOf("prompt" to "inside"), 1.0, 2.0)
        val ring = LcncNode("r", LcncContracts.SCOPE, emptyMap(), 0.0, 0.0, children = listOf(inner).toSeries())
        val outer = LcncNode("o", "timer", mapOf("seconds" to "7"))
        val p = LcncProgram("rings", listOf(outer, ring).toSeries(), listOf(LcncWire("o", "tick", "i1", "prompt?")).toSeries())
        val back = LcncRdf.programOf(RdfGraph(LcncRdf.graph(p)), "rings")
        assertEquals(carried(p), carried(back))
        assertEquals(setOf(LcncWire("o", "tick", "i1", "prompt")), wires(back))
        val r = (0 until back.nodes.size).map { back.nodes[it] }.first { it.id == "r" }
        assertEquals(1, r.children.size, "the inner node is a child, not a top-level orphan")
    }

    @Test
    fun openWorldForeignTriplesAreIgnoredAndHalfTypedCablesAreDropped() {
        val p = LcncProgram("ow", listOf(LcncNode("a", "timer"), LcncNode("b", "prompt.chat")).toSeries(), listOf(LcncWire("a", "tick", "b", "prompt")).toSeries())
        val shacl = RdfTriple(LcncRdf.nodeIri("a"), RdfTerm.Iri("http://www.w3.org/ns/shacl#conforms"), RdfTerm.Literal("true"))
        val dangling = RdfTriple(LcncRdf.portIri("b", "content"), RdfTerm.Iri(LcncRdf.NS + "feeds"), LcncRdf.portIri("ghost", "in"))
        val prov = RdfTriple(LcncRdf.nodeIri("b"), RdfTerm.Iri("http://www.w3.org/ns/prov#wasDerivedFrom"), LcncRdf.nodeIri("a"))
        val back = LcncRdf.programOf(RdfGraph(LcncRdf.graph(p) + shacl + dangling + prov), "ow")
        assertEquals(carried(p), carried(back))
        assertEquals(wires(p), wires(back), "the cable to the untyped ghost is dropped, not guessed")
    }

    @Test
    fun causalProjectionIsOneCauseTriplePerCableAndOneTypePerNode() {
        for ((name, program) in corpus) {
            val nal = LcncRdf.causalProjection(program)
            val causes = nal.count { it.p.iri == LcncRdf.NS + "causes" }
            val types = nal.count { it.p.iri == "http://www.w3.org/1999/02/22-rdf-syntax-ns#type" }
            assertEquals(program.wires.size, causes, "$name: causes")
            assertEquals(flat(program).size, types, "$name: inheritances")
            assertEquals(nal.size, causes + types, "$name: nothing else")
        }
    }
}
