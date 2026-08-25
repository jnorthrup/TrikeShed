package borg.trikeshed.cursor

import borg.trikeshed.lib.*
import kotlin.test.Test
import kotlin.test.assertEquals

/** The PRELOAD.md fancy-indexing grammar, exercised end to end on a 3×4 cursor. */
class CursorIndexingTest {

    private val keys = s_["name", "age", "debug", "score"] α { it.toString() }
    private val rows = listOf(
        s_["ada" as Any?, 36 as Any?, true as Any?, 9.5 as Any?],
        s_["kay" as Any?, 52 as Any?, false as Any?, 8.0 as Any?],
        s_["rob" as Any?, 41 as Any?, true as Any?, 7.25 as Any?],
    )
    private val cursor: Cursor = rows.size j { r: Int -> cellsToRowVec(rows[r], keys) }

    @Test
    fun rowByInt() {
        val row: RowVec = cursor[1]
        assertEquals("kay", row[0].a)
    }

    @Test
    fun rowRangeIsLazyView() {
        val tail: Cursor = cursor[1 until 3]
        assertEquals(2, tail.size)
        assertEquals("rob", tail[1][0].a)
    }

    @Test
    fun columnReorderByOrdinals() {
        val swapped = cursor[1, 0]
        assertEquals(2, swapped.width)
        assertEquals(36, swapped[0][0].a)
        assertEquals("ada", swapped[0][1].a)
    }

    @Test
    fun columnProjectionByName() {
        val slim = cursor["name", "score"]
        assertEquals(2, slim.width)
        assertEquals("name", slim.columnNames[0].toString())
        assertEquals(9.5, slim[0][1].a)
    }

    @Test
    fun columnExclusion() {
        val noDebug = cursor[-"debug"]
        assertEquals(3, noDebug.width)
        assertEquals(listOf("name", "age", "score"), noDebug.columnNames.view.map { it.toString() })
    }

    @Test
    fun joinWidensAndCombineStacks() {
        val wide = join(cursor["name"], cursor["score"])
        assertEquals(2, wide.width)
        assertEquals(3, wide.size)
        val tall = combine(cursor, cursor)
        assertEquals(6, tall.size)
        assertEquals("ada", tall[3][0].a)
    }

    @Test
    fun filterIsDeferredUntilAccess() {
        var probes = 0
        val src: Series<Int> = 5 j { i: Int -> probes++; i }
        val evens = src.filter { it % 2 == 0 }
        assertEquals(0, probes, "filter must not scan at call time")
        assertEquals(3, evens.size)
        assertEquals(5, probes, "one full scan on first access")
        assertEquals(4, evens[2])
    }
}
