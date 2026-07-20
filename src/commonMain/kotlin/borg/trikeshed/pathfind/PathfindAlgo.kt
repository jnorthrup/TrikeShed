package borg.trikeshed.pathfind

import borg.trikeshed.graph.query.Graph

/**
 * Result of a pathfinding query.
 */
data class PathResult<N>(val path: List<N>, val cost: Double)

/**
 * A basic Priority Queue implementation for A* and Dijkstra since commonMain
 * does not have java.util.PriorityQueue.
 */
internal class PriorityQueue<T>(private val comparator: Comparator<T>) {
    private val elements = mutableListOf<T>()

    fun isEmpty() = elements.isEmpty()
    fun isNotEmpty() = elements.isNotEmpty()

    fun add(element: T) {
        elements.add(element)
        siftUp(elements.size - 1)
    }

    fun poll(): T? {
        if (elements.isEmpty()) return null
        val result = elements[0]
        val last = elements.removeAt(elements.size - 1)
        if (elements.isNotEmpty()) {
            elements[0] = last
            siftDown(0)
        }
        return result
    }

    private fun siftUp(index: Int) {
        var child = index
        while (child > 0) {
            val parent = (child - 1) / 2
            if (comparator.compare(elements[child], elements[parent]) >= 0) break
            swap(child, parent)
            child = parent
        }
    }

    private fun siftDown(index: Int) {
        var parent = index
        while (true) {
            val left = 2 * parent + 1
            val right = 2 * parent + 2
            var smallest = parent

            if (left < elements.size && comparator.compare(elements[left], elements[smallest]) < 0) {
                smallest = left
            }
            if (right < elements.size && comparator.compare(elements[right], elements[smallest]) < 0) {
                smallest = right
            }
            if (smallest == parent) break
            swap(parent, smallest)
            parent = smallest
        }
    }

    private fun swap(i: Int, j: Int) {
        val temp = elements[i]
        elements[i] = elements[j]
        elements[j] = temp
    }
}

/**
 * Runs Dijkstra's algorithm from `start` to `end` on the given graph.
 * @param weightFn function extracting the numeric weight (cost) of an edge.
 * @return the shortest path and its cost, or null if no path exists.
 */
fun <N, E> Graph<N, E>.dijkstra(start: N, end: N, weightFn: (E) -> Double): PathResult<N>? {
    return aStar(start, end, weightFn) { 0.0 }
}

/**
 * Runs A* search algorithm from `start` to `end`.
 * @param weightFn function extracting the edge cost.
 * @param heuristicFn admissible heuristic estimating cost from node N to end.
 * @return the shortest path and its cost, or null if no path exists.
 */
fun <N, E> Graph<N, E>.aStar(start: N, end: N, weightFn: (E) -> Double, heuristicFn: (N) -> Double): PathResult<N>? {
    data class State(val node: N, val cost: Double, val fScore: Double)

    val openSet = PriorityQueue<State>(compareBy { it.fScore })
    val cameFrom = mutableMapOf<N, N>()

    // cost from start to node
    val gScore = mutableMapOf<N, Double>().withDefault { Double.POSITIVE_INFINITY }
    gScore[start] = 0.0

    openSet.add(State(start, 0.0, heuristicFn(start)))

    while (openSet.isNotEmpty()) {
        val current = openSet.poll()!!.node

        if (current == end) {
            val path = mutableListOf<N>()
            var curr = current
            while (curr in cameFrom) {
                path.add(curr)
                curr = cameFrom.getValue(curr)
            }
            path.add(start)
            path.reverse()
            return PathResult(path, gScore.getValue(end))
        }

        for ((neighbor, edge) in outEdges(current)) {
            val tentativeGScore = gScore.getValue(current) + weightFn(edge)
            if (tentativeGScore < gScore.getValue(neighbor)) {
                cameFrom[neighbor] = current
                gScore[neighbor] = tentativeGScore
                val fScore = tentativeGScore + heuristicFn(neighbor)
                openSet.add(State(neighbor, tentativeGScore, fScore))
            }
        }
    }

    return null
}
