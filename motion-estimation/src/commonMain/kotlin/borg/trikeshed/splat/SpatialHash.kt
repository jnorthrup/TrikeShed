package borg.trikeshed.splat

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.view
import borg.trikeshed.lib.α

/** Spatial hash grid for O(1) k-NN search in query space */
class SpatialHashGrid(
    private val cellSize: Double,
    private val queryDim: Int,
) {
    private val grid = mutableMapOf<Long, MutableList<Int>>()

    private fun hashPoint(z: Series<Double>): Series<Int> =
        queryDim.j { (z[it] / cellSize).floor().toInt() }

    private fun cellKey(cell: Series<Int>): Long {
        var h = 0L
        cell.forEach { h = h * 31 + it.toLong() }
        return h
    }

    fun insert(idx: Int, unit: GUnit) {
        val key = cellKey(hashPoint(unit.mean))
        grid.getOrPut(key) { mutableListOf() }.add(idx)
    }

    fun remove(idx: Int, unit: GUnit) {
        val key = cellKey(hashPoint(unit.mean))
        grid[key]?.remove(idx)
        if (grid[key]?.isEmpty() == true) grid.remove(key)
    }

    /** k-NN with progressive Chebyshev radius expansion */
    fun kNearest(
        z: Series<Double>,
        units: List<GUnit>,
        k: Int,
        maxRadius: Int = 3,
    ): Series<Join<Int, Double>> {
        val queryCell = hashPoint(z)
        val candidates = mutableListOf<Join<Int, Double>>()
        var radius = 0

        while (candidates.size < k && radius <= maxRadius) {
            val offsets = generateOffsets(radius)
            for (offset in offsets) {
                val neighborCell = queryCell.size.j { queryCell[it] + offset[it] }
                val key = cellKey(neighborCell)
                grid[key]?.forEach { idx ->
                    val distSq = units[idx].mahalanobisSq(z)
                    candidates.add(idx.j(distSq))
                }
            }
            radius++
        }

        return candidates.sortedBy { it.b }.take(k).toSeries()
    }

    private fun generateOffsets(radius: Int): List<Series<Int>> {
        if (radius == 0) return listOf(queryDim.j { 0 })
        val result = mutableListOf<Series<Int>>()
        val current = MutableList(queryDim) { 0 }
        fun recurse(dim: Int) {
            if (dim == queryDim) {
                result.add(current.toSeries())
                return
            }
            for (d in -radius..radius) {
                current[dim] = d
                recurse(dim + 1)
            }
        }
        recurse(0)
        return result
    }

    fun clear() = grid.clear()
}