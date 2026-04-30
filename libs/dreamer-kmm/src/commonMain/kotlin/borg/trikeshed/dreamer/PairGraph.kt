package borg.trikeshed.dreamer

/**
 * Represents the trade pair graph where nodes are assets and edges are available trade pairs.
 */
class PairGraph {
    private val adjList = mutableMapOf<String, MutableList<String>>()
    private val pairExists = mutableSetOf<String>()

    fun addPair(base: String, quote: String) {
        adjList.getOrPut(base) { mutableListOf() }.add(quote)
        adjList.getOrPut(quote) { mutableListOf() }.add(base) // Trades can happen in both directions
        pairExists.add("$base-$quote")
        pairExists.add("$quote-$base")
    }

    /**
     * Finds the shortest path to convert an asset back to a prime fiat (e.g. USDT) using BFS/Dijkstra logic.
     * Returns a list of assets forming the path (e.g. [AAVE, BTC, USDT]).
     */
    fun findShortestPathToFiat(fromAsset: String, targetFiat: String = "USDT"): List<String>? {
        if (fromAsset == targetFiat) return listOf(fromAsset)

        val queue = ArrayDeque<List<String>>()
        val visited = mutableSetOf<String>()

        queue.addLast(listOf(fromAsset))
        visited.add(fromAsset)

        while (queue.isNotEmpty()) {
            val path = queue.removeFirst()
            val node = path.last()

            if (node == targetFiat) {
                return path
            }

            val neighbors = adjList[node] ?: emptyList()
            for (neighbor in neighbors) {
                if (neighbor !in visited) {
                    visited.add(neighbor)
                    val newPath = path + neighbor
                    queue.addLast(newPath)
                }
            }
        }
        return null // No path found
    }
}
