package borg.trikeshed.lcnc

import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.rdf.RdfTerm
import borg.trikeshed.rdf.RdfTriple

/**
 * LCNC as triples — GENERATED, never authored.
 *
 * [LcncContracts] is already the one author; the retired JS TYPES table is the
 * cautionary tale and this file exists to not become the next one. An ontology
 * written by hand would be a second source that drifts exactly the way the three
 * facet picklists drifted from `VmFacet`. So the ontology is a PROJECTION of the
 * contracts and the graph is a PROJECTION of the program, and neither is
 * authoritative for anything.
 *
 * ## The one modelling decision
 *
 * A wire joins (node, port) to (node, port) — four terms, not three. Reifying it
 * as a `lcnc:Wire` with four properties is the obvious encoding and the wrong
 * one: it is unqueryable and it hides the edge inside a node.
 *
 * A PORT IS ALREADY A RESOURCE — node id plus port name, which is what
 * `VertexId(patch, offset)` is one layer down. Name it, and a wire is a plain
 * triple:
 *
 *     :n1#tick  lcnc:feeds  :n2#trigger .
 *
 * One triple per cable, and the whole graph is queryable without reification.
 * This is the same recognition as CharSequence being Series<Char>: nothing is
 * adapted, the shape was already there and had no name.
 *
 * ## Why RDF and not XSD or JSON Schema
 *
 * Both of those are closed-world — an unknown element or additionalProperties
 * false is a validation failure — so every new node type is a schema revision
 * and every stored program needs migrating. RDF is open-world: adding a term
 * invalidates no existing triple, and a reader that does not know it ignores it.
 * Thirty-two isEffect flags were added to this vocabulary in one commit without
 * breaking a single stored panel; under RDF that is guaranteed rather than
 * lucky. Both are also TREE schemas over what is natively a cyclic GRAPH, so
 * routing through them is a lossy detour. Constraints, when wanted, belong in
 * SHACL — closed-world shapes over an open-world graph, opt-in per shape.
 */
object LcncRdf {

    const val NS = "https://trikeshed.borg/lcnc#"
    const val KIND_NS = "https://trikeshed.borg/lcnc/kind#"
    const val TYPE_NS = "https://trikeshed.borg/lcnc/type#"

    private fun iri(s: String) = RdfTerm.Iri(s)
    private fun lit(s: String) = RdfTerm.Literal(s)
    private fun pr(local: String) = RdfTerm.Iri(NS + local)
    private val RDF_TYPE = RdfTerm.Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#type")
    private val RDFS_SUBCLASS = RdfTerm.Iri("http://www.w3.org/2000/01/rdf-schema#subClassOf")
    private val RDFS_LABEL = RdfTerm.Iri("http://www.w3.org/2000/01/rdf-schema#label")

    /** A port's IRI: the node it lives on, then its name. Ports are resources. */
    fun portIri(nodeId: String, port: String): RdfTerm.Iri =
        iri("$NS$nodeId%23${port.removeSuffix("?")}")

    fun nodeIri(nodeId: String): RdfTerm.Iri = iri(NS + nodeId)
    fun typeIri(type: String): RdfTerm.Iri = iri(TYPE_NS + type)
    fun kindIri(kind: String): RdfTerm.Iri = iri(KIND_NS + kind)

    /**
     * The VOCABULARY, from the contracts. Kinds become classes so that
     * kind-compatibility can one day be `rdfs:subClassOf` instead of string
     * equality over five buckets — which is the weakness that let
     * `introspect.field` wire into `review.facts`, both json, both wrong.
     */
    fun ontology(contracts: List<LcncPortContract> = LcncContracts.all()): List<RdfTriple> {
        val out = ArrayList<RdfTriple>()
        for (c in contracts) {
            val t = typeIri(c.type)
            out.add(RdfTriple(t, RDF_TYPE, pr("NodeType")))
            out.add(RdfTriple(t, RDFS_LABEL, lit(c.title)))
            if (c.isSource) out.add(RdfTriple(t, RDFS_SUBCLASS, pr("Source")))
            if (c.isSink) out.add(RdfTriple(t, RDFS_SUBCLASS, pr("Sink")))
            // An effect CHANGES SOMETHING outside the graph. As a class, a query
            // can ask "what does this program write" without reading any code.
            if (c.isEffect) out.add(RdfTriple(t, RDFS_SUBCLASS, pr("Effect")))
            for (i in c.inputs) {
                val bare = i.removeSuffix("?")
                out.add(RdfTriple(t, pr("declaresIn"), lit(bare)))
                if (!i.endsWith("?")) out.add(RdfTriple(t, pr("requiresIn"), lit(bare)))
                c.inputKinds[bare]?.let { out.add(RdfTriple(t, pr("inKind_$bare"), kindIri(it))) }
            }
            for (o in c.outputs) {
                out.add(RdfTriple(t, pr("declaresOut"), lit(o)))
                c.outputKinds[o]?.let { out.add(RdfTriple(t, pr("outKind_$o"), kindIri(it))) }
            }
        }
        return out
    }

    /**
     * ONE PROGRAM as an instance graph. Ports are resources, so every wire is a
     * single `lcnc:feeds` triple and the open-socket question — the treeshake —
     * becomes a query rather than a traversal:
     *
     *     ?p a lcnc:InPort ; lcnc:required true .
     *     FILTER NOT EXISTS { ?x lcnc:feeds ?p }
     */
    fun graph(program: LcncProgram): List<RdfTriple> {
        val out = ArrayList<RdfTriple>()
        fun walk(nodes: List<LcncNode>, parent: String?) {
            for (n in nodes) {
                val ni = nodeIri(n.id)
                out.add(RdfTriple(ni, RDF_TYPE, typeIri(n.type)))
                // Ring containment as a property, so "lateral or inward" is a
                // property path a query can state instead of a rule each caller
                // re-implements.
                parent?.let { out.add(RdfTriple(ni, pr("inScope"), nodeIri(it))) }
                for ((k, v) in n.params) if (v.isNotEmpty()) out.add(RdfTriple(ni, pr("param_$k"), lit(v)))
                val c = LcncContracts.find(n.type)
                for (i in c?.inputs.orEmpty()) {
                    val pi = portIri(n.id, i)
                    out.add(RdfTriple(pi, RDF_TYPE, pr("InPort")))
                    out.add(RdfTriple(pi, pr("onNode"), ni))
                    if (!i.endsWith("?")) out.add(RdfTriple(pi, pr("required"), lit("true")))
                }
                for (o in c?.outputs.orEmpty()) {
                    val po = portIri(n.id, o)
                    out.add(RdfTriple(po, RDF_TYPE, pr("OutPort")))
                    out.add(RdfTriple(po, pr("onNode"), ni))
                }
                val kids = ArrayList<LcncNode>()
                for (j in 0 until n.children.size) kids.add(n.children[j])
                if (kids.isNotEmpty()) walk(kids, n.id)
            }
        }
        val top = ArrayList<LcncNode>()
        for (j in 0 until program.nodes.size) top.add(program.nodes[j])
        walk(top, null)
        for (j in 0 until program.wires.size) {
            val w = program.wires[j]
            // THE POINT: one triple per cable, no reification.
            out.add(RdfTriple(portIri(w.fromNode, w.fromPort), pr("feeds"), portIri(w.toNode, w.toPort)))
        }
        return out
    }

    fun turtle(triples: List<RdfTriple>): String = triples.joinToString("\n") { it.toTurtle() }
}
