package borg.trikeshed.lcnc

import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
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
    /** A CCEK type name like `List<TurnFact>` is a kind; `<`/`>` would end the IRI, so they are percent-encoded. */
    fun kindIri(kind: String): RdfTerm.Iri = iri(KIND_NS + kind.replace("<", "%3C").replace(">", "%3E").replace(" ", "%20"))

    /**
     * The VOCABULARY, from the contracts. Kinds become classes so that
     * kind-compatibility can one day be `rdfs:subClassOf` instead of string
     * equality over five buckets — which is the weakness that let
     * `introspect.field` wire into `review.facts`, both json, both wrong.
     */
    fun ontology(contracts: List<LcncPortContract> = LcncContracts.all()): List<RdfTriple> {
        val out = ArrayList<RdfTriple>()
        // The kind hierarchy, from the names in use: `json.turn-facts` is a
        // class, and its dotted name IS the subClassOf edge (LcncKinds). A
        // shape's required keys ride the class, so a SHACL-minded reader can
        // reconstruct the literal refinement rule from the graph alone.
        val facts = LcncFacts.of(contracts)
        for (k in facts.kinds()) out.add(RdfTriple(kindIri(k), RDF_TYPE, pr("Kind")))
        for ((child, parent) in facts.hierarchy()) out.add(RdfTriple(kindIri(child), RDFS_SUBCLASS, kindIri(parent)))
        for ((k, keys) in facts.shapes()) for (key in keys) out.add(RdfTriple(kindIri(k), pr("requiresKey"), lit(key)))
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
                // Layout rides along so the mapping round-trips a canvas, not just a graph.
                out.add(RdfTriple(ni, pr("x"), lit(n.x.toString())))
                out.add(RdfTriple(ni, pr("y"), lit(n.y.toString())))
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

    /**
     * The BINDING edges — the late-bound join between a wrapper and what runs
     * it, one triple pair per type. `:type/pick lcnc:boundBy "…CanvasJsPureNodes…"`
     * is provenance a query can ask for ("which file binds this?") and blame
     * can walk, without reflecting on anything twice.
     */
    fun bindings(bindings: List<LcncBinding>): List<RdfTriple> {
        val out = ArrayList<RdfTriple>()
        for (b in bindings) {
            val t = typeIri(b.type)
            out.add(RdfTriple(t, pr("bindingKind"), lit(b.kind.name.lowercase())))
            if (b.provenance.isNotEmpty()) out.add(RdfTriple(t, pr("boundBy"), lit(b.provenance)))
        }
        return out
    }

    fun turtle(triples: List<RdfTriple>): String = triples.joinToString("\n") { it.toTurtle() }

    private val RDF_TYPE_IRI = RDF_TYPE.iri

    /**
     * The INVERSE mapping: a graph in this vocabulary back to a program. Nodes
     * are the subjects typed in [TYPE_NS]; params are `lcnc:param_<k>`; layout
     * is `lcnc:x`/`lcnc:y`; ring membership is `lcnc:inScope`; every
     * `lcnc:feeds` between two port IRIs is one cable. Terms this vocabulary
     * does not know are ignored — open world — so a graph annotated by another
     * tool (alignment, provenance, SHACL reports) still maps back cleanly.
     */
    fun programOf(graph: borg.trikeshed.rdf.RdfGraph, name: String): LcncProgram {
        data class Raw(val id: String, var type: String = "", val params: LinkedHashMap<String, String> = LinkedHashMap(), var x: Double = 0.0, var y: Double = 0.0, var parent: String? = null)
        val raws = LinkedHashMap<String, Raw>()
        fun nodeId(t: RdfTerm): String? {
            val i = (t as? RdfTerm.Iri)?.iri ?: return null
            if (!i.startsWith(NS)) return null
            val local = i.removePrefix(NS)
            if (local.isEmpty() || "%23" in local || "#" in local) return null
            return local
        }
        fun port(t: RdfTerm): Pair<String, String>? {
            val i = (t as? RdfTerm.Iri)?.iri ?: return null
            if (!i.startsWith(NS)) return null
            val local = i.removePrefix(NS)
            val sep = local.indexOf("%23").takeIf { it >= 0 } ?: local.indexOf('#').takeIf { it >= 0 } ?: return null
            val n = local.substring(0, sep); val p = local.substring(sep + if (local.startsWith("%23", sep)) 3 else 1)
            if (n.isEmpty() || p.isEmpty()) return null
            return n to p
        }
        val wires = ArrayList<LcncWire>()
        for (t in graph.triples) {
            val pred = t.p.iri
            if (pred == RDF_TYPE_IRI) {
                val id = nodeId(t.s) ?: continue
                val type = (t.o as? RdfTerm.Iri)?.iri?.takeIf { it.startsWith(TYPE_NS) }?.removePrefix(TYPE_NS) ?: continue
                raws.getOrPut(id) { Raw(id) }.type = type
                continue
            }
            if (!pred.startsWith(NS)) continue
            val local = pred.removePrefix(NS)
            when {
                local == "feeds" -> {
                    val from = port(t.s) ?: continue; val to = port(t.o) ?: continue
                    wires.add(LcncWire(from.first, from.second, to.first, to.second))
                }
                local.startsWith("param_") -> {
                    val id = nodeId(t.s) ?: continue
                    raws.getOrPut(id) { Raw(id) }.params[local.removePrefix("param_")] = (t.o as? RdfTerm.Literal)?.lexical ?: continue
                }
                local == "x" -> { val id = nodeId(t.s) ?: continue; raws.getOrPut(id) { Raw(id) }.x = (t.o as? RdfTerm.Literal)?.lexical?.toDoubleOrNull() ?: 0.0 }
                local == "y" -> { val id = nodeId(t.s) ?: continue; raws.getOrPut(id) { Raw(id) }.y = (t.o as? RdfTerm.Literal)?.lexical?.toDoubleOrNull() ?: 0.0 }
                local == "inScope" -> { val id = nodeId(t.s) ?: continue; raws.getOrPut(id) { Raw(id) }.parent = nodeId(t.o) }
            }
        }
        val typed = raws.values.filter { it.type.isNotEmpty() }
        val byParent = typed.groupBy { it.parent }
        fun build(r: Raw): LcncNode {
            val kids = byParent[r.id].orEmpty().map { build(it) }
            return LcncNode(id = r.id, type = r.type, params = r.params, x = r.x, y = r.y, children = kids.toSeries())
        }
        val top = byParent[null].orEmpty().map { build(it) }
        // A cable whose ends are not both typed nodes is dropped, not guessed.
        val known = typed.mapTo(HashSet()) { it.id }
        val keptWires = wires.filter { it.fromNode in known && it.toNode in known }
        return LcncProgram(name = name, nodes = top.toSeries(), wires = keptWires.toSeries())
    }

    /**
     * The CAUSAL projection for the NAL crossing: every cable as `lcnc:causes`
     * (KgNalBridge maps `causes` to IMPLICATION), every node as an instance of
     * its type (INHERITANCE). Ingested through /api/beliefs/kg this turns a
     * program into causal beliefs the belief bag and the causality rete can
     * reason over — the same IRIs the graph and the alignment use.
     */
    fun causalProjection(program: LcncProgram): List<RdfTriple> {
        val out = ArrayList<RdfTriple>()
        fun walk(nodes: List<LcncNode>) {
            for (n in nodes) {
                out.add(RdfTriple(nodeIri(n.id), RDF_TYPE, typeIri(n.type)))
                val kids = ArrayList<LcncNode>(); for (j in 0 until n.children.size) kids.add(n.children[j]); if (kids.isNotEmpty()) walk(kids)
            }
        }
        val top = ArrayList<LcncNode>(); for (j in 0 until program.nodes.size) top.add(program.nodes[j]); walk(top)
        for (j in 0 until program.wires.size) {
            val w = program.wires[j]
            out.add(RdfTriple(portIri(w.fromNode, w.fromPort), pr("causes"), portIri(w.toNode, w.toPort)))
        }
        return out
    }
}
