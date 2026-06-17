package borg.trikeshed.cutedsl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * TDD Red: Layout and Tile Abstractions
 *
 * These tests define the expected behavior for CuTe-style layouts and tiles
 * before implementation exists. They should FAIL initially (RED phase).
 *
 * CuTe Layout = (Shape, Stride) pair describing logical shape and physical memory mapping
 * Shape = nested tuple of ints (e.g., (32, 64), (16, 16, 4))
 * Stride = matching nested tuple of strides
 */
class LayoutTileRedTest {

    @Test
    fun `Layout construction from shape and stride`() {
        // Layout((32, 64), (64, 1)) - row-major 32x64
        val layout = Layout(shape = intArrayOf(32, 64), stride = intArrayOf(64, 1))
        assertNotNull(layout)
        assertEquals(intArrayOf(32, 64), layout.shape)
        assertEquals(intArrayOf(64, 1), layout.stride)
    }

    @Test
    fun `Layout linear index calculation`() {
        val layout = Layout(shape = intArrayOf(32, 64), stride = intArrayOf(64, 1))
        // (i, j) -> i*64 + j*1
        assertEquals(0, layout[0, 0])
        assertEquals(63, layout[0, 63])
        assertEquals(64, layout[1, 0])
        assertEquals(32 * 64 - 1, layout[31, 63])
    }

    @Test
    fun `Layout composition - nested shapes`() {
        // Nested layout: ((16, 16), (4, 8)) with matching strides
        val layout = Layout(
            shape = intArrayOf(16, 16, 4, 8),
            stride = intArrayOf(64, 4, 16, 1)
        )
        assertNotNull(layout)
        // Should support flattened and hierarchical indexing
    }

    @Test
    fun `Tile construction from layout and pointer`() {
        val layout = Layout(shape = intArrayOf(32, 64), stride = intArrayOf(64, 1))
        val data = DoubleArray(32 * 64)
        val tile = Tile(layout, data)
        assertNotNull(tile)
        assertEquals(layout, tile.layout)
        assertEquals(data, tile.data)
    }

    @Test
    fun `Tile element access via layout`() {
        val layout = Layout(shape = intArrayOf(32, 64), stride = intArrayOf(64, 1))
        val data = DoubleArray(32 * 64) { it.toDouble() }
        val tile = Tile(layout, data)

        assertEquals(0.0, tile[0, 0])
        assertEquals(63.0, tile[0, 63])
        assertEquals(64.0, tile[1, 0])
    }

    @Test
    fun `Tile slice / sub-tile extraction`() {
        val layout = Layout(shape = intArrayOf(64, 64), stride = intArrayOf(64, 1))
        val data = DoubleArray(64 * 64) { (it % 64).toDouble() }
        val tile = Tile(layout, data)

        // Extract 32x32 subtile at offset (16, 16)
        val subtile = tile.slice(16, 16, 32, 32)
        assertNotNull(subtile)
        assertEquals(intArrayOf(32, 32), subtile.layout.shape)
        assertEquals(16.0, subtile[0, 0]) // Original [16, 16]
        assertEquals(31.0, subtile[15, 15]) // Original [31, 31]
    }

    @Test
    fun `Tile layout transformation - transpose`() {
        val layout = Layout(shape = intArrayOf(32, 64), stride = intArrayOf(64, 1))
        val transposed = layout.transposed()
        assertEquals(intArrayOf(64, 32), transposed.shape)
        assertEquals(intArrayOf(1, 64), transposed.stride)
    }

    @Test
    fun `Layout size and rank`() {
        val layout = Layout(shape = intArrayOf(32, 64, 4), stride = intArrayOf(256, 4, 1))
        assertEquals(3, layout.rank)
        assertEquals(32 * 64 * 4, layout.size)
    }

    @Test
    fun `Layout divide / tile partitioning for MMA`() {
        // Partition 64x64 into 16x16 MMA tiles
        val layout = Layout(shape = intArrayOf(64, 64), stride = intArrayOf(64, 1))
        val tiles = layout.divideTile(intArrayOf(16, 16))
        assertNotNull(tiles)
        assertEquals(16, tiles.size) // 4x4 = 16 tiles of 16x16
    }

    @Test
    fun `Layout composition with CursorTensor shape`() {
        // Integration point: CursorTensor -> CuTe Layout
        val cursorShape = intArrayOf(128, 256) // [batch, features]
        val layout = Layout.fromCursorShape(cursorShape)
        assertEquals(intArrayOf(128, 256), layout.shape)
        assertEquals(intArrayOf(256, 1), layout.stride) // Row-major default
    }
}

/**
 * Placeholder classes for RED phase - will be replaced by real implementations
 */
class Layout(
    val shape: IntArray,
    val stride: IntArray
) {
    val rank: Int get() = shape.size
    val size: Int get() = shape.reduce { acc, s -> acc * s }

    operator fun get(vararg indices: Int): Int {
        var idx = 0
        for (i in indices.indices) {
            idx += indices[i] * stride[i]
        }
        return idx
    }

    fun transposed(): Layout {
        val newShape = shape.reversed().toIntArray()
        val newStride = stride.reversed().toIntArray()
        return Layout(newShape, newStride)
    }

    fun divideTile(tileShape: IntArray): List<Layout> {
        // Placeholder - returns empty list to make test fail
        return emptyList()
    }

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
    }
}

class Tile<T>(val layout: Layout, val data: Array<T>) {
    operator fun get(vararg indices: Int): T = data[layout[*indices]]

    fun slice(rowStart: Int, colStart: Int, rows: Int, cols: Int): Tile<T> {
        // Placeholder - throws to make test fail
        throw UnsupportedOperationException("slice not implemented")
    }
}