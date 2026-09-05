package borg.trikeshed.lcnc

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size

/**
 * The patch type system, with ONE authority.
 *
 * panels.html refuses a kind-mismatched drag at the port (`kindsCompatible`) —
 * but the canvas is not the authority: a stored panel, an imported document, a
 * Kotlin-authored preset, or a POST straight to `/api/lcnc/run` never passes
 * that check, and the daemon executed them anyway. The editor's goodwill is not
 * a type system. This is the same rule, stated once, on the side that decides.
 *
 * CABLES ARE NEVER UNTYPED ([LcncKinds]). The rules, all drawn from
 * [LcncContracts] — never invented here:
 *  - every node type is in the vocabulary,
 *  - every wire names real nodes and DECLARED ports,
 *  - a cable's type is its source port's type and the sink must be exactly
 *    that type ([LcncFacts.accepts]); a sink whose runner declares `Any` is
 *    exact too. An untyped port on either side is a violation, not a
 *    wildcard, because untyped is how garbage crosses,
 *  - a ring port that declared nothing takes the type of the cable plugged
 *    into it, and every cable inside the ring obeys that type
 *    ([Resolver]) — the surface need not show it, it must obey it,
 *  - data flows lateral or inward only; yields leave a ring through `scope.out`.
 *
 * Rings are node-aware, exactly as the canvas renders them: an inline ring's
 * real ports are its `scope.in`/`scope.out` children, plus the declared
 * `args?`/`when?` envelope and the composed `returns`.
 */
object LcncTypeCheck {

    /** One failed rule, in the vocabulary a canvas can render. */
    data class Violation(
        val rule: String,
        val fromNode: String,
        val fromPort: String,
        val toNode: String,
        val toPort: String,
        val detail: String,
    ) {
        fun render(): String = "$rule: $fromNode.$fromPort -> $toNode.$toPort ($detail)"
        fun toMap(): Map<String, Any?> = linkedMapOf(
            "rule" to rule, "fromNode" to fromNode, "fromPort" to fromPort,
            "toNode" to toNode, "toPort" to toPort, "detail" to detail,
        )
    }

    /**
     * Every violation in [program], rings included; empty means it type-checks.
     *
     * [strict] is the difference between authoring and running. Strict (the
     * presets/authoring gate) also reports a node whose type is outside the
     * vocabulary. Non-strict (the run seam) does not: a runner registry may
     * legitimately carry a type the contract table does not describe, and
     * `LcncRunner` throws `LcncUnknownNodeType` for one that is genuinely
     * absent. Either way an untyped node's ports are UNRESOLVED — a checker
     * that invented ports for a type it cannot see would reject working
     * programs, which is how a type system loses its welcome.
     */
    fun check(
        program: LcncProgram,
        contracts: Map<String, LcncPortContract> = LcncContracts.all().associateBy { it.type },
        strict: Boolean = true,
    ): List<Violation> {
        val out = ArrayList<Violation>()
        val ix = index(program)
        for (id in ix.duplicates) out.add(Violation("duplicate-node-id", id, "", id, "", "id '$id' declared twice"))
        if (strict) for (n in ix.nodes) if (n.type !in contracts) {
            out.add(Violation("unknown-type", n.id, "", n.id, "", "type '${n.type}' is not in the vocabulary"))
        }
        // The vocabulary as tuples, once per check; every kind question below is a query.
        val facts = LcncFacts.of(contracts.values)
        val resolver = Resolver(ix.byId, ix.pathOf, ix.wires, contracts, facts)

        for (w in ix.wires) {
            val from = ix.byId[w.fromNode]
            val to = ix.byId[w.toNode]
            if (from == null || to == null) {
                out.add(Violation(
                    "missing-node", w.fromNode, w.fromPort, w.toNode, w.toPort,
                    if (from == null) "no node '${w.fromNode}'" else "no node '${w.toNode}'",
                ))
                continue
            }
            // A node whose ports cannot be known: an untyped type, or a NAMED ring
            // whose ports are declared by the body the loader fetches at run time
            // (the canvas says the same: "a named ring defers to the daemon").
            val fromOpaque = isOpaque(from, contracts)
            val toOpaque = isOpaque(to, contracts)
            val outs = outputsOf(from, contracts)
            val ins = inputsOf(to, contracts)
            val fromPort = w.fromPort.removeSuffix("?")
            val toPort = w.toPort.removeSuffix("?")
            if (!fromOpaque && outs.none { it.removeSuffix("?") == fromPort }) {
                out.add(Violation(
                    "undeclared-port", w.fromNode, w.fromPort, w.toNode, w.toPort,
                    "${from.type} declares no output '$fromPort'",
                ))
                continue
            }
            if (!toOpaque && ins.none { it.removeSuffix("?") == toPort }) {
                out.add(Violation(
                    "undeclared-port", w.fromNode, w.fromPort, w.toNode, w.toPort,
                    "${to.type} declares no input '$toPort'",
                ))
                continue
            }
            val sk = if (fromOpaque) PortKind(null, generic = true) else resolver.kind(from, "out", fromPort)
            val dk = if (toOpaque) PortKind(null, generic = true) else resolver.kind(to, "in", toPort)
            if (!facts.accepts(sk, dk)) {
                out.add(Violation(
                    "kind-mismatch", w.fromNode, w.fromPort, w.toNode, w.toPort,
                    "${from.type}.$fromPort emits ${sk.render()}, ${to.type}.$toPort wants ${dk.render()}",
                ))
                continue
            }
            // The runner's own scope rule, stated before the run instead of thrown during it.
            val sp = ix.pathOf[w.fromNode].orEmpty()
            val dp = ix.pathOf[w.toNode].orEmpty()
            val lateralOrInward = sp.size <= dp.size && sp.indices.all { dp.getOrNull(it) == sp[it] }
            if (!lateralOrInward) {
                out.add(Violation(
                    "ring-boundary", w.fromNode, w.fromPort, w.toNode, w.toPort,
                    "data flows lateral or inward — a yield leaves a ring through scope.out",
                ))
            }
        }
        return out
    }

