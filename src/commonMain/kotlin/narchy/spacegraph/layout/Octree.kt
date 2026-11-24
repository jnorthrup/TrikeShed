package narchy.spacegraph.layout

// Port of upstream src/layout/octree.ts at 125b369.
// Octree spatial index for the Barnes-Hut repulsion approximation (O(n log n) repulsion over grouped cells).
// The insertion order and the centre-of-mass arithmetic follow upstream line for line, including its re-insertion of
// a split leaf into the parent cell (upstream adds that leaf's mass to the parent a second time; kept as written).

/** A mutable 3-vector for the simulation: positions and forces. Distinct from the immutable [narchy.spacegraph.Vec3]. */
class SimVector(var x: Double = 0.0, var y: Double = 0.0, var z: Double = 0.0)

/** A simulated body: the minimal shape the force core and the octree read. */
interface SimNode {
    val id: String
    val position: SimVector
    val depth: Double?
}

class OctreeLeaf(val x: Double, val y: Double, val z: Double, val mass: Double, val id: String)

/** A cell of the octree: its bounds, its mass and centre of mass, and either children or one leaf. */
class OctreeCell(
    var minX: Double,
    var minY: Double,
    var minZ: Double,
    var maxX: Double,
    var maxY: Double,
    var maxZ: Double,
    var centerX: Double,
    var centerY: Double,
    var centerZ: Double,
    var halfSize: Double,
) {
    /** Total mass (sum of node radii/masses) in this cell. */
    var mass: Double = 0.0
    var comX: Double = 0.0
    var comY: Double = 0.0
    var comZ: Double = 0.0
    var children: MutableList<OctreeCell>? = null
    var leaf: OctreeLeaf? = null
}

/** Barnes-Hut configuration. Upstream's `theta`, `maxDepth` and `minCellSize`. */
data class BarnesHutConfig(
    /** Theta threshold: if cell size / distance < theta, treat as single body. */
    val theta: Double = 0.5,
    /** Maximum depth of the tree. */
    val maxDepth: Int = 20,
    /** Minimum cell size before stopping subdivision. */
    val minCellSize: Double = 1.0,
)

/** Build an octree from a set of nodes for Barnes-Hut force approximation. */
fun buildOctree(
    nodes: List<SimNode>,
    getMass: (SimNode) -> Double,
    config: BarnesHutConfig = BarnesHutConfig(),
): OctreeCell {
    var minX = Double.POSITIVE_INFINITY
    var minY = Double.POSITIVE_INFINITY
    var minZ = Double.POSITIVE_INFINITY
    var maxX = Double.NEGATIVE_INFINITY
    var maxY = Double.NEGATIVE_INFINITY
    var maxZ = Double.NEGATIVE_INFINITY

    for (node in nodes) {
        val p = node.position
        if (p.x < minX) minX = p.x
        if (p.y < minY) minY = p.y
        if (p.z < minZ) minZ = p.z
        if (p.x > maxX) maxX = p.x
        if (p.y > maxY) maxY = p.y
        if (p.z > maxZ) maxZ = p.z
    }

    // Add padding
    val padding = maxOf(maxX - minX, maxY - minY, maxZ - minZ) * 0.1 + 10
    minX -= padding; minY -= padding; minZ -= padding
    maxX += padding; maxY += padding; maxZ += padding

    // Ensure cubic bounds
    val size = maxOf(maxX - minX, maxY - minY, maxZ - minZ)
    val centerX = (minX + maxX) / 2
    val centerY = (minY + maxY) / 2
    val centerZ = (minZ + maxZ) / 2
    minX = centerX - size / 2
    minY = centerY - size / 2
    minZ = centerZ - size / 2
    maxX = centerX + size / 2
    maxY = centerY + size / 2
    maxZ = centerZ + size / 2

    val root = OctreeCell(
        minX, minY, minZ, maxX, maxY, maxZ,
        centerX, centerY, centerZ, size / 2,
    )

    for (node in nodes) {
        insertIntoOctree(root, node.position.x, node.position.y, node.position.z, getMass(node), node.id, 0, config.maxDepth, config.minCellSize)
    }

    return root
}

