package borg.trikeshed.forge.blackboard

import borg.trikeshed.graph.CausalGraphNodeIndex
import borg.trikeshed.lib.get
import kotlin.math.sqrt
import kotlin.math.max

/**
 * Computes a deterministic force-directed layout (spring + electrostatic) for
 * the causal graph, and determines a new camera state by interpreting the
 * layout's center of mass or bounding box as the target focus area.
 *
 * It takes the graph and camera, runs `iterations` simulation steps, and returns
 * a new camera whose x/y/zoom have been updated to frame the graph.
 */
fun forceLayout(
    graph: CausalGraphNodeIndex,
    camera: ForgeBlackboardCamera,
    iterations: Int = 100
): Pair<ForgeBlackboardCamera, Map<String, ProjectedPoint>> {
    val size = graph.size
    if (size == 0) return Pair(camera, emptyMap())

    val posX = DoubleArray(size)
    val posY = DoubleArray(size)
    val velX = DoubleArray(size)
    val velY = DoubleArray(size)

    // A simple grid initialization to break symmetry
    for (i in 0 until size) {
        val cols = Math.ceil(sqrt(size.toDouble())).toInt()
        val r = i / cols
        val c = i % cols
        posX[i] = c * 100.0
        posY[i] = r * 100.0
    }

    val kRepulsion = 100000.0 // higher repulsion
    val kSpring = 0.1 // lower spring
    val restingLength = 50.0
    val dt = 0.05
    val friction = 0.5

    for (iter in 0 until iterations) {
        val forceX = DoubleArray(size)
        val forceY = DoubleArray(size)

        for (i in 0 until size) {
            for (j in i + 1 until size) {
                var dx = posX[i] - posX[j]
                var dy = posY[i] - posY[j]
                var distSq = dx * dx + dy * dy
                if (distSq < 1.0) {
                    dx = 1.0; dy = 0.0; distSq = 1.0
                }
                val force = kRepulsion / distSq
                val dist = sqrt(distSq)
                val fx = (dx / dist) * force
                val fy = (dy / dist) * force
                forceX[i] += fx
                forceY[i] += fy
                forceX[j] -= fx
                forceY[j] -= fy
            }
        }

        for (i in 0 until size) {
            val node = graph[i]
            for (parentId in node.parentNodeIds) {
                var parentIndex = -1
                for (k in 0 until size) {
                    if (graph[k].nodeId == parentId) {
                        parentIndex = k
                        break
                    }
                }
                if (parentIndex == -1) continue

                val dx = posX[parentIndex] - posX[i]
                val dy = posY[parentIndex] - posY[i]
                val dist = sqrt(dx * dx + dy * dy)
                if (dist > 0.0) {
                    val force = kSpring * (dist - restingLength)
                    val fx = (dx / dist) * force
                    val fy = (dy / dist) * force
                    forceX[i] += fx
                    forceY[i] += fy
                    forceX[parentIndex] -= fx
                    forceY[parentIndex] -= fy
                }
            }
        }

        for (i in 0 until size) {
            velX[i] = (velX[i] + forceX[i] * dt) * friction
            velY[i] = (velY[i] + forceY[i] * dt) * friction
            posX[i] += velX[i] * dt
            posY[i] += velY[i] * dt
        }
    }

    var minX = posX[0]
    var maxX = posX[0]
    var minY = posY[0]
    var maxY = posY[0]
    val nodePositions = mutableMapOf<String, ProjectedPoint>()

    for (i in 0 until size) {
        nodePositions[graph[i].nodeId] = ProjectedPoint(posX[i], posY[i])
        if (posX[i] < minX) minX = posX[i]
        if (posX[i] > maxX) maxX = posX[i]
        if (posY[i] < minY) minY = posY[i]
        if (posY[i] > maxY) maxY = posY[i]
    }

    val cx = (minX + maxX) / 2.0
    val cy = (minY + maxY) / 2.0
    val spanX = max(1.0, maxX - minX)
    val spanY = max(1.0, maxY - minY)

    val targetZoom = (1000.0 / (max(spanX, spanY) * 1.5)).coerceIn(camera.minZoom, camera.maxZoom)

    return Pair(
        camera.copy(
            x = cx,
            y = cy,
            zoom = targetZoom,
            vx = 0.0,
            vy = 0.0,
            vz = 0.0
        ),
        nodePositions
    )
}
