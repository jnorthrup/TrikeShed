package borg.trikeshed.cutedsl

/**
 * CuTe-style Layout abstraction.
 *
 * A Layout describes the mapping from logical coordinates (Shape)
 * to physical memory indices (Stride).
 *
 * Shape = nested tuple of extents (e.g., [M, N] or [M, N, K])
 * Stride = matching tuple of strides for each dimension
 *
 * This is the core abstraction from CuTe (CUTLASS Templates) and ThunderKittens.
 */
class Layout(
    val shape: IntArray,
    val stride: IntArray
) {
    init {
        require(shape.size == stride.size) { "Shape and stride must have same rank" }
    }

    val rank: Int get() = shape.size

    val size: Int get() = shape.fold(1) { acc, s -> acc * s }

    /**
     * Compute linear index from logical coordinates.
     * index = sum_i (coord[i] * stride[i])
     */
    operator fun get(vararg coords: Int): Int {
        require(coords.size == rank) { "Coordinate rank must match layout rank" }
        var idx = 0
        for (i in coords.indices) {
            idx += coords[i] * stride[i]
        }
        return idx
    }

    /**
     * Transpose the layout (swap last two dimensions for 2D, reverse for ND).
     */
    fun transposed(): Layout {
        if (rank < 2) return this
        val newShape = shape.copyOf()
        val newStride = stride.copyOf()
        // Swap last two dimensions
        val i = rank - 2
        val j = rank - 1
        newShape[i] = shape[j]
        newShape[j] = shape[i]
        newStride[i] = stride[j]
        newStride[j] = stride[i]
        return Layout(newShape, newStride)
    }

    /**
     * Partition layout into tiles of given shape.
     * Returns a list of sub-layouts covering the original layout.
     */
    fun divideTile(tileShape: IntArray): List<Layout> {
        require(tileShape.size == rank) { "Tile shape rank must match layout rank" }

        val tiles = mutableListOf<Layout>()
        val numTiles = IntArray(rank) { shape[it] / tileShape[it] }

        // Generate all tile coordinates
        val coords = IntArray(rank)
        fun generateTiles(dim: Int) {
            if (dim == rank) {
                // Compute tile offset and sub-layout
                val offset = IntArray(rank) { coords[it] * tileShape[it] }
                val tileStride = IntArray(rank) { stride[it] }
                val tileLayout = Layout(tileShape, tileStride)
                tiles.add(tileLayout)
                return
            }
            for (c in 0 until numTiles[dim]) {
                coords[dim] = c
                generateTiles(dim + 1)
            }
        }
        generateTiles(0)
        return tiles
    }

    /**
     * Create a layout from a cursor shape (row-major by default).
     */
    companion object {
        fun fromCursorShape(shape: IntArray): Layout {
            val stride = IntArray(shape.size)
            var s = 1
            for (i in shape.indices.reversed()) {
                stride[i] = s
                s *= shape[i]
            }
            return Layout(shape, stride)
        }

        /**
         * Create a column-major layout from shape.
         */
        fun columnMajor(shape: IntArray): Layout {
            val stride = IntArray(shape.size)
            var s = 1
            for (i in shape.indices) {
                stride[i] = s
                s *= shape[i]
            }
            return Layout(shape, stride)
        }

        /**
         * Create a tiled layout for MMA operations.
         * E.g., MMA 16x16x16 -> layout with shape [M/16, N/16, K/16, 16, 16, 16]
         */
        fun mmaTiled(
            m: Int, n: Int, k: Int,
            mmaM: Int = 16, mmaN: Int = 16, mmaK: Int = 16
        ): Layout {
            val shape = intArrayOf(m / mmaM, n / mmaN, k / mmaK, mmaM, mmaN, mmaK)
            val stride = intArrayOf(n * k / (mmaN * mmaK), m * k / (mmaM * mmaK), 1, mmaN * mmaK, mmaK, 1)
            return Layout(shape, stride)
        }
    }

    override fun toString(): String {
        return "Layout(shape=${shape.joinToString(", ", "[", "]")}, stride=${stride.joinToString(", ", "[", "]")})"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Layout) return false
        return shape.contentEquals(other.shape) && stride.contentEquals(other.stride)
    }

    override fun hashCode(): Int = 31 * shape.contentHashCode() + stride.contentHashCode()
}

/**
 * A Tile is a view into memory described by a Layout.
 * It combines a Layout with a data pointer/array.
 */
class Tile<T>(val layout: Layout, val data: Array<T>) {
    init {
        require(data.size >= layout.size) { "Data array too small for layout" }
    }

    operator fun get(vararg coords: Int): T = data[layout.get(*coords)]

    operator fun set(vararg coords: Int, value: T) {
        data[layout.get(*coords)] = value
    }

    /**
     * Extract a sub-tile (slice) from this tile.
     */
    fun slice(
        offsets: IntArray,
        extents: IntArray
    ): Tile<T> {
        require(offsets.size == layout.rank && extents.size == layout.rank) {
            "Offset and extent rank must match layout rank"
        }

        // Compute new layout for the slice
        val newShape = extents
        val newStride = layout.stride.copyOf()

        // Compute base offset
        var baseOffset = 0
        for (i in offsets.indices) {
            baseOffset += offsets[i] * layout.stride[i]
        }

        // Create sliced data view (copy for now, could be view later)
        val sliceSize = extents.fold(1) { a, b -> a * b }
        val sliceData = Array<T>(sliceSize) { data[baseOffset + it] }

        return Tile(Layout(newShape, newStride), sliceData)
    }

    /**
     * 2D slice convenience.
     */
    fun slice(rowStart: Int, colStart: Int, rows: Int, cols: Int): Tile<T> {
        require(layout.rank >= 2) { "Layout must be at least 2D for 2D slice" }
        val offsets = IntArray(layout.rank) { 0 }
        val extents = layout.shape.copyOf()
        offsets[layout.rank - 2] = rowStart
        offsets[layout.rank - 1] = colStart
        extents[layout.rank - 2] = rows
        extents[layout.rank - 1] = cols
        return slice(offsets, extents)
    }

    override fun toString(): String {
        return "Tile(layout=$layout, dataSize=${data.size})"
    }
}