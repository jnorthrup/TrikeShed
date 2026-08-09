package borg.trikeshed.dag

import borg.trikeshed.graph.CausalGraphNode
import borg.trikeshed.graph.CausalGraphNodeIndex
import borg.trikeshed.lib.j

/**
 * Causal landmark for differential heuristics in reconciliation search.
 *
 * Inspired by Amit Patel's differential heuristic (redblobgames.com/pathfinding/
 * heuristics/differential.html): precompute cost(N, L) from every node N to a
 * fixed landmark L, then for any (start, goal) pair, the triangle inequality
 * gives a lower bound:
 *
 *   cost(B, X) >= cost(B, L) - cost(X, L)
 *
 * In the causal graph, "cost" is causal distance: the minimum number of causal
 * edges (parentNodeIds) traversed between two states. A landmark anchored at a
 * known-reconciled node (a committed MergeReceipt, a closed job, a quiescent
 * agenda) provides the "after the goal" position the article requires.
 *
 * Multiple landmarks each give a lower bound; the reconciler takes max() to
 * get the tightest. This prunes the Rete agenda: instead of firing every
 * pending activation in static salience order, fire the one whose resulting
 * state is causally closest to reconciliation.
 *
 * The metric uses [CausalGraphNode.topoOrdinal] as the primary distance
 * dimension (topological depth in the causal DAG) and [CausalGraphNode.causalClock]
 * as the secondary (Lamport timestamp). Both are already indexed in
 * [CausalGraphNodeIndex].
 */

/**
 * A causal landmark: an anchor node with precomputed backward distances.
 *
 * @param anchorIndex the [CausalGraphNodeIndex] slot of the landmark node.
 * @param anchorCausalKey the causalKey of the landmark (for identity/debug).
 * @param distances map from node index -> causal distance to this landmark.
 *        Distance is measured in topo-ordinal hops; direct parents are
 *        distance 1, grandparents 2, etc.
 */
data class CausalLandmark(
    val anchorIndex: Int,
    val anchorCausalKey: String,
    val distances: Map<Int, Int>,
) {
    /**
     * Differential heuristic lower bound for the causal distance from node
     * at [startIndex] to node at [goalIndex], via this landmark.
     *
     * Returns null if either node lacks a precomputed distance to this
     * landmark (no path found, or the node was added after landmark
     * computation).
     */
    fun lowerBound(startIndex: Int, goalIndex: Int): Int? {
        val distToLFromStart = distances[startIndex] ?: return null
        val distToLFromGoal = distances[goalIndex] ?: return null
        // Triangle inequality: cost(start, goal) >= |cost(start, L) - cost(goal, L)|
        return kotlin.math.abs(distToLFromStart - distToLFromGoal)
    }
}

/**
 * Index of multiple causal landmarks. Each provides an independent lower
 * bound; [bestLowerBound] takes the max (tightest).
 */
class CausalLandmarkIndex(private val graph: CausalGraphNodeIndex) {

    /** The backing causal graph (exposed for heuristic resolution). */
    internal val backingGraph: CausalGraphNodeIndex get() = graph

    private val landmarks: MutableList<CausalLandmark> = mutableListOf()

    val landmarkCount: Int get() = landmarks.size

    /**
     * Register a landmark at [anchorIndex]. Computes backward BFS distances
     * from the anchor through parentNodeIds to all reachable nodes.
     *
     * The anchor should be a node representing a reconciled/committed state
     * — the "after the goal" position. Good anchors: MergeReceipt commits,
     * closed jobs, quiescent checkpoints.
     */
    fun registerLandmark(anchorIndex: Int) {
        if (anchorIndex !in 0 until graph.size) return
        val anchor = graph[anchorIndex]
        val distances = computeBackwardDistances(anchorIndex)
        landmarks.add(CausalLandmark(anchorIndex, anchor.causalKey, distances))
    }

    /**
     * Register a landmark by causalKey. Looks up the node index, then
     * delegates to [registerLandmark].
     */
    fun registerLandmarkByCausalKey(causalKey: String) {
        for (i in 0 until graph.size) {
            if (graph[i].causalKey == causalKey) {
                registerLandmark(i)
                return
            }
        }
    }

