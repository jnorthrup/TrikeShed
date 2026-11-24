package narchy.spacegraph.layout

// Port of upstream src/layout/force-core.ts at 125b369.
// Shared core for the force-directed placement: Fruchterman-Reingold repulsion and link attraction, cooled by a
// temperature, with Barnes-Hut repulsion above 100 nodes and a final overlap separation pass.
// The golden-angle start and the mulberry32 random start follow upstream. Math.cos/sin may differ from upstream's
// JavaScript engine in the last bit; the upstream tests check distances, not exact values.

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/** The force placement's vocabulary: what pulls a box towards another one. */
data class ForceMeta(
    /** The ids this box is attracted to. */
    val links: List<String>? = null,
    /** The box's radius: half its width, the distance two boxes need to sit side by side. */
    val radius: Double? = null,
)

/** Upstream's `Partial<BarnesHutConfig>`: every field optional, the defaults come from [BarnesHutConfig]. */
data class BarnesHutOptions(
    val theta: Double? = null,
    val maxDepth: Int? = null,
    val minCellSize: Double? = null,
)

data class ForceCoreConfig(
    /** The distance two boxes rest at (repulsion equilibrium). */
    val spacing: Double? = null,
    /** The simulation steps. */
    val iterations: Int? = null,
    /** The seed of the initial placement. */
    val seed: Int? = null,
    /** The Z distance per depth. */
    val depthStep: Double? = null,
    /** Extra padding between nodes for collision avoidance. */
    val nodePadding: Double? = null,
    /** Attraction strength along links/edges. */
    val attraction: Double? = null,
    /** Repulsion strength. */
    val repulsion: Double? = null,
    /** Gravity towards centre. */
    val gravity: Double? = null,
    /** Minimum distance for force calculation. */
    val minDistance: Double? = null,
    /** If true, nodes start from random positions instead of the golden-angle spiral. */
    val randomize: Boolean? = null,
    /** Progress callback: fired each iteration with (iteration, total). */
    val onProgress: ((Int, Int) -> Unit)? = null,
    /** Use Barnes-Hut octree for O(n log n) repulsion (for large graphs). */
    val useBarnesHut: Boolean? = null,
    /** Barnes-Hut configuration. */
    val barnesHut: BarnesHutOptions? = null,
)

private const val DEFAULT_SPACING = 240.0
private const val DEFAULT_ITERATIONS = 300
private const val DEFAULT_DEPTH_STEP = 64.0
private const val DEFAULT_ATTRACTION = 0.01
private const val DEFAULT_REPULSION = 10000.0
private const val DEFAULT_GRAVITY = 0.1
private const val DEFAULT_MIN_DISTANCE = 50.0

/** The golden angle: it spreads the initial placement without a random number generator. */
private const val GOLDEN_ANGLE = 2.399963229728653

/** A simulated body with the fields the placement reads: its radius inputs and its links. */
class ForceNode(
    override val id: String,
    override val position: SimVector,
    override val depth: Double?,
    val width: Double,
    val height: Double?,
    val meta: ForceMeta?,
) : SimNode

class ForceCore(config: ForceCoreConfig = ForceCoreConfig()) {
    private val spacing = config.spacing ?: DEFAULT_SPACING
    private val iterations = config.iterations ?: DEFAULT_ITERATIONS
    private val seed = config.seed ?: 1
    private val depthStep = config.depthStep ?: DEFAULT_DEPTH_STEP
    private val nodePadding = config.nodePadding ?: DEFAULT_NODE_PADDING.toDouble()
    private val attraction = config.attraction ?: DEFAULT_ATTRACTION
    private val repulsion = config.repulsion ?: DEFAULT_REPULSION
    private val gravity = config.gravity ?: DEFAULT_GRAVITY
    private val minDistance = config.minDistance ?: DEFAULT_MIN_DISTANCE
    private val randomize = config.randomize ?: false
    private val onProgress = config.onProgress
    private val useBarnesHut = config.useBarnesHut ?: ((config.iterations ?: 0) > 1000)
    private val barnesHutTheta = config.barnesHut?.theta ?: 0.5
    private val barnesHutMaxDepth = config.barnesHut?.maxDepth ?: 20
    private val barnesHutMinCellSize = config.barnesHut?.minCellSize ?: 1.0

    /** The collision radius for a box: half its diagonal plus padding. */
    fun getNodeRadius(width: Double, height: Double?, padding: Double? = null): Double =
        diagonalRadius(width, height ?: width, padding ?: nodePadding)