    /**
     * The EXACT type each cable carries, in wire order — what goes on the
     * blackboard beside the program ([LcncBlackboard.programEntry]). Null where
     * nothing can be resolved: an opaque source, or an unresolved ring port.
     */
    fun cableTypes(
        program: LcncProgram,
        contracts: Map<String, LcncPortContract> = LcncContracts.all().associateBy { it.type },
    ): List<String?> {
        val ix = index(program)
        val resolver = Resolver(ix.byId, ix.pathOf, ix.wires, contracts, LcncFacts.of(contracts.values))
        return ix.wires.map { w ->
            val from = ix.byId[w.fromNode] ?: return@map null
            if (isOpaque(from, contracts)) null else resolver.kind(from, "out", w.fromPort.removeSuffix("?")).kind
        }
    }

    /** A ring's outputs are its body's `scope.out` names plus the composed `returns`. */
    fun outputsOf(n: LcncNode, contracts: Map<String, LcncPortContract>): List<String> =
        if (isRing(n)) ringPorts(n, LcncContracts.SCOPE_OUT) + "returns"
        else contracts[n.type]?.outputs.orEmpty()

    /** A ring's inputs are the `args?`/`when?` envelope plus its body's `scope.in` names. */
    fun inputsOf(n: LcncNode, contracts: Map<String, LcncPortContract>): List<String> =
        if (isRing(n)) listOf("args?", "when?") + ringPorts(n, LcncContracts.SCOPE_IN)
        else contracts[n.type]?.inputs.orEmpty()

    /**
     * A port's type: a resolved [kind], or [generic] — UNRESOLVED: a ring port
     * that has declared nothing and, in this program, has no cable to take a
     * type from. Unresolved is not "accepts anything": nothing can be claimed
     * about it yet, so no violation is raised on it. Untyped (neither) is a
     * vocabulary bug and stays a violation, because untyped is how garbage
     * crosses. Whether one port's type is accepted by another's is
     * [LcncFacts.accepts] — a query, not a method here.
     */
    data class PortKind(val kind: String?, val generic: Boolean) {
        fun render(): String = kind ?: if (generic) "unresolved" else "untyped"
    }