    /**
     * Best differential heuristic lower bound across all registered landmarks.
     * Takes the max of per-landmark bounds (the tightest lower bound).
     *
     * Returns 0 if no landmarks are registered or no landmark can bound
     * this pair (degrades to no-heuristic A*, same as the old salience-only
     * ordering).
     */
    fun bestLowerBound(startIndex: Int, goalIndex: Int): Int {
        if (landmarks.isEmpty()) return 0
        var best = 0
        for (lm in landmarks) {
            val bound = lm.lowerBound(startIndex, goalIndex)
            if (bound != null && bound > best) best = bound
        }
        return best
    }

    /**
     * Heuristic estimate h(node) for the causal distance from [nodeIndex] to
     * the nearest reconciled landmark. This is the "encouraging feature" —
     * it guides the agenda toward activations causally closer to reconciliation.
     *
     * Uses the minimum distance-to-landmark across all landmarks (closest
     * reconciliation target). Lower h = closer to done.
     */
    fun distanceToNearestLandmark(nodeIndex: Int): Int {
        if (landmarks.isEmpty()) return Int.MAX_VALUE
        var min = Int.MAX_VALUE
        for (lm in landmarks) {
            val d = lm.distances[nodeIndex]
            if (d != null && d < min) min = d
        }
        return min
    }

    /** Remove all landmarks (e.g. when the causal graph is rebuilt). */
    fun clear() {
        landmarks.clear()
    }

    /**
     * Backward BFS through parentNodeIds, computing topo-hop distance from
     * the anchor to every reachable node.
     *
     * parentNodeIds are causalKey strings, not indices, so we build a
     * causalKey -> index lookup first, then traverse.
     */
    private fun computeBackwardDistances(anchorIndex: Int): Map<Int, Int> {
        val result = mutableMapOf<Int, Int>()
        if (anchorIndex !in 0 until graph.size) return result

        // Build causalKey -> index map for edge traversal.
        val keyToIndex = mutableMapOf<String, Int>()
        for (i in 0 until graph.size) {
            keyToIndex[graph[i].causalKey] = i
        }

        // BFS backward from anchor.
        val queue = ArrayDeque<Int>()
        queue.addLast(anchorIndex)
        result[anchorIndex] = 0

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val currentDist = result[current] ?: continue
            val node = graph[current]

            // Walk to parents (backward edges in the causal DAG).
            for (parentKey in node.parentNodeIds) {
                val parentIdx = keyToIndex[parentKey] ?: continue
                if (parentIdx !in result) {
                    result[parentIdx] = currentDist + 1
                    queue.addLast(parentIdx)
                }
            }

            // Also walk forward to children who list this node as a parent.
            // This catches siblings and forward-reachable nodes.
            for (i in 0 until graph.size) {
                if (i in result) continue
                val candidate = graph[i]
                if (candidate.parentNodeIds.contains(node.causalKey)) {
                    result[i] = currentDist + 1
                    queue.addLast(i)
                }
            }
        }

        return result
    }
}

/**
 * Augmented A* priority for a Rete activation.
 *
 * f(n) = g(n) + h(n) where:
 *   g(n) = causal cost so far (activation.sequence — the committed sequence
 *          when this activation was created)
 *   h(n) = differential heuristic lower bound to the nearest reconciled
 *          landmark (from [CausalLandmarkIndex])
 *
 * Lower f = higher priority (pop first). This is the "encouraging feature":
 * activations causally closer to reconciliation fire before ones that are
 * far away, even if their static salience is equal.
 *
 * When no landmarks are registered, h=0 and this degrades to the original
 * salience-only ordering.
 */
data class HeuristicPriority(
    val salience: Int,
    val sequence: Long,
    val heuristicDistance: Int,
) : Comparable<HeuristicPriority> {

    /**
     * A* f-value: lower is better (pop first).
     *
     * We invert salience (higher salience = lower f = pop first) and add
     * the heuristic distance. Sequence breaks ties (earlier = pop first).
     */
    private val fValue: Long
        get() {
            // Higher salience should pop first → subtract from a base.
            // Lower heuristicDistance should pop first → add directly.
            // Lower sequence should pop first → add directly.
            val salienceComponent = (Int.MAX_VALUE.toLong() - salience.toLong())
            return salienceComponent + heuristicDistance.toLong() * 1000L + sequence
        }

    override fun compareTo(other: HeuristicPriority): Int =
        fValue.compareTo(other.fValue)
}
