package narchy.spacegraph

import borg.trikeshed.cursor.BudgetCoord
import borg.trikeshed.lcnc.*
import borg.trikeshed.lib.*
import borg.trikeshed.narsese.SemanticSignal
import borg.trikeshed.narsese.TruthCoord

data class OntologicalPath(val terms: Series<String>, val sourceCid: String? = null)
typealias EpistemicEdge = Join<OntologicalPath, TruthCoord>

/** Exact source bindings are supplied by the authoritative caller, never inferred from screen proximity. */
data class EpistemicBinding(
    val nodeId: String,
    val ontology: OntologicalPath,
    val signal: SemanticSignal,
    val budget: BudgetCoord? = null,
) { val edge: EpistemicEdge get() = ontology j signal.truth() }

data class ShadowPort(val nodeId: String, val name: String, val input: Boolean, val kind: String?, val optional: Boolean)
data class ShadowIssue(val rule: String, val nodeId: String, val message: String)

/** The original program is retained intact: every projection can resolve back to its full LCNC semantics. */
data class LcncShadow(
    val source: LcncProgram,
    val graph: GraphSpec,
    val ports: Series<ShadowPort>,
    val issues: Series<ShadowIssue>,
    val epistemic: Series<EpistemicBinding>,
)

object LcncSpaceGraph {
    fun project(program: LcncProgram, contracts: List<LcncPortContract> = LcncContracts.all(),
                epistemic: Series<EpistemicBinding> = emptySeriesOf()): LcncShadow {
        val vocabulary = contracts.associateBy { it.type }
        val nodes = mutableListOf<NodeSpec>(); val ports = mutableListOf<ShadowPort>(); val issues = mutableListOf<ShadowIssue>()
        val ids = mutableSetOf<String>()
        fun visit(input: Series<LcncNode>, parent: String?) {
            for (node in input.view) {
                require(ids.add(node.id)) { "Duplicate LCNC node ID: ${node.id}" }
                val contract = vocabulary[node.type]
                if (contract == null) issues.add(ShadowIssue("unknown-type", node.id, "Retained LCNC type ${node.type}; no contract supplied"))
                val width = node.width ?: if (contract?.wide == true) 320.0 else 220.0
                val height = if (node.collapsed) 34.0 else node.height ?: maxOf(90.0, 58.0 + node.params.size * 24.0)
                val data = NodeData.of(linkedMapOf(
                    "width" to width, "height" to height, "lcncType" to node.type,
                    "params" to node.params, "collapsed" to node.collapsed, "subprogram" to node.subprogram,
                    "color" to if (node.children.size > 0) "#e6eef2" else "#ffffff",
                    "clip" to false, "source" to contract?.isSource, "sink" to contract?.isSink,
                    "effect" to contract?.isEffect,
                ))
                // LCNC persists top-left, Y-down coordinates. The scene uses centered, Y-up coordinates.
                val parentNode = nodes.firstOrNull { it.id == parent }
                val parentHalf = parentNode?.let { Vec3(it.data.width / 2, it.data.height / 2) } ?: Vec3.ZERO
                val position = Vec3(node.x + width / 2 - parentHalf.x, -node.y - height / 2 + parentHalf.y)
                nodes.add(NodeSpec(node.id, if (node.children.size > 0) NodeKind.GroupNode else NodeKind.PanelNode,
                    contract?.title ?: node.type, Transform(position), data, parent))
                contract?.inputs?.forEach { name -> ports.add(ShadowPort(node.id, name.removeSuffix("?"), true, contract.inputKinds[name.removeSuffix("?")], name.endsWith("?"))) }
                contract?.outputs?.forEach { name -> ports.add(ShadowPort(node.id, name, false, contract.outputKinds[name], false)) }
                visit(node.children, node.id)
            }
        }
        visit(program.nodes, null)
        val edges = mutableListOf<EdgeSpec>()
        program.wires.view.forEachIndexed { index, wire ->
            if (wire.fromNode !in ids || wire.toNode !in ids) {
                issues.add(ShadowIssue("dangling-wire", wire.toNode, "${wire.fromNode}#${wire.fromPort} -> ${wire.toNode}#${wire.toPort}"))
            } else {
                edges.add(EdgeSpec("lcnc-wire-$index", wire.fromNode, wire.toNode, EdgeKind.Wire,
                    NodeData.of(mapOf("fromPort" to wire.fromPort, "toPort" to wire.toPort,
                        "label" to "${wire.fromPort} -> ${wire.toPort}", "arrowhead" to "target"))))
            }
        }
        for (binding in epistemic.view) require(binding.nodeId in ids) { "Epistemic binding names missing node ${binding.nodeId}" }
        return LcncShadow(program, GraphSpec(nodes.toSeries(), edges.toSeries()), ports.toSeries(), issues.toSeries(), epistemic)
    }

    /** Presentation-only edits preserve params, children, controls, Kanban, sequence and named subprograms. */
    fun move(program: LcncProgram, nodeId: String, x: Double, y: Double): LcncProgram {
        require(x.isFinite() && y.isFinite()); var found = false
        fun moveNodes(nodes: Series<LcncNode>): Series<LcncNode> = nodes.size j { i ->
            val n = nodes[i]
            if (n.id == nodeId) { found = true; n.copy(x = x, y = y) } else n.copy(children = moveNodes(n.children))
        }
        // Materialize once before returning so validation never depends on whether a lazy consumer visits a node.
        fun freeze(nodes: Series<LcncNode>): Series<LcncNode> = nodes.view.map { it.copy(children = freeze(it.children)) }.toSeries()
        val moved = freeze(moveNodes(program.nodes))
        require(found) { "Unknown LCNC node $nodeId" }
        return program.copy(nodes = moved)
    }

    fun epistemicRows(bindings: Series<EpistemicBinding>): Series<Map<String, Any?>> = bindings.size j { i ->
        val b = bindings[i]; val s = b.signal
        linkedMapOf("nodeId" to b.nodeId, "ontology" to b.ontology.terms.view.toList(), "ontologySourceCid" to b.ontology.sourceCid,
            "angular" to s.angular.toString(), "evidence" to s.evidence.packed.toString(),
            "frequency" to s.truth().frequency, "confidence" to s.truth().confidence,
            "budget" to b.budget?.packed?.toString(), "relation" to s.relation.name,
            "subjectCid" to s.subjectCid, "objectCid" to s.objectCid,
            "provenanceCid" to s.provenanceCid, "basisBloom" to s.basisBloom.toString())
    }
}
