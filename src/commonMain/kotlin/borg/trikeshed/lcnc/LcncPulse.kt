package borg.trikeshed.lcnc

import borg.trikeshed.lib.*

/** Completed execution's data projection; trigger edges never enter the next transport. */
data class LcncRetainedFrame(
    val outputs: Map<String, Map<String, Any?>> = emptyMap(),
    val bindings: Map<String, Any?> = emptyMap(),
    /** The entire frame is unavailable; no partial projection can seed another pulse. */
    val unavailable: String? = null,
    /** One global occurrence table. Null denotes legacy data with no proof of its binding origins. */
    val origins: Map<String, Any?>? = null,
)

/**
 * One root control event over a retained root frame. The admission boundary verifies
 * the frame's completed receipt, definition and arguments. This is selection data
 * for [LcncRunner], not another executor or a browser-supplied output cache.
 * Rings selected by an inward wire execute as whole blocks in authored order.
 */
data class LcncPulse(
    val nodeId: String,
    val outputs: Map<String, Any?>,
    val retained: LcncRetainedFrame,
    /** Authenticated CID-pinned bodies, also used by the actual walk; admission never invokes a loader. */
    val definitions: Map<String, LcncProgram> = emptyMap(),
) {
    fun roots(program: LcncProgram, registeredRunnerTypes: Set<String> = emptySet(),
              pureRunnerTypes: Set<String> = emptySet()): Set<String> {
        require(retained.unavailable == null) { "pulse_state_unavailable" }
        val roots = program.nodes.view.associateBy { it.id }
        val source = requireNotNull(roots[nodeId]) { "pulse_requires_root_control" }
        require(source.children.size == 0 && source.subprogram == null) { "pulse_requires_root_control" }
        require(retained.outputs.keys.all { it in roots }) { "pulse_state_node_mismatch" }
        val ports = when (source.type) {
            "timer" -> emptySet()
            "button" -> setOf("press")
            "slider" -> setOf("value")
            "dom.board" -> setOf("move")
            "graal.events", "vm.events", "dom.events" -> setOf("event")
            "media.player" -> setOf("state", "ended")
            else -> throw IllegalArgumentException("pulse_requires_control_source")
        }
        require(if (source.type == "media.player") "state" in outputs && outputs.keys.all { it in ports }
            else outputs.keys == ports) { "pulse_output_ports_mismatch" }
        when (source.type) {
            "button" -> require(number("press") >= 0.0) { "invalid_pulse_press" }
            "slider" -> number("value")
            "dom.board" -> {
                val move = outputs["move"] as? Map<*, *>
                require(move != null && move["itemId"] != null && move["from"] is String && move["to"] is String) {
                    "invalid_pulse_move"
                }
            }
            "graal.events", "vm.events" -> require(outputs["event"] != null) { "invalid_pulse_event" }
            "dom.events" -> LcncDomForm.event(outputs["event"], nodeId, roots, retained)
            "media.player" -> {
                require(outputs["state"] is Map<*, *> && ("ended" !in outputs || number("ended") > 0.0)) { "invalid_pulse_media" }
            }
        }
        val owner = mutableMapOf<String, String>()
        fun index(nodes: Series<LcncNode>, root: String) {
            for (node in nodes.view) {
                require(owner.put(node.id, root) == null) { "duplicate node id '${node.id}'" }
                index(node.children, root)
            }
        }
        for (node in program.nodes.view) index(s_[node], node.id)
        val selected = mutableSetOf(nodeId)
        val emitted = if (source.type == "timer") setOf("tick") else outputs.keys
        var changed: Boolean
        do {
            changed = false
            for (wire in program.wires.view) {
                if (wire.fromNode == nodeId && wire.fromPort !in emitted) continue
                val from = owner[wire.fromNode]
                val to = owner[wire.toNode]
                if (from in selected && to != null && selected.add(to)) changed = true
            }
        } while (changed)
        // Yields are pure frame projections; retaining their empty output map would
        // lose the procedure's returns. Re-gather without dispatching a runner.
        for (node in program.nodes.view) if (node.type == LcncContracts.SCOPE_OUT) selected.add(node.id)
        bindingOrigins(program, selected, owner, registeredRunnerTypes, pureRunnerTypes)
        return selected
    }

    /** Proof can repair the next frame after a pure walk. Effects still require pre-effect refusal. */
    private fun bindingOrigins(program: LcncProgram, selected: Set<String>, owner: Map<String, String>,
                               registeredRunnerTypes: Set<String>, pureRunnerTypes: Set<String>) {
        if (retained.bindings.isEmpty()) return
        if (retained.origins != null && pure(program, selected, registeredRunnerTypes, pureRunnerTypes)) return
        val contracts = LcncContracts.all().associateBy { it.type }
        val kind = LcncTypeCheck.portKindResolver(program, contracts, LcncFacts.of(contracts.values))
        val wiresTo = program.wires.view.groupBy { it.toNode }
        fun fed(wire: LcncWire): Boolean = when {
            wire.fromNode == nodeId -> outputs.containsKey(wire.fromPort)
            owner[wire.fromNode] !in selected -> retained.outputs[wire.fromNode]?.containsKey(wire.fromPort) == true
            else -> false
        }
        fun inspect(node: LcncNode, shadowed: Set<String>) {
            if (node.type == LcncContracts.SCOPE_IN) {
                val name = node.params["name"]?.removeSuffix("?")
                require(name == null || name !in retained.bindings || name in shadowed || !event(kind(node, "out", "value").kind)) {
                    BINDING_ORIGIN_UNAVAILABLE
                }
                return
            }
            if (node.type == LcncContracts.SCOPE_OUT) return
            val fedNames = wiresTo[node.id].orEmpty().filter(::fed).mapNotNull { wire ->
                wire.toPort.removeSuffix("?").takeUnless { it == "args" || it == "when" }
            }
            val local = shadowed + fedNames
            val iteration = if ("each" in fedNames) local + (node.params["item"]?.takeIf { it.isNotBlank() } ?: "item") else local
            if (node.children.size > 0) {
                for (child in node.children.view) inspect(child, iteration)
            } else if (node.subprogram != null ||
                node.type == LcncContracts.SCOPE && !node.params["program"].isNullOrBlank() ||
                node.type !in contracts && node.type !in registeredRunnerTypes) {
                require(retained.bindings.keys.all { it in iteration }) { BINDING_ORIGIN_UNAVAILABLE }
            }
        }
        for (node in program.nodes.view) if (node.id in selected) inspect(node, emptySet())
    }

    /** Only known pure projections and structural rings can retract aliases after execution safely. */
    private fun pure(program: LcncProgram, selected: Set<String>, registered: Set<String>, runners: Set<String>): Boolean {
        var work = 0
        fun inspect(node: LcncNode, depth: Int): Boolean {
            if (++work > 65536 || depth > 16) return false
            if (node.children.size > 0) return node.children.view.all { inspect(it, depth + 1) }
            if (node.type == LcncContracts.SCOPE_IN || node.type == LcncContracts.SCOPE_OUT) return true
            val name = node.subprogram
                ?: node.params["program"]?.takeIf { node.type == LcncContracts.SCOPE && it.isNotBlank() }
                ?: node.type.takeIf { it !in registered && LcncContracts.find(it) == null }
            if (name != null) {
                val body = definitions[name] ?: return false
                return !body.controls.inspectionOnly && body.nodes.view.all { inspect(it, depth + 1) }
            }
            return node.type in runners
        }
        return program.nodes.view.all { it.id !in selected || inspect(it, 0) }
    }

    /** Absence of an event port cannot inherit a prior transport's trigger. */
    fun sourceOutput(node: LcncNode, produced: Map<String, Any?>): Map<String, Any?> =
        (if (node.type == "media.player") produced - "ended" - "rendered" else produced) + outputs

    companion object {
        const val BINDING_ORIGIN_UNAVAILABLE = "pulse_binding_origin_unavailable"

        internal fun event(kind: String?): Boolean = kind == "trigger" ||
            kind != null && kind.startsWith("List<") && kind.endsWith(">") &&
                event(kind.substring(5, kind.length - 1))

        /** Only port taxonomy lives here. Structured yields are projected by the actual walk. */
        internal fun event(node: LcncNode, port: String, kind: String?): Boolean =
            event(kind) || node.type == "dom.board" && port == "move" ||
                node.type == "media.player" && port == "rendered" ||
                node.type in setOf("graal.events", "vm.events", "dom.events") && port == "event"
    }

    private fun number(port: String): Double = requireNotNull((outputs[port] as? Number)?.toDouble()
        ?.takeIf { it.isFinite() }) { "invalid_pulse_$port" }
}
