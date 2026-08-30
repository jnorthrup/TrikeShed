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
 * The rules, all drawn from [LcncContracts] — never invented here:
 *  - every node type is in the vocabulary,
 *  - every wire names real nodes and DECLARED ports,
 *  - kinds match exactly (the daemon's mating rule: `outputKinds[from] ==
 *    inputKinds[to]`) — an untyped port on either side is a violation, not a
 *    wildcard, because untyped is how garbage crosses,
 *  - data flows lateral or inward only; yields leave a ring through `scope.out`.
 *
 * Rings are node-aware, exactly as the canvas renders them: an inline ring's
 * real ports are its `scope.in`/`scope.out` children (json-kinded), plus the
 * declared `args?`/`when?` envelope and the composed `returns`.
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
     * absent. Either way an untyped node's ports are GENERIC — a checker that
     * invented ports for a type it cannot see would reject working programs,
     * which is how a type system loses its welcome.
     */
    fun check(
        program: LcncProgram,
        contracts: Map<String, LcncPortContract> = LcncContracts.all().associateBy { it.type },
        strict: Boolean = true,
    ): List<Violation> {
        val out = ArrayList<Violation>()
        val byId = LinkedHashMap<String, LcncNode>()
        val pathOf = LinkedHashMap<String, List<String>>()

        fun walk(nodes: Series<LcncNode>, path: List<String>) {
            for (i in 0 until nodes.size) {
                val n = nodes[i]
                if (byId.put(n.id, n) != null) {
                    out.add(Violation("duplicate-node-id", n.id, "", n.id, "", "id '${n.id}' declared twice"))
                }
                pathOf[n.id] = path
                if (strict && n.type !in contracts) {
                    out.add(Violation("unknown-type", n.id, "", n.id, "", "type '${n.type}' is not in the vocabulary"))
                }
                if (n.children.size > 0) walk(n.children, path + n.id)
            }
        }
        walk(program.nodes, emptyList())

        for (i in 0 until program.wires.size) {
            val w = program.wires[i]
            val from = byId[w.fromNode]
            val to = byId[w.toNode]
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
            val sk = if (fromOpaque) PortKind(null, generic = true) else portKind(from, "out", fromPort, contracts)
            val dk = if (toOpaque) PortKind(null, generic = true) else portKind(to, "in", toPort, contracts)
            if (!sk.acceptedBy(dk)) {
                out.add(Violation(
                    "kind-mismatch", w.fromNode, w.fromPort, w.toNode, w.toPort,
                    "${from.type}.$fromPort emits ${sk.render()}, ${to.type}.$toPort wants ${dk.render()}",
                ))
                continue
            }
            // The runner's own scope rule, stated before the run instead of thrown during it.
            val sp = pathOf[w.fromNode].orEmpty()
            val dp = pathOf[w.toNode].orEmpty()
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

    /** A ring's outputs are its body's `scope.out` names plus the composed `returns`. */
    fun outputsOf(n: LcncNode, contracts: Map<String, LcncPortContract>): List<String> =
        if (isRing(n)) ringPorts(n, LcncContracts.SCOPE_OUT) + "returns"
        else contracts[n.type]?.outputs.orEmpty()

    /** A ring's inputs are the `args?`/`when?` envelope plus its body's `scope.in` names. */
    fun inputsOf(n: LcncNode, contracts: Map<String, LcncPortContract>): List<String> =
        if (isRing(n)) listOf("args?", "when?") + ringPorts(n, LcncContracts.SCOPE_IN)
        else contracts[n.type]?.inputs.orEmpty()

    /**
     * A port's type: a declared [kind], or [generic] — a ring parameter that has
     * not declared one. Generic is not "untyped": a frame binding really is
     * `Any?`, so a ring accepts any kind until its `scope.in`/`scope.out`
     * declares otherwise. Untyped (neither) is a vocabulary bug and stays a
     * violation, because untyped is how garbage crosses.
     */
    data class PortKind(val kind: String?, val generic: Boolean) {
        fun render(): String = kind ?: if (generic) "generic" else "untyped"
        /** Exact equality, the daemon's mating rule — with generic as the ring's escape. */
        fun acceptedBy(other: PortKind): Boolean = when {
            generic || other.generic -> true
            kind == null || other.kind == null -> false
            else -> kind == other.kind
        }
    }

    fun portKind(n: LcncNode, dir: String, port: String, contracts: Map<String, LcncPortContract>): PortKind {
        val declared = contracts[n.type]
            ?.let { if (dir == "out") it.outputKinds[port] else it.inputKinds[port] }
        if (declared != null) return PortKind(declared, generic = false)
        if (!isRing(n)) return PortKind(null, generic = false)
        // A ring's per-name port: the kind its scope.in/scope.out child declares, else generic.
        val childType = if (dir == "out") LcncContracts.SCOPE_OUT else LcncContracts.SCOPE_IN
        for (i in 0 until n.children.size) {
            val c = n.children[i]
            if (c.type == childType && c.params["name"]?.removeSuffix("?") == port.removeSuffix("?")) {
                val k = c.params["kind"]?.takeIf { it.isNotBlank() }
                return PortKind(k, generic = k == null)
            }
        }
        return PortKind(null, generic = true)
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
