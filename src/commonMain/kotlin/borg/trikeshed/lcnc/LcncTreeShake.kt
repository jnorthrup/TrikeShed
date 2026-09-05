package borg.trikeshed.lcnc

import borg.trikeshed.collections.ChunkedMutableSeries
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import kotlin.math.hypot

data class LcncTreeShakeOptions(
    val reach: Double = 340.0,
    val includeOptional: Boolean = false,
    val parentId: String? = null,
) {
    companion object {
        fun fromMap(value: Map<*, *>?): LcncTreeShakeOptions {
            val parent = value?.get("parentId")
            require(parent == null || parent is String && parent.isNotBlank()) { "parentId must be a nonempty node id" }
            val reach = (value?.get("reach") as? Number)?.toDouble() ?: 340.0
            require(reach.isFinite() && reach >= 0) { "reach must be finite and nonnegative" }
            return LcncTreeShakeOptions(reach, value?.get("optional") == true, parent as? String)
        }
    }
}

data class LcncTreeShakeVerdict(
    val nodeId: String,
    val dir: String, // "in" or "out"
    val port: String,
    val kind: String?,
    val status: String, // "ok", "open", "scope", "dead", "binding", "optional"
    val label: String,
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "nodeId" to nodeId,
        "dir" to dir,
        "port" to port,
        "kind" to kind,
        "status" to status,
        "label" to label,
    )
}

data class LcncTreeShakeResult(
    val program: LcncProgram,
    val made: List<LcncWire>,
    val verdicts: List<LcncTreeShakeVerdict>,
    val starvedNodeIds: Set<String>,
    val outletBlockedCount: Int,
    val parentId: String? = null,
    val socketCount: Int = 0,
    val connectedSocketCount: Int = 0,
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "ok" to true,
        "parentId" to parentId,
        "made" to made.map { mapOf("fromNode" to it.fromNode, "fromPort" to it.fromPort, "toNode" to it.toNode, "toPort" to it.toPort) },
        "verdicts" to verdicts.map { it.toMap() },
        "starved" to starvedNodeIds.toList(),
        "outletBlocked" to outletBlockedCount,
        "coverage" to mapOf("connected" to connectedSocketCount, "total" to socketCount),
        "program" to LcncProgramConfix.toJson(program),
    )
}

/**
 * Server-authoritative tree-shaking for LCNC graphs.
 *
 * Checks declared kind compatibility and lateral/inward scope containment. Executable
 * programs use proximity pairing with optional-input and effect protections. Inspection-only
 * specimens use bounded, type-validated matching across all sockets, with distance ranking
 * alternatives rather than excluding them. Selection bounds new proposals, not context.
 */
object LcncTreeShake {

    private const val MAX_SPECIMEN_NODES = 1500
    private const val MAX_SPECIMEN_SOCKETS = 2048
    private const val MAX_MATCH_STEPS = 4_000_000

    private class MatchingBudget {
        private var steps = 0
        fun step() { require(++steps <= MAX_MATCH_STEPS) { "Wiring specimen matching budget exceeded" } }
    }

    private fun bare(port: String): String = port.removeSuffix("?")

    // Hash lookups need value equality; the general j constructor uses identity.
    private data class PortKey(override val a: String, override val b: String) : Join<String, String>

    data class OpenIn(
        val nd: LcncNode,
        val port: String,
        val isRequired: Boolean,
        val isEffect: Boolean,
        val kind: LcncTypeCheck.PortKind,
        val sp: List<String>,
        val x: Double,
        val y: Double,
    )

    data class OpenOut(
        val nd: LcncNode,
        val port: String,
        val kind: LcncTypeCheck.PortKind,
        val sp: List<String>,
        val x: Double,
        val y: Double,
    )

    data class CandidatePair(
        val i: OpenIn,
        val o: OpenOut,
        val dist: Double,
    )

