package borg.trikeshed.lcnc

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import borg.trikeshed.lib.toList

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
     * Infer a wire only when exactly one kind-compatible port pair exists.
     * Multiple pairs are returned as evidence; no arbitrary first-pair guess.
     * The pairs are `(outKind from ?p ?K)` × `(inKind to ?q ?L)` where the
     * tuple rule accepts K into L.
     */
    fun autoWire(program: LcncProgram, fromId: String, toId: String): LcncAutoWireResult {
        val from = node(program, fromId)
        val to = node(program, toId)
        val candidates = LcncFacts.of(LcncContracts.all()).autoWire(from.type, to.type)
        val wire = candidates.singleOrNull()?.let { LcncWire(fromId, it.fromPort, toId, it.toPort) }
        return LcncAutoWireResult(wire, candidates)
    }

    /**
     * Drag-to-blank narrowing, resolved through the ONE type authority
     * ([LcncTypeCheck.portKind]) so the menu and the drop agree.
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
        val sourceKind = kindOverride
            ?: LcncTypeCheck.portKind(source, "out", sourcePort.removeSuffix("?"), contracts, facts)
        if (sourceKind.kind == null && !sourceKind.generic) return emptyList()
        val kind = if (sourceKind.generic) LcncKinds.UNRESOLVED else sourceKind.kind!!
        // `(inKind ?T ?p ?K)` where K accepts an output of `kind` — vocabulary order.
        return facts.compatibleInputs(kind)
            .map { (type, input) -> LcncMatingCandidate(type, input, facts.label(type) ?: type) }
            .distinctBy { it.type to it.inputPort }
    }

    /** True when the source port is a ring parameter that declared no kind. */
    fun genericSource(program: LcncProgram, sourceNode: String, sourcePort: String): Boolean =
        LcncTypeCheck.portKind(
            node(program, sourceNode), "out", sourcePort.removeSuffix("?"),
            LcncContracts.all().associateBy { it.type },
        ).generic

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
        require(sourcePort.removeSuffix("?") in (LcncContracts.find(source.type)?.outputs ?: emptyList())) {
            "unknown source output: ${source.type}.$sourcePort"
        }
        val candidate = compatibleTypes(program, sourceNode, sourcePort)
            .firstOrNull { it.type == targetType }
            ?: error("incompatible mated type: $targetType for ${source.type}.$sourcePort")
        val id = freshNodeId(program)
        val targetNode = LcncNode(id, candidate.type, x = targetX, y = targetY)
        val wire = LcncWire(sourceNode, sourcePort, id, candidate.inputPort)
        // Duplicate guard: a ONE-cardinality output can only fan out to ONE wire,
        // and a ONE-cardinality input can only receive ONE wire. Check BOTH sides
        // using the existing wire graph (not the fresh `id` which is always new).
        val srcCard = LcncContracts.find(source.type)?.cardinality?.get(sourcePort.removeSuffix("?")) ?: LcncCardinality.ONE
        if (srcCard == LcncCardinality.ONE) {
            require(program.wires.toList().none { it.fromNode == sourceNode && it.fromPort == sourcePort }) {
                "source output already wired: ${source.type}.$sourcePort (ONE cardinality)"
            }
        }
        val tgtCard = LcncContracts.find(targetType)?.cardinality?.get(candidate.inputPort.removeSuffix("?")) ?: LcncCardinality.ONE
        if (tgtCard == LcncCardinality.ONE) {
            val existingType = mutableMapOf<String, String>()
            require(program.wires.toList().none {
                it.toPort == candidate.inputPort && run {
                    val t = existingType.getOrPut(it.toNode) {
                        runCatching { node(program, it.toNode).type }.getOrDefault("")
                    }
                    t == targetType
                }
            }) {
                "target input already connected: ${candidate.type}.${candidate.inputPort} (ONE cardinality)"
            }
        }
        val point = LcncPatchMatingPoint(
            id = "mate:$sourceNode:$sourcePort:$id:${candidate.inputPort}",
            fromNode = sourceNode,
            fromPort = sourcePort,
            toNode = id,
            toPort = candidate.inputPort,
            cardinality = LcncContracts.find(source.type)?.cardinality?.get(sourcePort.removeSuffix("?")) ?: LcncCardinality.ONE,
            function = LcncContracts.find(candidate.type)?.functions?.get(candidate.inputPort.removeSuffix("?"))?.firstOrNull() ?: "identity",
        ).validate()
        val controls = program.controls.addMatingPoint(point)
        val nodes = program.nodes.toList().plus(targetNode).toSeries()
        val wires = program.wires.toList().plus(wire).toSeries()
        // W2.4: seq must clear the fresh id — "n3" ⇒ seq ≥ 4 — or the browser's
        // next addNode("n"+G.seq++) would collide with the mated node.
        val freshNum = id.removePrefix("n").toIntOrNull() ?: 0
        val nextSeq = maxOf(program.seq, freshNum + 1)
        return LcncMatedProgram(
            program.copy(nodes = nodes, wires = wires, controls = controls, seq = nextSeq),
            wire, point,
        )
    }

    private fun node(program: LcncProgram, id: String): LcncNode =
        program.nodes.toList().firstOrNull { it.id == id }
            ?: error("unknown source node: $id")

    private fun freshNodeId(program: LcncProgram): String {
        var n = program.nodes.toList().size + 1
        while (program.nodes.toList().any { it.id == "n$n" }) n++
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
