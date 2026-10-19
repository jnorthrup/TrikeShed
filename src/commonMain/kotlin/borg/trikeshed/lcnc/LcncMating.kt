package borg.trikeshed.lcnc

import borg.trikeshed.lib.*

/**
 * Kotlin-owned mating operation over the contract vocabulary. Pointer geometry and
 * the selected type are merely ingress; all compatibility, cardinality,
 * function, add, and replace validation happens here before a new immutable
 * program value is returned for Confix persistence.
 *
 * Every compatibility question is a pattern over the vocabulary's tuples
 * ([LcncFacts]); this file shapes the answers, it does not loop the table.
 */
data class LcncMatingCandidate(
    val type: String,
    val inputPort: String,
    val title: String,
)

data class LcncMatedProgram(
    val program: LcncProgram,
    val wire: LcncWire,
    val matingPoint: LcncPatchMatingPoint,
)

/** Corpus-counted suggested parameter value. */
data class LcncParamFill(val param: String, val value: String, val count: Int)

/** One kind-compatible source/output → target/input pair. */
data class LcncAutoWireCandidate(val fromPort: String, val toPort: String, val kind: String)

/** Exactly one candidate yields [wire]; ambiguity returns all candidates and refuses to guess. */
data class LcncAutoWireResult(
    val wire: LcncWire?,
    val candidates: List<LcncAutoWireCandidate>,
)

object LcncMating {
    /**
     * Evidence-ranked parameter defaults for [type]. Values observed on nodes in
     * [corpus] rank count-desc/value-asc. Parameters with no observations expose
     * the contract default at count 0, so every wizard step remains complete.
     */
    fun paramFills(type: String, corpus: Collection<LcncProgram>): List<LcncParamFill> {
        val contract = LcncContracts.find(type) ?: return emptyList()
        val counts = linkedMapOf<String, MutableMap<String, Int>>()
        for (param in contract.params.keys) counts[param] = linkedMapOf()
        fun visit(nodes: Series<LcncNode>) {
            for (i in 0 until nodes.size) {
                val n = nodes[i]
                if (n.type == type) {
                    for ((param, value) in n.params) {
                        val bucket = counts[param] ?: continue
                        bucket[value] = (bucket[value] ?: 0) + 1
                    }
                }
                if (n.children.size > 0) visit(n.children)
            }
        }
        for (program in corpus) visit(program.nodes)
        val out = mutableListOf<LcncParamFill>()
        for ((param, spec) in contract.params) {
            val observed = counts[param].orEmpty()
            if (observed.isEmpty()) out += LcncParamFill(param, spec.v, 0)
            else observed.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .forEach { out += LcncParamFill(param, it.key, it.value) }
        }
        return out
    }

    /**
     * Infer a wire only when exactly one legal port pair exists in this program.
     * Multiple pairs are returned as evidence; no arbitrary first-pair guess.
     * Existing bindings determine kinds; the checker owns scope and type rules.
     */
    fun autoWire(program: LcncProgram, fromId: String, toId: String): LcncAutoWireResult {
        val from = node(program, fromId)
        val to = node(program, toId)
        if (fromId == toId) return LcncAutoWireResult(null, emptyList())
        val contracts = LcncContracts.all().associateBy { it.type }
        val facts = LcncFacts.of(contracts.values)
        val kind = LcncTypeCheck.portKindResolver(program, contracts, facts)
        val baseline = LcncTypeCheck.check(program, contracts).toSet()
        val candidates = mutableListOf<LcncAutoWireCandidate>()
        for (output in LcncTypeCheck.outputsOf(from, contracts)) {
            val fromPort = output.removeSuffix("?")
            val source = kind(from, "out", fromPort)
            if (source.kind == null && !source.generic) continue
            val sourceCardinality = contracts[from.type]?.cardinality?.get(fromPort) ?: LcncCardinality.ONE
            if (sourceCardinality != LcncCardinality.MANY && program.wires.view.any {
                    it.fromNode == fromId && it.fromPort.removeSuffix("?") == fromPort
                }) continue
            for (input in LcncTypeCheck.inputsOf(to, contracts)) {
                val toPort = input.removeSuffix("?")
                // Iteration changes the ring's yields; it is always an authored choice.
                if (input == LcncTypeCheck.RING_EACH) continue
                val target = kind(to, "in", toPort)
                if (target.kind == null && !target.generic || !facts.accepts(source, target)) continue
                val targetCardinality = contracts[to.type]?.cardinality?.get(toPort) ?: LcncCardinality.ONE
                if (program.wires.view.any {
                        it.toNode == toId && it.toPort.removeSuffix("?") == toPort &&
                            (targetCardinality != LcncCardinality.MANY ||
                                it.fromNode == fromId && it.fromPort.removeSuffix("?") == fromPort)
                    }) continue
                val candidate = LcncWire(fromId, output, toId, input)
                val wires = (program.wires.size + 1) j { i: Int ->
                    if (i < program.wires.size) program.wires[i] else candidate
                }
                // Also reject a binding that would make an existing cable invalid.
                if (LcncTypeCheck.check(program.copy(wires = wires), contracts).any { it !in baseline }) continue
                candidates.add(LcncAutoWireCandidate(output, input, source.kind ?: LcncKinds.UNRESOLVED))
            }
        }
        val wire = candidates.singleOrNull()?.let { LcncWire(fromId, it.fromPort, toId, it.toPort) }
        return LcncAutoWireResult(wire, candidates)
    }