    /** Iterative augmenting paths maximize coverage without depending on layout.
     * Distance orders alternatives; no authored answer or node-id convention is used. */
    private fun completeMatching(pairs: List<CandidatePair>, inputs: List<OpenIn>, budget: MatchingBudget): List<CandidatePair> {
        val candidates = pairs.groupBy { it.i }
        val matchedIn = HashMap<OpenIn, CandidatePair>()
        val matchedOut = HashMap<OpenOut, CandidatePair>()
        for (start in inputs.sortedWith(compareBy<OpenIn> { candidates[it]?.size ?: 0 }.thenByDescending { it.sp.size })) {
            val queue = arrayListOf(start)
            val predecessor = HashMap<OpenOut, CandidatePair>()
            var head = 0
            var end: CandidatePair? = null
            search@ while (head < queue.size) {
                for (edge in candidates[queue[head++]].orEmpty()) {
                    budget.step()
                    if (edge.o in predecessor) continue
                    predecessor[edge.o] = edge
                    val occupied = matchedOut[edge.o]
                    if (occupied == null) { end = edge; break@search }
                    queue.add(occupied.i)
                }
            }
            while (end != null) {
                val edge = end
                val previous = matchedIn.put(edge.i, edge)
                matchedOut[edge.o] = edge
                end = previous?.let { predecessor[it.o] }
            }
        }
        return inputs.mapNotNull { matchedIn[it] }
    }

    private fun validatedMatching(
        program: LcncProgram, pairs: List<CandidatePair>, inputs: List<OpenIn>, contracts: Map<String, LcncPortContract>, facts: LcncFacts,
    ): List<CandidatePair> {
        val budget = MatchingBudget()
        val resolved = LcncTypeCheck.portKindResolver(program, contracts, facts)
        fun variable(node: LcncNode, dir: String, port: String): PortKey? {
            val contract = contracts[node.type] ?: return null
            val declared = (if (dir == "in") contract.inputKinds else contract.outputKinds)[bare(port)] ?: return null
            return if (LcncKinds.isTypeVariable(declared)) PortKey(node.id, declared) else null
        }
        val choices = linkedMapOf<PortKey, List<LcncTypeCheck.PortKind>>()
        for (input in inputs) {
            val key = variable(input.nd, "in", input.port) ?: continue
            if (key in choices) continue
            val fixed = resolved(input.nd, "in", input.port)
            // Bind one type variable consistently across its inputs AND outputs.
            // Candidate evidence supplies the alternatives, not a hardcoded kind.
            choices[key] = if (!fixed.generic && fixed.kind != null) listOf(fixed) else pairs
                .filter { variable(it.i.nd, "in", it.i.port) == key }
                .map { resolved(it.o.nd, "out", it.o.port) }
                .filter { !it.generic && it.kind != null }
                .groupingBy { it }.eachCount().entries
                .sortedWith(compareByDescending<Map.Entry<LcncTypeCheck.PortKind, Int>> { it.value }.thenBy { it.key.kind })
                .map { it.key }.ifEmpty { listOf(fixed) }
        }
        val keys = choices.keys.toList()
        val indices = IntArray(keys.size)
        var best: List<CandidatePair>? = null
        var lastViolations: List<LcncTypeCheck.Violation> = emptyList()
        repeat(64) {
            val bindings = keys.indices.associate { i -> keys[i] to choices.getValue(keys[i])[indices[i]] }
            fun kind(node: LcncNode, dir: String, port: String) =
                variable(node, dir, port)?.let { bindings[it] } ?: resolved(node, dir, port)
            val candidates = pairs.filter { edge ->
                budget.step()
                val source = kind(edge.o.nd, "out", edge.o.port)
                val target = kind(edge.i.nd, "in", edge.i.port)
                facts.accepts(source, target) &&
                    (variable(edge.i.nd, "in", edge.i.port) == null || target.generic || source.kind == target.kind)
            }
            val matched = completeMatching(candidates, inputs, budget)
            val wires = ChunkedMutableSeries<LcncWire>()
            for (i in 0 until program.wires.size) wires.add(program.wires[i])
            for (edge in matched) wires.add(LcncWire(edge.o.nd.id, edge.o.port, edge.i.nd.id, edge.i.port))
            val trial = program.copy(wires = wires.freeze())
            val violations = LcncTypeCheck.check(trial, contracts)
            lastViolations = violations
            if (violations.isEmpty()) {
                if (best == null || matched.size > best!!.size) best = matched
                if (matched.size == inputs.size) return matched
            }
            var cursor = keys.lastIndex
            while (cursor >= 0) {
                indices[cursor]++
                if (indices[cursor] < choices.getValue(keys[cursor]).size) break
                indices[cursor--] = 0
            }
            if (cursor < 0) return best ?: throw IllegalArgumentException("Specimen wiring failed type validation: $lastViolations")
        }
        return best ?: throw IllegalArgumentException("Wiring specimen type-binding budget exceeded: $lastViolations")
    }

