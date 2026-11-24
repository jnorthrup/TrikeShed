package narchy.spacegraph.layout

// Port of upstream src/layout/force.ts at 125b369.
// The force placement: a simulation, not a grid. The simulation is deterministic and then frozen: boxes a layout
// already placed keep their position, and only new ones relax against them.

import narchy.spacegraph.Vec3

class ForcePlacement(config: ForceCoreConfig = ForceCoreConfig()) : Placement<ForceMeta> {
    override val name = "force"
    private val core = ForceCore(config)

    override fun place(items: List<PlacementItem<ForceMeta>>, previous: Map<String, Position>?): Map<String, Position> {
        // Convert to the internal format for ForceCore
        val nodes = items.map { item ->
            ForceNode(
                id = item.id,
                position = SimVector(0.0, 0.0, 0.0),
                depth = item.depth,
                width = item.width,
                height = item.height,
                meta = item.meta,
            )
        }

        val previousMap = previous?.mapValues { (_, p) -> SimVector(p.x, p.y, p.z) }
        val pinnedIds = previousMap?.keys.orEmpty()

        core.run(
            nodes,
            { node -> node.meta?.links.orEmpty() },
            { node -> core.getNodeRadius(node.width, node.height) },
            { node -> node.id in pinnedIds }, // pin nodes that have previous positions
            { node, z -> node.position.z = z },
            previousMap,
        )

        val positions = LinkedHashMap<String, Position>()
        for (node in nodes) {
            positions[node.id] = Vec3(node.position.x, node.position.y, node.position.z)
        }

        return positions
    }
}
