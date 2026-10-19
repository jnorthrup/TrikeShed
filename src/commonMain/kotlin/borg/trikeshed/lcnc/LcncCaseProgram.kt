package borg.trikeshed.lcnc

import borg.trikeshed.kanban.KanbanCondition
import borg.trikeshed.kanban.KanbanEdge
import borg.trikeshed.kanban.KanbanEdgeMode
import borg.trikeshed.kanban.KanbanLane
import borg.trikeshed.lib.*

enum class LcncCaseScope(val id: String, val lane: String, val binding: String) {
    ARGUE_INITIAL("case.argue", "argue", "__case_argue"),
    ARGUE_REPEAT("case.argue.repeat", "argue", "__case_argue_repeat"),
    REBUT("case.rebut", "rebut", "__case_rebut"),
    DELIBERATE("case.deliberate", "deliberate", "__case_deliberate"),
    RULE("case.rule", "rule", "__case_rule"),
}

/** Authored scopes use ordinary LCNC guards and yields; the host selects their root bindings. */
object LcncCaseProgram {
    const val BINDING_PREFIX = "__case_"
    const val initialLane = "brief"
    private val lanes = s_["argue", "rebut", "deliberate"]
    private val ports = s_["prompt", "content", "model"]
    private fun kind(port: String): String = if (port == "model") "id" else "text"
    private fun scopes(repeatArgue: Boolean): Series<LcncCaseScope> = if (repeatArgue)
        s_[LcncCaseScope.ARGUE_INITIAL, LcncCaseScope.ARGUE_REPEAT, LcncCaseScope.REBUT, LcncCaseScope.DELIBERATE]
    else s_[LcncCaseScope.ARGUE_INITIAL, LcncCaseScope.REBUT, LcncCaseScope.DELIBERATE]

    fun arguments(inputs: Map<String, Any?>, lane: String, repeatArgue: Boolean = false): Map<String, Any?> {
        require(inputs.keys.none { it.startsWith(BINDING_PREFIX) }) { "reserved case input" }
        if (lane == "rule") {
            require(!repeatArgue) { "repeated case scope must argue" }
            return inputs + scopes(true).view.associate { it.binding to false } + (LcncCaseScope.RULE.binding to true)
        }
        require(lane in lanes.view) { "unsupported case scope: $lane" }
        if (repeatArgue) {
            require(lane == "argue") { "repeated case scope must argue" }
            return inputs + scopes(true).view.associate { it.binding to (it == LcncCaseScope.ARGUE_REPEAT) }
        }
        return inputs + lanes.view.associate { (BINDING_PREFIX + it) to (it == lane) }
    }

    fun scope(program: LcncProgram, lane: String, repeatArgue: Boolean = false): LcncNode {
        validate(program)
        val selected = when (lane) {
            "argue" -> if (repeatArgue) LcncCaseScope.ARGUE_REPEAT else LcncCaseScope.ARGUE_INITIAL
            "rebut" -> LcncCaseScope.REBUT
            "deliberate" -> LcncCaseScope.DELIBERATE
            "rule" -> LcncCaseScope.RULE
            else -> throw IllegalArgumentException("unsupported case scope: $lane")
        }
        require(!repeatArgue || lane == "argue") { "repeated case scope must argue" }
        return program.nodes.view.singleOrNull { it.id == selected.id }
            ?: throw IllegalArgumentException("case program lacks the repeated argument scope")
    }