    fun shake(
        program: LcncProgram,
        options: LcncTreeShakeOptions = LcncTreeShakeOptions(),
        contracts: Map<String, LcncPortContract> = LcncContracts.all().associateBy { it.type },
        facts: LcncFacts = LcncFacts.of(contracts.values),
    ): LcncTreeShakeResult {
        val specimen = program.controls.inspectionOnly
        val allNodes = ArrayList<LcncNode>()
        val byId = LinkedHashMap<String, LcncNode>()
        val pathOf = LinkedHashMap<String, List<String>>()

        fun walk(ns: Series<LcncNode>, path: List<String>) {
            require(!specimen || path.size <= 32) { "Wiring specimen exceeds 32 scope levels" }
            for (i in 0 until ns.size) {
                require(!specimen || allNodes.size < MAX_SPECIMEN_NODES) { "Wiring specimen exceeds $MAX_SPECIMEN_NODES nodes" }
                val n = ns[i]
                allNodes.add(n)
                byId[n.id] = n
                pathOf[n.id] = path
                if (n.children.size > 0) walk(n.children, path + n.id)
            }
        }
        walk(program.nodes, emptyList())

        val parent = options.parentId?.let { id ->
            requireNotNull(byId[id]) { "selected parent not found: $id" }.also {
                require(it.type == LcncContracts.SCOPE || it.children.size > 0) { "selected parent is not a container: $id" }
            }
        }
        // Keep full ancestry and existing inbound bindings. Selection bounds new
        // proposals, not the program's evaluation context.
        val selectedNodes = if (parent == null) allNodes else allNodes.filter { parent.id in pathOf[it.id].orEmpty() }

        val existingWires = ChunkedMutableSeries<LcncWire>()
        for (i in 0 until program.wires.size) existingWires.add(program.wires[i])

        val fedIn = HashSet<Join<String, String>>()
        val usedOut = HashSet<Join<String, String>>()
        for (w in existingWires) {
            fedIn.add(PortKey(w.toNode, bare(w.toPort)))
            usedOut.add(PortKey(w.fromNode, bare(w.fromPort)))
        }

        val openIns = ArrayList<OpenIn>()
        val openOuts = ArrayList<OpenOut>()
        val verdicts = ArrayList<LcncTreeShakeVerdict>()

        for (nd in selectedNodes) {
            val c = contracts[nd.type]
            val ins = LcncTypeCheck.inputsOf(nd, contracts)
            val outs = LcncTypeCheck.outputsOf(nd, contracts)
            val isEffect = c?.isEffect == true
            val sp = pathOf[nd.id] ?: emptyList()
            val nodeWidth = nd.width ?: 200.0

            if (nd.type == LcncContracts.SCOPE_IN) {
                val name = nd.params["name"].orEmpty()
                val label = when {
                    nd.params.containsKey("default") -> "Default binding: ${nd.params["default"]}"
                    sp.isEmpty() -> "Caller input: $name"
                    else -> "Enclosing frame binding: $name"
                }
                verdicts.add(LcncTreeShakeVerdict(nd.id, "out", "value", null, "binding", label))
            }

            for ((idx, ip) in ins.withIndex()) {
                val b = bare(ip)
                if (PortKey(nd.id, b) in fedIn) continue
                val req = !ip.endsWith("?")
                val kind = LcncTypeCheck.portKind(nd, "in", ip, contracts, facts)
                // A guessed named wire would override the caller's argument map.
                if (!specimen && nd.type == LcncContracts.SCOPE && b != "args" && b != "when" && PortKey(nd.id, "args") in fedIn) {
                    verdicts.add(LcncTreeShakeVerdict(nd.id, "in", ip, kind.kind, "binding",
                        "Argument map connected; '$b' is checked at run time"))
                    continue
                }
                if (!req && !options.includeOptional && !specimen) {
                    verdicts.add(LcncTreeShakeVerdict(nd.id, "in", ip, kind.kind, "optional",
                        "Optional input left unchanged"))
                    continue
                }
                val px = nd.x
                val py = nd.y + 25.0 + idx * 20.0
                openIns.add(OpenIn(nd, ip, req, isEffect, kind, sp, px, py))
            }

            for ((idx, op) in outs.withIndex()) {
                val b = bare(op)
                if (PortKey(nd.id, b) in usedOut) continue
                val kind = LcncTypeCheck.portKind(nd, "out", op, contracts, facts)
                val px = nd.x + nodeWidth
                val py = nd.y + 25.0 + idx * 20.0
                openOuts.add(OpenOut(nd, op, kind, sp, px, py))
            }
        }

        require(!specimen || openIns.size + openOuts.size <= MAX_SPECIMEN_SOCKETS) {
            "Wiring specimen exceeds $MAX_SPECIMEN_SOCKETS open sockets"
        }
        val pairs = ArrayList<CandidatePair>()
        for (i in openIns) {
            for (o in openOuts) {
                if (o.nd.id == i.nd.id) continue
                if (!facts.accepts(o.kind, i.kind)) continue
                // Scope boundary: lateral or inward only
                val inScope = o.sp.size <= i.sp.size && o.sp.indices.all { idx -> i.sp[idx] == o.sp[idx] }
                if (!inScope) continue

                val d = hypot(o.x - i.x, o.y - i.y)
                if (!specimen && d > options.reach) continue
                pairs.add(CandidatePair(i, o, d))
            }
        }

        // Required inputs first, then nearest
        pairs.sortWith(
            compareBy<CandidatePair> { if (it.i.isRequired) 0 else 1 }
                .thenBy { it.dist },
        )

        val tookIn = HashSet<Pair<String, String>>()
        val tookOut = HashSet<Pair<String, String>>()
        val made = ArrayList<LcncWire>()

        val proposals = if (specimen) validatedMatching(program, pairs, openIns, contracts, facts) else pairs
        for (pr in proposals) {
            if (pr.i.isEffect && !specimen) continue // executable graphs still require explicit effect wiring
            val ik = pr.i.nd.id to bare(pr.i.port)
            val ok = pr.o.nd.id to bare(pr.o.port)
            if (ik in tookIn || ok in tookOut) continue
            tookIn.add(ik)
            tookOut.add(ok)
            made.add(LcncWire(pr.o.nd.id, pr.o.port, pr.i.nd.id, pr.i.port))
        }

        val stillOpen = openIns.filter { (it.nd.id to bare(it.port)) !in tookIn }

        for (pr in proposals.filter { (it.i.nd.id to bare(it.i.port)) in tookIn && (it.o.nd.id to bare(it.o.port)) in tookOut }) {
            if (made.any { it.fromNode == pr.o.nd.id && it.fromPort == pr.o.port && it.toNode == pr.i.nd.id && it.toPort == pr.i.port }) {
                verdicts.add(
                    LcncTreeShakeVerdict(
                        nodeId = pr.i.nd.id,
                        dir = "in",
                        port = pr.i.port,
                        kind = pr.i.kind.kind,
                        status = "ok",
                        label = "closed by ${pr.o.nd.type}.${pr.o.port}",
                    ),
                )
            }
        }

        fun kindMates(i: OpenIn) = openOuts.filter { it.nd.id != i.nd.id && facts.accepts(it.kind, i.kind) }
        fun inScope(o: OpenOut, i: OpenIn) = o.sp.size <= i.sp.size && o.sp.indices.all { idx -> i.sp[idx] == o.sp[idx] }

        val dead = stillOpen.filter { kindMates(it).isEmpty() }
        val scoped = stillOpen.filter {
            val km = kindMates(it)
            km.isNotEmpty() && km.none { o -> inScope(o, it) }
        }
        val reachable = stillOpen.filter {
            val km = kindMates(it)
            km.any { o -> inScope(o, it) }
        }

        for (i in reachable) {
            verdicts.add(
                LcncTreeShakeVerdict(
                    nodeId = i.nd.id,
                    dir = "in",
                    port = i.port,
                    kind = i.kind.kind,
                    status = "open",
                    label = when {
                        i.isEffect -> "Effect input requires an explicit connection"
                        pairs.any { it.i == i } -> "Compatible output assigned elsewhere; connect explicitly to share it"
                        else -> "Compatible output beyond ${options.reach.toInt()} units; move it closer or connect explicitly"
                    },
                ),
            )
        }

        for (i in scoped) {
            verdicts.add(
                LcncTreeShakeVerdict(
                    nodeId = i.nd.id,
                    dir = "in",
                    port = i.port,
                    kind = i.kind.kind,
                    status = "scope",
                    label = "kind-compatible producers exist, but outside ring boundary — data flows lateral or inward",
                ),
            )
        }

        for (i in dead) {
            verdicts.add(
                LcncTreeShakeVerdict(
                    nodeId = i.nd.id,
                    dir = "in",
                    port = i.port,
                    kind = i.kind.kind,
                    status = "dead",
                    label = if (parent == null) "no kind-compatible mate anywhere on this board"
                        else "no kind-compatible mate inside selected parent ${parent.id}; connect an external source explicitly",
                ),
            )
        }

        // Outlet blocked
        val allIns = ArrayList<OpenIn>()
        for (nd in allNodes) {
            val ins = LcncTypeCheck.inputsOf(nd, contracts)
            val sp = pathOf[nd.id] ?: emptyList()
            for (ip in ins) {
                val kind = LcncTypeCheck.portKind(nd, "in", ip, contracts, facts)
                allIns.add(OpenIn(nd, ip, !ip.endsWith("?"), contracts[nd.type]?.isEffect == true, kind, sp, 0.0, 0.0))
            }
        }

        var outletBlocked = 0
        for (o in openOuts) {
            if (o.sp.isEmpty()) continue
            val consumers = allIns.filter { it.nd.id != o.nd.id && facts.accepts(o.kind, it.kind) }
            if (consumers.isEmpty()) continue
            if (consumers.any { inScope(o, it) }) continue
            verdicts.add(
                LcncTreeShakeVerdict(
                    nodeId = o.nd.id,
                    dir = "out",
                    port = o.port,
                    kind = o.kind.kind,
                    status = "scope",
                    label = "outlet blocked — every consumer of this yield is outside the ring",
                ),
            )
            outletBlocked++
        }

        // Starved reach: downstream from still-open REQUIRED holes
        val starvedSeed = stillOpen.filter { it.isRequired }.map { it.nd.id }.toSet()
        val allWires = existingWires.snapshot()
        for (w in made) allWires.add(w)
        val starved = HashSet<String>(starvedSeed)
        var grew = true
        while (grew) {
            grew = false
            for (w in allWires) {
                if (w.fromNode in starved && w.toNode !in starved) {
                    starved.add(w.toNode)
                    grew = true
                }
            }
        }

        val updatedWires = allWires.freeze()
        val updatedProgram = program.copy(wires = updatedWires)
        if (specimen) {
            val violations = LcncTypeCheck.check(updatedProgram, contracts)
            require(violations.isEmpty()) { "Wiring specimen failed type validation: $violations" }
        }
        for (w in made) {
            fedIn.add(PortKey(w.toNode, bare(w.toPort)))
            usedOut.add(PortKey(w.fromNode, bare(w.fromPort)))
        }
        var socketCount = 0
        var connectedSocketCount = 0
        for (nd in selectedNodes) {
            for (port in LcncTypeCheck.inputsOf(nd, contracts)) {
                socketCount++
                if (PortKey(nd.id, bare(port)) in fedIn) connectedSocketCount++
            }
            for (port in LcncTypeCheck.outputsOf(nd, contracts)) {
                socketCount++
                if (PortKey(nd.id, bare(port)) in usedOut) connectedSocketCount++
            }
        }

        return LcncTreeShakeResult(
            program = updatedProgram,
            made = made,
            verdicts = verdicts,
            starvedNodeIds = starved,
            outletBlockedCount = outletBlocked,
            parentId = options.parentId,
            socketCount = socketCount,
            connectedSocketCount = connectedSocketCount,
        )
    }
}