    /**
     * Drag-to-blank narrowing, resolved through the ONE type authority
     * ([LcncTypeCheck.portKindResolver]) so the menu and the drop agree.
     *
     * Reading the kind straight out of `outputKinds` made this node-blind: a
     * ring's per-name port is declared by its `scope.out` child, never by the
     * `scope` contract, so the daemon answered "nothing is compatible" for a
     * port the canvas would wire without complaint — the menu and the manual
     * drag disagreed about the same cable. A generic ring port genuinely
     * accepts every kind; [genericSource] lets the surface say so instead of
     * pretending the wide list is a narrow one.
     */
    fun compatibleTypes(
        program: LcncProgram,
        sourceNode: String,
        sourcePort: String,
        /** The surface's already-resolved kind — a ring port's kind lives in the
         *  node, and `/api/lcnc/mating-options` only receives (type, port). */
        kindOverride: LcncTypeCheck.PortKind? = null,
        facts: LcncFacts = LcncFacts.of(LcncContracts.all()),
    ): List<LcncMatingCandidate> {
        val source = node(program, sourceNode)
        val contracts = LcncContracts.all().associateBy { it.type }
        // A source the vocabulary cannot see offers nothing: the menu refuses to
        // guess (the checker, by contrast, leaves such a port unresolved).
        val ring = source.children.size > 0 || !source.subprogram.isNullOrBlank() || !source.params["program"].isNullOrBlank()
        if (kindOverride == null && source.type !in contracts && !ring) return emptyList()
        if (kindOverride == null && LcncTypeCheck.outputsOf(source, contracts).none {
                it.removeSuffix("?") == sourcePort.removeSuffix("?")
            }) return emptyList()
        val sourceKind = kindOverride
            ?: LcncTypeCheck.portKindResolver(program, contracts, facts)(source, "out", sourcePort)
        if (sourceKind.kind == null && !sourceKind.generic) return emptyList()
        val kind = if (sourceKind.generic) LcncKinds.UNRESOLVED else sourceKind.kind!!
        // `(inKind ?T ?p ?K)` where K accepts an output of `kind` — vocabulary order.
        return facts.compatibleInputs(kind)
            .map { (type, input) -> LcncMatingCandidate(type, input, facts.label(type) ?: type) }
            .distinctBy { it.type to it.inputPort }
    }

    /** True when the source port has no declared or cable-derived kind. */
    fun genericSource(program: LcncProgram, sourceNode: String, sourcePort: String): Boolean {
        val source = node(program, sourceNode)
        val contracts = LcncContracts.all().associateBy { it.type }
        if (LcncTypeCheck.outputsOf(source, contracts).none {
                it.removeSuffix("?") == sourcePort.removeSuffix("?")
            }) return false
        return LcncTypeCheck.portKindResolver(program, contracts, LcncFacts.of(contracts.values))(
            source, "out", sourcePort,
        ).generic
    }

    /**
     * The mate-menu ordering — the drag-cable-to-empty-space popup's list:
     * kind-compatible candidates ranked by EVIDENCE. The prior is quota-free
     * Bayes counted from a corpus of stored programs (rings included): how
     * often `sourceType.sourcePort` actually wires into each target type in
     * practice — `(feeds ?g ?n port ?m ?q)` joined to `(node ?g ?n sourceType)`
     * and `(node ?g ?m ?T)`, counted by T. Deterministic: count descending,
     * then the contract-declared order. [q] is the popup's text-entry lane — a
     * name filter over type and title (blank = unfiltered). Live-usage
     * revision on top of this prior is the belief bag's job, later — this
     * function stays pure.
     */
    fun rankedCandidates(
        program: LcncProgram,
        sourceNode: String,
        sourcePort: String,
        corpus: Collection<LcncProgram> = emptyList(),
        q: String = "",
        kindOverride: LcncTypeCheck.PortKind? = null,
    ): List<LcncMatingCandidate> {
        val facts = LcncFacts.of(LcncContracts.all(), corpus.withIndex().associate { (i, p) -> "g$i" to p })
        val compatible = compatibleTypes(program, sourceNode, sourcePort, kindOverride, facts)
        val counts = facts.feedsInto(node(program, sourceNode).type, sourcePort)
        val needle = q.trim().lowercase()
        val filtered = if (needle.isEmpty()) compatible else compatible.filter {
            needle in it.type.lowercase() || needle in it.title.lowercase()
        }
        return filtered.withIndex()
            .sortedWith(
                compareByDescending<IndexedValue<LcncMatingCandidate>> { counts[it.value.type] ?: 0 }
                    .thenBy { it.index },
            )
            .map { it.value }
    }