    /** Advance the simulation by one step; returns the force on each node. */
    fun step(
        nodes: List<ForceNode>,
        getLinks: (ForceNode) -> List<String>,
        getRadius: (ForceNode) -> Double,
    ): Map<String, SimVector> {
        if (nodes.isEmpty()) return emptyMap()

        val forces = HashMap<String, SimVector>()
        val nodeById = HashMap<String, ForceNode>()
        for (node in nodes) {
            forces[node.id] = SimVector()
            if (node.id !in nodeById) nodeById[node.id] = node
        }

        // Precompute radii (used as mass for Barnes-Hut)
        val radii = HashMap<String, Double>()
        for (node in nodes) radii[node.id] = getRadius(node)

        val maxForce = repulsion / (minDistance * minDistance)

        if (useBarnesHut && nodes.size > 100) {
            val octree = buildOctree(
                nodes,
                { node -> radii[node.id] ?: 1.0 },
                BarnesHutConfig(barnesHutTheta, barnesHutMaxDepth, barnesHutMinCellSize),
            )

            for (node in nodes) {
                val force = computeBarnesHutForce(node, octree, barnesHutTheta, repulsion, minDistance, maxForce)
                val f = forces.getValue(node.id)
                f.x += force.x
                f.y += force.y
                f.z += force.z
            }
        } else {
            // Repulsion: all-pairs (exact for small graphs)
            for (i in nodes.indices) {
                for (j in i + 1 until nodes.size) {
                    val a = nodes[i]
                    val b = nodes[j]
                    val dx = a.position.x - b.position.x
                    val dy = a.position.y - b.position.y
                    val dz = a.position.z - b.position.z
                    val distSq = dx * dx + dy * dy + dz * dz
                    val dist = orEpsilonDistance(sqrt(distSq))

                    // Per-pair minimum distance based on node sizes
                    val minDist = (radii[a.id] ?: 0.0) + (radii[b.id] ?: 0.0)
                    val effectiveDistSq = maxOf(distSq, minDist * minDist)

                    val rep = repulsion / effectiveDistSq
                    val force = minOf(rep, maxForce)
                    val fx = (dx / dist) * force
                    val fy = (dy / dist) * force
                    val fz = (dz / dist) * force

                    val forceA = forces.getValue(a.id)
                    val forceB = forces.getValue(b.id)
                    forceA.x += fx; forceA.y += fy; forceA.z += fz
                    forceB.x -= fx; forceB.y -= fy; forceB.z -= fz
                }
            }
        }

        // Attraction along links/edges
        for (node in nodes) {
            val force = forces.getValue(node.id)
            for (link in getLinks(node)) {
                val target = nodeById[link] ?: continue

                val dx = target.position.x - node.position.x
                val dy = target.position.y - node.position.y
                val dz = target.position.z - node.position.z
                val dist = orEpsilonDistance(sqrt(dx * dx + dy * dy + dz * dz))

                val pull = attraction * dist
                val fx = (dx / dist) * pull
                val fy = (dy / dist) * pull
                val fz = (dz / dist) * pull

                force.x += fx; force.y += fy; force.z += fz
            }
        }

        // Gravity towards centre
        for (node in nodes) {
            val force = forces.getValue(node.id)
            val p = node.position
            val dist = sqrt(p.x * p.x + p.y * p.y + p.z * p.z)
            if (dist > 0) {
                force.x += -p.x / dist * gravity * dist
                force.y += -p.y / dist * gravity * dist
                force.z += -p.z / dist * gravity * dist
            }
        }

        return forces
    }

    /** Final separation pass: push overlapping nodes apart until no overlaps remain. */
    fun separateOverlaps(nodes: List<ForceNode>, getRadius: (ForceNode) -> Double, isPinned: (ForceNode) -> Boolean) {
        separateOverlappingNodes(nodes, getRadius, isPinned, if (nodes.size > 150) 1 else 30)
    }