    /** The same original seats, options and graph, with explicit independent scope boundaries. */
    fun authored(program: LcncProgram, name: String, repeatArgue: Boolean = false): LcncProgram {
        val original = program.nodes.view.associateBy { it.id }
        val roots = ArrayList<LcncNode>()
        val wires = ArrayList<LcncWire>()
        for (selected in scopes(repeatArgue).view) {
            val source = original.getValue(when (selected.lane) { "argue" -> "n2"; "rebut" -> "n3"; else -> "n4" })
            val seat = if (selected == LcncCaseScope.ARGUE_REPEAT) source.copy(id = "${selected.id}.seat") else source
            val prefix = selected.id
            val input = LcncNode("$prefix.in", LcncContracts.SCOPE_IN,
                params = mapOf("name" to if (selected == LcncCaseScope.ARGUE_INITIAL) "brief" else "record", "kind" to "text"))
            val body = ArrayList<LcncNode>()
            body.add(input)
            if (selected == LcncCaseScope.ARGUE_INITIAL && original.containsKey("n1")) {
                body.add(original.getValue("n1"))
                body.add(original.getValue("n1b"))
                wires.addAll(listOf(
                    LcncWire(input.id, "value", "n1", "text?"),
                    LcncWire("n1", "documentCid", "n1b", "documentCid?"),
                    LcncWire("n1", "brief", "n1b", "brief?"),
                    LcncWire("n1b", "brief", seat.id, "prompt?"),
                ))
            } else wires.add(LcncWire(input.id, "value", seat.id, "prompt?"))
            body.add(seat)
            for (port in ports.view) {
                val output = "$prefix.out.$port"
                body.add(LcncNode(output, LcncContracts.SCOPE_OUT, params = mapOf("name" to port, "kind" to kind(port))))
                wires.add(LcncWire(seat.id, port, output, "value"))
            }
            val guard = "$prefix.when"
            roots.add(LcncNode(guard, LcncContracts.SCOPE_IN,
                params = mapOf("name" to selected.binding, "default" to "false", "kind" to "json")))
            roots.add(LcncNode(prefix, LcncContracts.SCOPE, children = body.toSeries()))
            wires.add(LcncWire(guard, "value", prefix, "when?"))
            for (port in ports.view) {
                val output = "$prefix.yield.$port"
                roots.add(LcncNode(output, LcncContracts.SCOPE_OUT, params = mapOf("name" to port, "kind" to kind(port))))
                wires.add(LcncWire(prefix, port, output, "value"))
            }
        }
        if (repeatArgue) {
            val prefix = LcncCaseScope.RULE.id
            roots.add(LcncNode("$prefix.when", LcncContracts.SCOPE_IN, params = mapOf(
                "name" to LcncCaseScope.RULE.binding, "default" to "false", "kind" to "json")))
            roots.add(LcncNode(prefix, LcncContracts.SCOPE, children = s_[
                LcncNode("$prefix.in", LcncContracts.SCOPE_IN, params = mapOf("name" to "record", "kind" to "text")),
                LcncNode("$prefix.record", "tribunal.record"),
                LcncNode("$prefix.out.report", LcncContracts.SCOPE_OUT, params = mapOf("name" to "report", "kind" to "json")),
            ]))
            roots.add(LcncNode("$prefix.yield.report", LcncContracts.SCOPE_OUT,
                params = mapOf("name" to "report", "kind" to "json")))
            wires.addAll(listOf(
                LcncWire("$prefix.in", "value", "$prefix.record", "text?"),
                LcncWire("$prefix.record", "report", "$prefix.out.report", "value"),
                LcncWire("$prefix.when", "value", prefix, "when?"),
                LcncWire(prefix, "report", "$prefix.yield.report", "value"),
            ))
        }
        roots.add(LcncNode("case.note", "note", params = mapOf("text" to if (repeatArgue)
            "Separate first and repeated counsel scopes retain the original seat options. " +
                "The graph bounds counsel repetition at three and clarification at one; terminal settlement remains unsupported."
        else "Explicit case scopes for counsel, opposing counsel and judge. The host currently admits brief to argue only. " +
            "The remaining graph modes, clarification and terminal settlement are unfinished.")))
        val graph = if (repeatArgue) requireNotNull(program.kanban).let { current -> current.copy(
            lanes = (current.lanes.size + 1) j { index: Int -> if (index < current.lanes.size) current.lanes[index]
                else KanbanLane("rule", "Rule", current.lanes.view.maxOf { it.order } + 1, "judge") },
            edges = (current.edges.size + 1) j { index: Int -> if (index < current.edges.size) current.edges[index]
                else KanbanEdge("deliberate-rule", "deliberate", "rule",
                    condition = KanbanCondition(TribunalPredicates.RESOLVED_RULING)) },
        ) } else program.kanban
        return program.copy(name = name, nodes = roots.toSeries(), wires = wires.toSeries(), kanban = graph).also(::validate)
    }