    fun mate(
        program: LcncProgram,
        sourceNode: String,
        sourcePort: String,
        targetType: String,
        targetX: Double,
        targetY: Double,
    ): LcncMatedProgram {
        val source = node(program, sourceNode)
        val contracts = LcncContracts.all().associateBy { it.type }
        require(LcncTypeCheck.outputsOf(source, contracts).any {
            it.removeSuffix("?") == sourcePort.removeSuffix("?")
        }) {
            "unknown source output: ${source.type}.$sourcePort"
        }
        val candidate = compatibleTypes(program, sourceNode, sourcePort)
            .firstOrNull { it.type == targetType }
            ?: error("incompatible mated type: $targetType for ${source.type}.$sourcePort")
        val id = freshNodeId(program)
        val targetNode = LcncNode(id, candidate.type, x = targetX, y = targetY)
        val wire = LcncWire(sourceNode, sourcePort, id, candidate.inputPort)
        // The target is a new node; occupancy belongs to an instance, never its type.
        val srcCard = contracts[source.type]?.cardinality?.get(sourcePort.removeSuffix("?")) ?: LcncCardinality.ONE
        if (srcCard != LcncCardinality.MANY) {
            require(program.wires.view.none {
                it.fromNode == sourceNode && it.fromPort.removeSuffix("?") == sourcePort.removeSuffix("?")
            }) {
                "source output already wired: ${source.type}.$sourcePort ($srcCard cardinality)"
            }
        }
        val point = LcncPatchMatingPoint(
            id = "mate:$sourceNode:$sourcePort:$id:${candidate.inputPort}",
            fromNode = sourceNode,
            fromPort = sourcePort,
            toNode = id,
            toPort = candidate.inputPort,
            cardinality = srcCard,
            function = contracts[candidate.type]?.functions?.get(candidate.inputPort.removeSuffix("?"))?.firstOrNull() ?: "identity",
        ).validate()
        val controls = program.controls.addMatingPoint(point)
        val nodes = checkNotNull(mate(program.nodes, sourceNode, targetNode))
        val wires = (program.wires.size + 1) j { i: Int -> if (i < program.wires.size) program.wires[i] else wire }
        // W2.4: seq must clear the fresh id — "n3" ⇒ seq ≥ 4 — or the browser's
        // next addNode("n"+G.seq++) would collide with the mated node.
        val freshNum = id.removePrefix("n").toIntOrNull() ?: 0
        val nextSeq = maxOf(program.seq, freshNum + 1)
        val result = program.copy(nodes = nodes, wires = wires, controls = controls, seq = nextSeq)
        val baseline = LcncTypeCheck.check(program, contracts).toSet()
        require(LcncTypeCheck.check(result, contracts).none { it !in baseline }) { "mated wire violates the program's port or scope contracts" }
        return LcncMatedProgram(result, wire, point)
    }

    private fun node(program: LcncProgram, id: String): LcncNode =
        node(program.nodes, id)
            ?: error("unknown source node: $id")

    private fun node(nodes: Series<LcncNode>, id: String): LcncNode? {
        for (n in nodes.view) {
            if (n.id == id) return n
            node(n.children, id)?.let { return it }
        }
        return null
    }

    /** A new mate is a sibling of its source, retaining the source's enclosing frame. */
    private fun mate(nodes: Series<LcncNode>, sourceId: String, target: LcncNode): Series<LcncNode>? {
        if (nodes.view.any { it.id == sourceId })
            return (nodes.size + 1) j { i: Int -> if (i < nodes.size) nodes[i] else target }
        for (node in nodes.view) {
            val children = mate(node.children, sourceId, target) ?: continue
            return nodes α { if (it.id == node.id) it.copy(children = children) else it }
        }
        return null
    }

    private fun freshNodeId(program: LcncProgram): String {
        var n = maxOf(1, program.seq)
        fun visit(nodes: Series<LcncNode>) {
            for (node in nodes.view) {
                node.id.takeIf { it.startsWith("n") }?.removePrefix("n")?.toIntOrNull()?.let {
                    require(it < Int.MAX_VALUE - 1) { "node sequence exhausted" }
                    n = maxOf(n, it + 1)
                }
                visit(node.children)
            }
        }
        visit(program.nodes)
        require(n < Int.MAX_VALUE) { "node sequence exhausted" }
        return "n$n"
    }

    /** Whole-graph treeshake: pairs open ports with nearest legal mates under scope/type rules. */
    fun treeshake(
        program: LcncProgram,
        options: LcncTreeShakeOptions = LcncTreeShakeOptions(),
        contracts: Map<String, LcncPortContract> = LcncContracts.all().associateBy { it.type },
        facts: LcncFacts = LcncFacts.of(contracts.values),
    ): LcncTreeShakeResult = LcncTreeShake.shake(program, options, contracts, facts)
}
