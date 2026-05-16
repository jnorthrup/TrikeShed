package borg.trikeshed.cursor

import borg.trikeshed.isam.meta.IOMemento.*
import borg.trikeshed.lib.*
import kotlin.test.*

class SimpleCursorTest {
    @Test
    fun scalarsDispatch() {
        // replicate columnar behavior: build a typed cursor, call .scalars on it
        val vscalar: Series<ColumnMeta> = _v[
            ColumnMeta("a", IoString),
            ColumnMeta("b", IoInt),
            ColumnMeta("c", IoDouble),
        ]

        val data: Series<Series<Any>> = _v[
            _v["dog", 1, 0.0],
            _v["cat", 11, 0.01],
            _v["act", 111, 0.011],
            _v["lib", 1111, 0.0111],
            _v["nil", 11111, 0.1111],
        ]

        val cursor = SimpleCursor(vscalar, data)
        cursor.scalars // just verify it doesn't throw
    }

    @Test
    fun scalarsDispatchFromEmptyData() {
        val vscalar: Series<ColumnMeta> = _v[
            ColumnMeta("a", IoString),
            ColumnMeta("b", IoInt),
        ]
        val emptyData: Series<Series<Any>> = emptySeries()
        val cursor = SimpleCursor(vscalar, emptyData)
        assertEquals(2, cursor.scalars.size)
    }

    @Test
    fun rowAccess() {
        val vscalar: Series<ColumnMeta> = _v[
            ColumnMeta("name", IoString),
            ColumnMeta("count", IoInt),
        ]
        val data: Series<Series<Any>> = _v[
            _v["alice", 1],
            _v["bob", 2],
            _v["carol", 3],
        ]
        val cursor = SimpleCursor(vscalar, data)
        val row0 = cursor.row(0)
        assertEquals("alice", row0[0]?.first)
        assertEquals(1, row0[1]?.first)
        val row2 = cursor.row(2)
        assertEquals("carol", row2[0]?.first)
        assertEquals(3, row2[1]?.first)
    }

    @Test
    fun negativeIndexWraps() {
        val vscalar: Series<ColumnMeta> = _v[ColumnMeta("x", IoInt)]
        val data: Series<Series<Any>> = _v[_v[10], _v[20], _v[30]]
        val cursor = SimpleCursor(vscalar, data)
        val last = cursor.row(-1)
        assertEquals(30, last[0]?.first)
    }

    @Test
    fun cursorSize() {
        val vscalar: Series<ColumnMeta> = _v[ColumnMeta("x", IoInt)]
        val data: Series<Series<Any>> = _v[_v[1], _v[2], _v[3], _v[4]]
        val cursor = SimpleCursor(vscalar, data)
        assertEquals(4, cursor.size)
    }

    @Test
    fun cursorEmpty() {
        val vscalar: Series<ColumnMeta> = _v[ColumnMeta("x", IoInt)]
        val data: Series<Series<Any>> = emptySeries()
        val cursor = SimpleCursor(vscalar, data)
        assertEquals(0, cursor.size)
    }
}