    /** Initialize positions deterministically: a golden-angle spiral, or a mulberry32 scatter when randomized. */
    fun initializePositions(nodes: List<ForceNode>, previous: Map<String, SimVector>?) {
        for ((i, node) in nodes.withIndex()) {
            val prev = previous?.get(node.id)
            if (prev != null) {
                node.position.x = prev.x
                node.position.y = prev.y
                node.position.z = prev.z
                continue
            }

            if (randomize) {
                // mulberry32, as upstream: 32-bit wrap-around arithmetic, unsigned result over 2^32
                var t = seed + i
                t += 0x6d2b79f5
                var r = (t xor (t ushr 15)) * (1 or t)
                r = r xor (r + ((r xor (r ushr 7)) * (61 or r)))
                val rand = toUnsigned(r xor (r ushr 14)) / 4294967296.0
                t += 0x6d2b79f5
                var r2 = (t xor (t ushr 15)) * (1 or t)
                r2 = r2 xor (r2 + ((r2 xor (r2 ushr 7)) * (61 or r2)))
                val rand2 = toUnsigned(r2 xor (r2 ushr 14)) / 4294967296.0
                t += 0x6d2b79f5
                var r3 = (t xor (t ushr 15)) * (1 or t)
                r3 = r3 xor (r3 + ((r3 xor (r3 ushr 7)) * (61 or r3)))
                val rand3 = toUnsigned(r3 xor (r3 ushr 14)) / 4294967296.0
                node.position.x = (rand - 0.5) * 400
                node.position.y = (rand2 - 0.5) * 400
                node.position.z = (rand3 - 0.5) * 400
            } else {
                val angle = (i + seed) * GOLDEN_ANGLE
                val radius = (spacing * sqrt((i + 1).toDouble())) / 2
                node.position.x = cos(angle) * radius
                node.position.y = sin(angle) * radius
                node.position.z = 0.0
            }
        }
    }

    /** Run the full simulation. */
    fun run(
        nodes: List<ForceNode>,
        getLinks: (ForceNode) -> List<String>,
        getRadius: (ForceNode) -> Double,
        isPinned: (ForceNode) -> Boolean,
        setDepth: (ForceNode, Double) -> Unit,
        previous: Map<String, SimVector>?,
    ) {
        initializePositions(nodes, previous)

        // The simulation adapts iterations based on node count for performance
        val k = spacing
        val steps = if (nodes.size > 150) {
            minOf(1, iterations)
        } else {
            maxOf(60, minOf(iterations, jsRound((9000.0 * iterations) / (300.0 * nodes.size))))
        }

        for (step in 0 until steps) {
            val heat = (k / 2) * (1 - step.toDouble() / steps)
            val forces = step(nodes, getLinks, getRadius)

            for (node in nodes) {
                if (isPinned(node)) continue
                val force = forces.getValue(node.id)
                val magnitude = sqrt(force.x * force.x + force.y * force.y + force.z * force.z)

                if (magnitude == 0.0) continue
                val limit = minOf(magnitude, heat) / magnitude
                node.position.x += force.x * limit
                node.position.y += force.y * limit
                node.position.z += force.z * limit
            }

            onProgress?.invoke(step + 1, steps)
        }

        // Final separation pass. Barnes-Hut repulsion already separates large graphs.
        if (nodes.size <= 150) separateOverlaps(nodes, getRadius, isPinned)

        // Apply depth-based Z
        for (node in nodes) {
            if (isPinned(node)) continue
            setDepth(node, (node.depth ?: 0.0) * depthStep)
        }
    }

    private fun toUnsigned(v: Int): Double = (v.toLong() and 0xffffffffL).toDouble()

    /** JS Math.round: halves round towards +infinity. Values here are non-negative. */
    private fun jsRound(v: Double): Int = floor(v + 0.5).toInt()
}

/** Push overlapping nodes apart in place, stopping early once no overlap remains. */
fun separateOverlappingNodes(
    nodes: List<ForceNode>,
    getRadius: (ForceNode) -> Double,
    isPinned: (ForceNode) -> Boolean,
    passes: Int,
) {
    val radii = HashMap<String, Double>()
    for (node in nodes) radii[node.id] = getRadius(node)

    for (pass in 0 until passes) {
        var anyOverlap = false
        for (i in nodes.indices) {
            for (j in i + 1 until nodes.size) {
                val a = nodes[i]
                val b = nodes[j]
                if (isPinned(a) && isPinned(b)) continue

                val dx = a.position.x - b.position.x
                val dy = a.position.y - b.position.y
                val dz = a.position.z - b.position.z
                val dist = orEpsilonDistance(sqrt(dx * dx + dy * dy + dz * dz))

                val needed = (radii[a.id] ?: 0.0) + (radii[b.id] ?: 0.0)
                if (dist >= needed) continue

                anyOverlap = true
                val shove = (needed - dist) / 2
                val fx = (dx / dist) * shove
                val fy = (dy / dist) * shove
                val fz = (dz / dist) * shove

                if (!isPinned(a)) {
                    a.position.x += fx
                    a.position.y += fy
                    a.position.z += fz
                }
                if (!isPinned(b)) {
                    b.position.x -= fx
                    b.position.y -= fy
                    b.position.z -= fz
                }
            }
        }
        if (!anyOverlap) break
    }
}