private fun insertIntoOctree(
    cell: OctreeCell,
    x: Double,
    y: Double,
    z: Double,
    mass: Double,
    id: String,
    depth: Int,
    maxDepth: Int,
    minCellSize: Double,
) {
    // Update centre of mass
    val newMass = cell.mass + mass
    cell.comX = (cell.comX * cell.mass + x * mass) / newMass
    cell.comY = (cell.comY * cell.mass + y * mass) / newMass
    cell.comZ = (cell.comZ * cell.mass + z * mass) / newMass
    cell.mass = newMass

    // If leaf and empty, store here
    if (cell.children == null && cell.leaf == null) {
        cell.leaf = OctreeLeaf(x, y, z, mass, id)
        return
    }

    // If leaf with existing node, subdivide
    val existing = cell.leaf
    if (cell.children == null && existing != null) {
        if (depth >= maxDepth || cell.halfSize <= minCellSize) {
            // Can't subdivide further - combine masses
            cell.leaf = null
            return
        }
        subdivide(cell)
        // Re-insert existing leaf
        insertIntoOctree(cell, existing.x, existing.y, existing.z, existing.mass, existing.id, depth + 1, maxDepth, minCellSize)
        cell.leaf = null
    }

    // Insert into appropriate child
    val kids = cell.children
    if (kids != null) {
        insertIntoOctree(kids[childIndex(cell, x, y, z)], x, y, z, mass, id, depth + 1, maxDepth, minCellSize)
    }
}

private fun subdivide(cell: OctreeCell) {
    val h = cell.halfSize / 2
    val cx = cell.centerX
    val cy = cell.centerY
    val cz = cell.centerZ

    val kids = mutableListOf<OctreeCell>()
    for (i in 0 until 8) {
        val dx = if (i and 1 != 0) h else -h
        val dy = if (i and 2 != 0) h else -h
        val dz = if (i and 4 != 0) h else -h
        kids.add(
            OctreeCell(
                minX = cx + dx - h,
                maxX = cx + dx + h,
                minY = cy + dy - h,
                maxY = cy + dy + h,
                minZ = cz + dz - h,
                maxZ = cz + dz + h,
                centerX = cx + dx,
                centerY = cy + dy,
                centerZ = cz + dz,
                halfSize = h,
            ),
        )
    }
    cell.children = kids
}

private fun childIndex(cell: OctreeCell, x: Double, y: Double, z: Double): Int {
    var index = 0
    if (x > cell.centerX) index = index or 1
    if (y > cell.centerY) index = index or 2
    if (z > cell.centerZ) index = index or 4
    return index
}

private fun addRepulsion(
    force: SimVector,
    dx: Double,
    dy: Double,
    dz: Double,
    distSq: Double,
    dist: Double,
    strength: Double,
    minDistance: Double,
    maxForce: Double,
) {
    val capped = minOf(strength / maxOf(distSq, minDistance * minDistance), maxForce)
    force.x += (dx / dist) * capped
    force.y += (dy / dist) * capped
    force.z += (dz / dist) * capped
}

/** JS `sqrt(v) || 0.001`: zero and NaN fall back to the epsilon distance. */
internal fun orEpsilonDistance(v: Double): Double = if (v == 0.0 || v.isNaN()) 0.001 else v

/**
 * Repulsion on a node using the Barnes-Hut approximation.
 * `theta` is the opening angle (cell size / distance); a cell further than that acts as one body.
 */
fun computeBarnesHutForce(
    node: SimNode,
    octree: OctreeCell,
    theta: Double,
    repulsion: Double,
    minDistance: Double,
    maxForce: Double,
): SimVector {
    val force = SimVector()
    val stack = ArrayDeque<OctreeCell>()
    stack.addLast(octree)

    while (stack.isNotEmpty()) {
        val cell = stack.removeLast()

        // Skip empty cells
        if (cell.mass == 0.0) continue

        // If leaf node, compute direct force
        val leaf = cell.leaf
        if (leaf != null) {
            if (leaf.id == node.id) continue // Skip self

            val dx = node.position.x - leaf.x
            val dy = node.position.y - leaf.y
            val dz = node.position.z - leaf.z
            val distSq = dx * dx + dy * dy + dz * dz
            addRepulsion(force, dx, dy, dz, distSq, orEpsilonDistance(kotlin.math.sqrt(distSq)), repulsion, minDistance, maxForce)
            continue
        }

        // Check if we can approximate this cell as a single body
        val dx = node.position.x - cell.comX
        val dy = node.position.y - cell.comY
        val dz = node.position.z - cell.comZ
        val distSq = dx * dx + dy * dy + dz * dz
        val dist = orEpsilonDistance(kotlin.math.sqrt(distSq))

        // Cell size / distance ratio
        val cellSize = cell.halfSize * 2
        if (cellSize / dist < theta) {
            addRepulsion(force, dx, dy, dz, distSq, dist, repulsion * cell.mass, minDistance, maxForce)
        } else {
            // Recurse into children
            cell.children?.forEach { child -> if (child.mass > 0) stack.addLast(child) }
        }
    }

    return force
}