    /** Resolves without a program's cables — a declared or literal port, or an unresolved ring port. */
    fun portKind(n: LcncNode, dir: String, port: String, contracts: Map<String, LcncPortContract>): PortKind =
        portKind(n, dir, port, contracts, LcncFacts.of(contracts.values))

    fun portKind(n: LcncNode, dir: String, port: String, contracts: Map<String, LcncPortContract>, facts: LcncFacts): PortKind =
        Resolver(mapOf(n.id to n), emptyMap(), emptyList(), contracts, facts).kind(n, dir, port.removeSuffix("?"))

    /** Shared, memoized resolution for candidate queries against one wired graph. */
    internal fun portKindResolver(
        program: LcncProgram, contracts: Map<String, LcncPortContract>, facts: LcncFacts,
    ): (LcncNode, String, String) -> PortKind {
        val ix = index(program)
        val resolver = Resolver(ix.byId, ix.pathOf, ix.wires, contracts, facts)
        return { node, dir, port -> resolver.kind(node, dir, port.removeSuffix("?")) }
    }

    /** One program's nodes (walk order), ids, ring paths, wires, and duplicate ids. */
    private class Index(
        val nodes: List<LcncNode>,
        val byId: Map<String, LcncNode>,
        val pathOf: Map<String, List<String>>,
        val wires: List<LcncWire>,
        val duplicates: List<String>,
    )

    private fun index(program: LcncProgram): Index {
        val nodes = ArrayList<LcncNode>()
        val byId = LinkedHashMap<String, LcncNode>()
        val pathOf = LinkedHashMap<String, List<String>>()
        val duplicates = ArrayList<String>()
        fun walk(ns: Series<LcncNode>, path: List<String>) {
            for (i in 0 until ns.size) {
                val n = ns[i]
                nodes.add(n)
                if (byId.put(n.id, n) != null) duplicates.add(n.id)
                pathOf[n.id] = path
                if (n.children.size > 0) walk(n.children, path + n.id)
            }
        }
        walk(program.nodes, emptyList())
        val wires = (0 until program.wires.size).map { program.wires[it] }
        return Index(nodes, byId, pathOf, wires, duplicates)
    }

