package borg.trikeshed.splat

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.view
import borg.trikeshed.lib.α
import kotlin.math.floor

/** Recovered from `libs/motion-estimation` at `b4ce9a494^`; duplicate-free progressive Chebyshev search. */
class SpatialHashGrid(
    private val cellSize: Double,
    private val dimensions: Int,
) {
    private val grid = mutableMapOf<Long, MutableList<Int>>()

    init {
        require(cellSize > 0.0)
        require(dimensions > 0)
    }

    private fun hashPoint(point: Series<Double>): Series<Int> {
        require(point.size == dimensions)
        return point α { coordinate -> floor(coordinate / cellSize).toInt() }
    }

    private fun cellKey(cell: Series<Int>): Long {
        var hash = 0xcbf29ce484222325UL.toLong()
        for (coordinate in cell.view) hash = (hash xor coordinate.toLong()) * 0x100000001b3L
        return hash
    }

    fun insert(index: Int, gaussian: ParameterGaussian) {
        val bucket = grid.getOrPut(cellKey(hashPoint(gaussian.mean))) { mutableListOf() }
        if (index !in bucket) bucket += index
    }

    fun remove(index: Int, gaussian: ParameterGaussian) {
        val key = cellKey(hashPoint(gaussian.mean))
        grid[key]?.remove(index)
        if (grid[key]?.isEmpty() == true) grid.remove(key)
    }

    fun kNearest(
        point: Series<Double>,
        gaussians: Series<ParameterGaussian>,
        k: Int,
        maxRadius: Int = 3,
    ): Series<Join<Int, Double>> {
        if (k <= 0 || gaussians.size == 0) return 0 j { _: Int -> error("empty neighbors") }
        val queryCell = hashPoint(point)
        val candidates = mutableMapOf<Int, Double>()
        var radius = 0
        while (candidates.size < k && radius <= maxRadius) {
            for (offset in offsets(radius).view) {
                val neighbor = dimensions j { i: Int -> queryCell[i] + offset[i] }
                for (index in grid[cellKey(neighbor)].orEmpty()) {
                    if (index in 0 until gaussians.size) candidates[index] = gaussians[index].mahalanobisSq(point)
                }
            }
            radius++
        }
        val iterator = candidates.entries.iterator()
        val ordered = Array(candidates.size) {
            val entry = iterator.next()
            entry.key j entry.value
        }
        ordered.sortWith(compareBy<Join<Int, Double>> { it.b }.thenBy { it.a })
        val count = minOf(k, ordered.size)
        return count j { i: Int -> ordered[i] }
    }

    private fun offsets(radius: Int): Series<Series<Int>> {
        if (radius == 0) return 1 j { _: Int -> dimensions j { _: Int -> 0 } }
        val vectors = ArrayList<Series<Int>>()
        val current = IntArray(dimensions)
        fun expand(axis: Int) {
            if (axis == dimensions) {
                val frozen = current.copyOf()
                // Only the shell for this radius; inner cells were visited at earlier radii.
                if (frozen.any { kotlin.math.abs(it) == radius }) vectors += frozen α { it }
                return
            }
            for (value in -radius..radius) {
                current[axis] = value
                expand(axis + 1)
            }
        }
        expand(0)
        return vectors.size j { i: Int -> vectors[i] }
    }

    fun clear() = grid.clear()
}
