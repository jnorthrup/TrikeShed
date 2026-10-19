package borg.trikeshed.lcnc

import borg.trikeshed.lib.*
import borg.trikeshed.modelmux.*
import borg.trikeshed.parse.jsonOf

/** Authored endpoint/model allowlists for the chat seats inside a construction. */
object LcncModelSelection {
    fun seats(program: LcncProgram): Series<LcncNode> {
        val seats = mutableListOf<LcncNode>()
        fun visit(nodes: Series<LcncNode>) {
            for (node in nodes.view) {
                if (node.type == "mux.chat" || node.type == "council.seat") seats.add(node)
                visit(node.children)
            }
        }
        visit(program.nodes)
        return seats.toSeries()
    }

    /** Selection changes one seat, retaining the program's scope, wires and other parameters. */
    fun select(
        program: LcncProgram,
        nodeId: String,
        models: Series<EndpointModel>,
        available: Series<EndpointModel>,
    ): LcncProgram {
        require(seats(program).view.count { it.id == nodeId } == 1) { "Choose one existing chat seat" }
        require(models.size > 0) { "Choose at least one endpoint/model route" }
        val rows = models.view.map { route ->
            require(available.view.any { it.a == route.a && it.b == route.b }) {
                "Endpoint/model route is not in the current catalog: ${route.a}/${route.b}"
            }
            mapOf("key" to route.a, "model" to route.b)
        }
        require(rows.distinct().size == rows.size) { "Duplicate endpoint/model route" }
        val selected = jsonOf(rows)
        fun nodes(values: Series<LcncNode>): Series<LcncNode> = values α { node ->
            node.copy(
                params = if (node.id == nodeId) node.params + ("models" to selected) else node.params,
                children = nodes(node.children),
            )
        }
        return program.copy(nodes = nodes(program.nodes))
    }
}
