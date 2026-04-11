package com.seaofnodes.simple

import borg.trikeshed.cursor.ColumnMeta
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.cursor.TypeMemento
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.Series2
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import borg.trikeshed.lib.zip
import borg.trikeshed.lib.α
import borg.trikeshed.manifold.Atlas
import borg.trikeshed.manifold.Chart
import borg.trikeshed.manifold.Manifold
import borg.trikeshed.manifold.coordinatesOf
import borg.trikeshed.parse.json.JsonBitmap
import com.seaofnodes.simple.ccek.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class Chapter24Backward_05_TrikeShedSolidificationTest {
    @Test
    fun `Series equals Join of Int and accessor lambda`() {
        val series: Series<String> = 3 j { i -> "row$i" }
        assertIs<Join<Int, (Int) -> String>>(series)
        assertEquals(3, series.size)
        assertEquals("row0", series[0])
        assertEquals("row2", series[2])

        // RED: DagNode.inputs is List<Int>, not Series<Int>
        val dag = DagNode(1, "Add", listOf(10, 20), emptyList(), "int")
        val dagInputs: Series<Int> = dag.inputs as Series<Int>
        assertEquals(2, dagInputs.size)
    }

    @Test
    fun `Series alpha-conversion should parallel IterPeeps peephole optimization`() {
        val nodeIds = listOf(1, 2, 3, 4, 5)
        val idSeries: Series<Int> = nodeIds.toSeries()
        val doubled: Series<Int> = idSeries α { it * 2 }
        assertEquals(10, doubled[4])

        // RED: no bridge from Series α-conversion to IterPeeps peephole iteration
        // IterPeeps operates on Node._nid via WorkList, Series α operates on typed values
        val peepResult: Series<Int> = idSeries.toPeepholeResult()
        assertEquals(doubled.size, peepResult.size)
    }

    @Test
    fun `Manifold chart transition matches compiler chained comparison structure`() {
        val chartA =
            Chart<Int, Int>(
                name = "lower_bound",
                dimension = 1,
                contains = { p -> p >= 0 },
                project = { p -> coordinatesOf(p) },
                embed = { c -> c[0] },
            )
        val chartB =
            Chart<Int, Int>(
                name = "upper_bound",
                dimension = 1,
                contains = { p -> p <= 4 },
                project = { p -> coordinatesOf(p) },
                embed = { c -> c[0] },
            )
        val atlas = Atlas(listOf(chartA, chartB).toSeries())
        val manifold = Manifold(atlas)
        assertEquals(2, atlas.size)

        val transition = manifold.transition("lower_bound", "upper_bound", coordinatesOf(2))
        assertNotNull(transition)
        assertEquals(2, transition!![0])

        // RED: no bridge from compiler IR comparison node count to atlas chart count
        val irComparisonCount = CompilerShape.countComparisons("0 < x < 4")
        assertEquals(atlas.size, irComparisonCount)
    }

    @Test
    fun `Cursor RowVec join structure matches DuckDB column series plus metadata`() {
        val values: Series<Any?> = 3 j { i -> "val$i" }
        val meta: Series<() -> ColumnMeta> =
            3 j { _ ->
                {
                    ColumnMeta(
                        "col",
                        object : TypeMemento {
                            override val networkSize: Int? = 4
                        },
                    )
                }
            }
        val rowVec: RowVec = values.zip(meta)
        assertIs<Join<Any?, () -> ColumnMeta>>(rowVec)

        val cursor: Cursor = 1 j { _ -> rowVec }
        assertEquals(1, cursor.size)
        assertEquals("val0", cursor[0].a)

        // RED: DuckDB has no direct toCursor() factory producing this RowVec shape
        val duckCursor: Cursor =
            borg.trikeshed.duck
                .DuckSeries()
                .toCursor()
        assertEquals(cursor.size, duckCursor.size)
    }

    @Test
    fun `JsonBitmap JsStateEvent maps to seaofnodes TokenKind`() {
        val json = "{[,"
        val bytes = json.encodeToByteArray().toUByteArray()
        val encoded = JsonBitmap.encode(bytes)
        assertEquals(2, encoded.size)

        // RED: no mapping function from JsonBitmap.JsStateEvent to TokenKind
        val openMapping = JsonBitmap.JsStateEvent.ScopeOpen.toTokenKind()
        assertEquals(TokenKind.OP, openMapping)

        val closeMapping = JsonBitmap.JsStateEvent.ScopeClose.toTokenKind()
        assertEquals(TokenKind.OP, closeMapping)

        val delimMapping = JsonBitmap.JsStateEvent.ValueDelim.toTokenKind()
        assertEquals(TokenKind.OP, delimMapping)
    }

    @Test
    fun `JsonBitmap decode quote-escape state machine is WAM trail`() {
        val json = """{"key":"value","num":42}"""
        val bytes = json.encodeToByteArray().toUByteArray()
        val encoded = JsonBitmap.encode(bytes)
        assertTrue(encoded.isNotEmpty())

        val decoded = JsonBitmap.decode(arrayOf(encoded), bytes.size.toUInt())
        assertTrue(decoded.isNotEmpty())

        // RED: no WAM trail format exists to compare against JsonBitmap decode output
        val wamTrail = WamTrail.fromBitplanes(decoded)
        assertEquals(WamTrail.TrailEntry.OpenScope, wamTrail.entries[0])
        assertEquals(WamTrail.TrailEntry.CloseScope, wamTrail.entries.last())
    }
}

// RED: these types/functions do not exist
private object CompilerShape {
    fun countComparisons(src: String): Int = TODO()
}

private fun JsonBitmap.JsStateEvent.toTokenKind(): TokenKind = TODO()

private fun <T> Series<T>.toPeepholeResult(): Series<Int> = TODO()

private class WamTrail private constructor(
    val entries: List<TrailEntry>,
) {
    sealed class TrailEntry {
        object OpenScope : TrailEntry()

        object CloseScope : TrailEntry()

        object Delimiter : TrailEntry()
    }

    companion object {
        fun fromBitplanes(bitplanes: Array<*>): WamTrail = TODO()
    }
}