    /**
     * Port types in ONE program, cables included. A declared port is its
     * declared kind (a literal, what its value matches). A ring port that
     * declared nothing is the type of the first cable plugged into it — a
     * parameter from the cable into the ring, a yield from the cable into its
     * `scope.out` — and the `scope.in`/`scope.out` bindings inside the ring
     * carry that same type. Memoised; a cycle resolves nothing.
     */
    private class Resolver(
        private val byId: Map<String, LcncNode>,
        private val pathOf: Map<String, List<String>>,
        private val wires: List<LcncWire>,
        private val contracts: Map<String, LcncPortContract>,
        private val facts: LcncFacts,
    ) {
        private val memo = HashMap<String, PortKind>()
        private val visiting = HashSet<String>()
        private val unresolved = PortKind(null, generic = true)

        fun kind(n: LcncNode, dir: String, port: String): PortKind {
            val key = "${n.id}/$dir/$port"
            memo[key]?.let { return it }
            if (!visiting.add(key)) return unresolved
            val k = resolve(n, dir, port)
            visiting.remove(key)
            memo[key] = k
            return k
        }

        /**
         * The type of the cable into (nodeId, port). With several cables the LAST
         * typed one wins — the runtime's scalar ports are last-write-wins
         * (LcncRunner.gather) — and each of those cables is still checked against it.
         */
        private fun feederOf(nodeId: String, port: String): PortKind? {
            var found: PortKind? = null
            for (w in wires) {
                if (w.toNode != nodeId || w.toPort.removeSuffix("?") != port) continue
                val src = byId[w.fromNode] ?: continue
                val k = kind(src, "out", w.fromPort.removeSuffix("?"))
                if (found == null || !k.generic) found = k
            }
            return found
        }

        private fun childOf(ring: LcncNode, childType: String, name: String): LcncNode? {
            for (i in 0 until ring.children.size) {
                val c = ring.children[i]
                if (c.type == childType && c.params["name"]?.removeSuffix("?") == name) return c
            }
            return null
        }

        private fun resolve(n: LcncNode, dir: String, port: String): PortKind {
            // A node the vocabulary cannot see — a composite referenced by type, a
            // type the table lacks — is opaque here exactly as it is at the wire:
            // UNRESOLVED, never untyped, so it cannot leak a false mismatch through
            // a ring parameter or a type variable.
            if (n.type !in contracts && !isRing(n)) return unresolved
            // The bindings: a `scope.in`'s value IS the ring parameter of that name;
            // a `scope.out`'s value takes what is plugged into it. Their contract
            // rows carry a `json` placeholder that must not type either.
            if (n.type == LcncContracts.SCOPE_IN && dir == "out" && port == "value") {
                n.params["kind"]?.takeIf { it.isNotBlank() }?.let { return PortKind(it, generic = false) }
                val name = n.params["name"]?.removeSuffix("?") ?: return unresolved
                // The runtime resolves a name OUTWARD through the frame chain — a ring
                // whose parameter of that name is BOUND (fed) shadows the rings outside
                // it; an unfed one is skipped (LcncScopeFrame.hasBinding walks parent).
                // So does the type.
                for (ringId in pathOf[n.id].orEmpty().asReversed()) {
                    val ring = byId[ringId] ?: continue
                    if (childOf(ring, LcncContracts.SCOPE_IN, name) == null) continue
                    val k = kind(ring, "in", name)
                    if (!k.generic) return k
                }
                return unresolved
            }
            if (n.type == LcncContracts.SCOPE_OUT && dir == "in" && port == "value") {
                n.params["kind"]?.takeIf { it.isNotBlank() }?.let { return PortKind(it, generic = false) }
                return unresolved
            }
            val contract = contracts[n.type]
            val declared = contract?.let { if (dir == "out") it.outputKinds[port] else it.inputKinds[port] }
            if (declared != null) {
                // A composite's formal that declared nothing (LcncComposites spells it `*`).
                if (declared == LcncKinds.UNRESOLVED) return unresolved
                // A type variable — coalesce<T>(a?: T, b: T): T — is fixed per node by
                // the cable into any of its T ports; every other T port, and a T
                // output, is that type. No typed cable yet: unresolved, never a wildcard.
                if (LcncKinds.isTypeVariable(declared) && contract != null) {
                    for ((p, k) in contract.inputKinds) if (k == declared) {
                        val fixed = feederOf(n.id, p) ?: continue
                        if (!fixed.generic) return fixed
                    }
                    return unresolved
                }
                // An authored JSON literal is what its rows match — the curator's facts
                // ARE turn facts. A text literal is text however its value looks.
                if (dir == "out" && contract != null && declared == "json" && LcncKinds.isLiteral(contract, port)) {
                    return PortKind(facts.refineLiteral(n, port, declared), generic = false)
                }
                return PortKind(declared, generic = false)
            }
            if (!isRing(n)) return PortKind(null, generic = false)
            // A ring's per-name port: the kind its scope.in/scope.out child declares,
            // else the type of the cable that fixes it.
            val childType = if (dir == "out") LcncContracts.SCOPE_OUT else LcncContracts.SCOPE_IN
            val child = childOf(n, childType, port)
            child?.params?.get("kind")?.takeIf { it.isNotBlank() }?.let { return PortKind(it, generic = false) }
            return if (dir == "in") feederOf(n.id, port) ?: unresolved
            else child?.let { feederOf(it.id, "value") } ?: unresolved
        }
    }

    private fun isRing(n: LcncNode): Boolean =
        n.children.size > 0 || !n.subprogram.isNullOrBlank() || !n.params["program"].isNullOrBlank()

    /** A named ring (body loaded at run time) or a type outside the contract table. */
    private fun isOpaque(n: LcncNode, contracts: Map<String, LcncPortContract>): Boolean =
        n.type !in contracts || (n.children.size == 0 && isRing(n))

    private fun ringPorts(n: LcncNode, childType: String): List<String> {
        val names = ArrayList<String>()
        for (i in 0 until n.children.size) {
            val c = n.children[i]
            if (c.type == childType) c.params["name"]?.let { names.add(it) }
        }
        return names
    }
}