    /** Validate the visible protocol itself, including every possible effect and every guard writer. */
    fun validate(program: LcncProgram) {
        fun valid(value: Boolean) { require(value) { "invalid authored case program" } }
        valid(!program.controls.inspectionOnly)
        val graph = requireNotNull(program.kanban) { "case program requires a graph" }
        valid(graph.lanes.view.any { it.id == initialLane })
        valid(graph.edges.view.any { it.from == initialLane && it.to == "argue" && it.mode == KanbanEdgeMode.DIRECT })
        val roots = program.nodes.view.associateBy { it.id }
        valid(roots.size == program.nodes.size)
        val allIds = roots.keys.toMutableSet()
        val expectedRoots = mutableSetOf<String>()
        val rootOrder = ArrayList<String>()
        val expectedWires = ArrayList<LcncWire>()
        val repeated = roots.containsKey(LcncCaseScope.ARGUE_REPEAT.id)
        for (selected in scopes(repeated).view) {
            val prefix = selected.id
            val guardId = "$prefix.when"
            expectedRoots.add(guardId)
            expectedRoots.add(prefix)
            rootOrder.add(guardId)
            rootOrder.add(prefix)
            val guard = requireNotNull(roots[guardId]) { "case scope lacks a false-default guard" }
            valid(guard.type == LcncContracts.SCOPE_IN && guard.params == mapOf(
                "name" to selected.binding, "default" to "false", "kind" to "json") &&
                guard.children.size == 0 && guard.subprogram == null)
            val scope = requireNotNull(roots[prefix]) { "case scope is missing" }
            valid(scope.type == LcncContracts.SCOPE && scope.params.isEmpty() && scope.subprogram == null)
            val body = scope.children
            valid(body.view.all { it.children.size == 0 && it.subprogram == null && allIds.add(it.id) })
            val seat = body.view.singleOrNull { it.type == "mux.chat" }
                ?: throw IllegalArgumentException("case scope must contain exactly one authored seat")
            valid(seat.params["job"] == selected.lane)
            val input = body.view.singleOrNull { it.type == LcncContracts.SCOPE_IN }
                ?: throw IllegalArgumentException("case scope must declare its source input")
            valid(input.params == mapOf("name" to if (selected == LcncCaseScope.ARGUE_INITIAL) "brief" else "record", "kind" to "text"))
            val effects = body.filter { it.type !in setOf("mux.chat", LcncContracts.SCOPE_IN, LcncContracts.SCOPE_OUT) }
            if (effects.size == 0) expectedWires.add(LcncWire(input.id, "value", seat.id, "prompt?"))
            else {
                valid(selected == LcncCaseScope.ARGUE_INITIAL && effects.size == 2 &&
                    effects[0].type == "legal.ingest" && effects[1].type == "legal.evidence")
                val intake = effects[0]
                val evidence = effects[1]
                valid(intake.params["brief"] == "brief")
                expectedWires.addAll(listOf(
                    LcncWire(input.id, "value", intake.id, "text?"),
                    LcncWire(intake.id, "documentCid", evidence.id, "documentCid?"),
                    LcncWire(intake.id, "brief", evidence.id, "brief?"),
                    LcncWire(evidence.id, "brief", seat.id, "prompt?"),
                ))
            }
            valid(body.size == 2 + effects.size + ports.size && body[0] == input && body[1 + effects.size] == seat)
            valid((0 until effects.size).all { body[it + 1] == effects[it] })
            expectedWires.add(LcncWire(guardId, "value", prefix, "when?"))
            for (port in ports.view) {
                val output = body.view.single { it.type == LcncContracts.SCOPE_OUT && it.params["name"] == port }
                valid(body[2 + effects.size + ports.view.indexOf(port)] == output)
                valid(output.params == mapOf("name" to port, "kind" to kind(port)))
                expectedWires.add(LcncWire(seat.id, port, output.id, "value"))
                val rootId = "$prefix.yield.$port"
                expectedRoots.add(rootId)
                rootOrder.add(rootId)
                val root = requireNotNull(roots[rootId]) { "case scope lacks its public yield" }
                valid(root.type == LcncContracts.SCOPE_OUT && root.params == mapOf("name" to port, "kind" to kind(port)) &&
                    root.children.size == 0 && root.subprogram == null)
                expectedWires.add(LcncWire(prefix, port, rootId, "value"))
            }
        }
        if (repeated) {
            val prefix = LcncCaseScope.RULE.id
            val guard = requireNotNull(roots["$prefix.when"]) { "case rule lacks a false-default guard" }
            val scope = requireNotNull(roots[prefix]) { "case rule scope is missing" }
            val yield = requireNotNull(roots["$prefix.yield.report"]) { "case rule report is missing" }
            expectedRoots.addAll(s_[guard.id, scope.id, yield.id].view)
            rootOrder.addAll(s_[guard.id, scope.id, yield.id].view)
            valid(guard.type == LcncContracts.SCOPE_IN && guard.params == mapOf(
                "name" to LcncCaseScope.RULE.binding, "default" to "false", "kind" to "json") &&
                guard.children.size == 0 && guard.subprogram == null)
            valid(scope.type == LcncContracts.SCOPE && scope.params.isEmpty() && scope.subprogram == null && scope.children.size == 3)
            val body = scope.children
            valid(body.view.all { it.children.size == 0 && it.subprogram == null && allIds.add(it.id) })
            valid(body[0].id == "$prefix.in" && body[0].type == LcncContracts.SCOPE_IN &&
                body[0].params == mapOf("name" to "record", "kind" to "text"))
            valid(body[1].id == "$prefix.record" && body[1].type == "tribunal.record" && body[1].params.isEmpty())
            valid(body[2].id == "$prefix.out.report" && body[2].type == LcncContracts.SCOPE_OUT &&
                body[2].params == mapOf("name" to "report", "kind" to "json"))
            valid(yield.type == LcncContracts.SCOPE_OUT && yield.params == mapOf("name" to "report", "kind" to "json") &&
                yield.children.size == 0 && yield.subprogram == null)
            expectedWires.addAll(listOf(
                LcncWire(body[0].id, "value", body[1].id, "text?"),
                LcncWire(body[1].id, "report", body[2].id, "value"),
                LcncWire(guard.id, "value", scope.id, "when?"),
                LcncWire(scope.id, "report", yield.id, "value"),
            ))
            valid(graph.lanes.view.count { it.id == "rule" } == 1 && graph.outgoing("rule").size == 0)
            valid(graph.edges.view.singleOrNull { it.id == "deliberate-rule" } ==
                KanbanEdge("deliberate-rule", "deliberate", "rule",
                    condition = KanbanCondition(TribunalPredicates.RESOLVED_RULING)))
        }
        roots["case.note"]?.let { note ->
            expectedRoots.add(note.id)
            rootOrder.add(note.id)
            valid(note.type == "note" && note.children.size == 0 && note.subprogram == null)
        }
        valid(roots.keys == expectedRoots)
        valid(program.nodes.size == rootOrder.size && rootOrder.indices.all { program.nodes[it].id == rootOrder[it] })
        valid(program.wires.size == expectedWires.size && program.wires.view.toSet() == expectedWires.toSet())
        if (repeated) {
            val initial = roots.getValue(LcncCaseScope.ARGUE_INITIAL.id).children.view.single { it.type == "mux.chat" }
            val repeat = roots.getValue(LcncCaseScope.ARGUE_REPEAT.id).children.view.single { it.type == "mux.chat" }
            valid(initial.params == repeat.params)
            valid(graph.edges.filter { it.mode == KanbanEdgeMode.LOOP }.size == 2)
            val counsel = graph.edges.view.singleOrNull { it.id == "rebut-argue" }
            valid(counsel != null && counsel.from == "rebut" && counsel.to == "argue" &&
                counsel.mode == KanbanEdgeMode.LOOP && counsel.maxIterations == 3 && counsel.condition == null)
            val clarification = graph.edges.view.singleOrNull { it.id == "deliberate-clarify" }
            valid(clarification != null && clarification.from == "deliberate" && clarification.to == "argue" &&
                clarification.mode == KanbanEdgeMode.LOOP && clarification.maxIterations == 1 &&
                clarification.condition == KanbanCondition(TribunalPredicates.NEEDS_CLARIFICATION))
        }
    }
}
