package borg.trikeshed.indicator

import borg.trikeshed.cursor.*
import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.lib.*
import borg.trikeshed.lib.get
import kotlin.test.*

class FeatureExtractorCursorTest {
    @Test
    fun namedNumericColumnsProduceFeatureRowsAndMetadata() {
        val schema = s_[
            ColumnMeta("volume", IOMemento.IoLong),
            ColumnMeta("unused", IOMemento.IoString),
            ColumnMeta(StringBuilder("low"), IOMemento.IoFloat),
            ColumnMeta("close", IOMemento.IoInt),
            ColumnMeta("high", IOMemento.IoDouble),
        ]
        val source: Cursor = 3 j { row: Int ->
            schema.size j { column: Int ->
                val value: Any = when (column) {
                    0 -> 1000L + row * 100L
                    1 -> "ignored"
                    2 -> 95f + row
                    3 -> 100 + row
                    else -> 105.0 + row
                }
                value j { schema[column] }
            }
        }
        val expected = FeatureExtractor.compute(
            3 j { row: Int -> 100.0 + row },
            3 j { row: Int -> 105.0 + row },
            3 j { row: Int -> 95.0 + row },
            3 j { row: Int -> 1000.0 + row * 100.0 },
        )
        val actual = FeatureExtractor.compute(source)
        assertNotSame(source, actual)
        assertEquals(3, actual.size)
        val names = actual.b(0).right.α { it().name.toString() }.view.toList()
        assertEquals(expected.keys.toList(), names)
        assertTrue("return_1d" in names)
        assertTrue("vwap" in names)
        for (row in 0 until actual.size) {
            val result = actual.b(row)
            assertEquals(expected.size, result.size)
            names.forEachIndexed { column, name ->
                val cell = result.b(column)
                assertEquals(IOMemento.IoDouble, cell.b().type)
                assertEquals(name, cell.b().name)
                assertEquals(expected.getValue(name)[row], assertIs<Double>(cell.a), "[$row,$name]")
            }
        }
        val returns = names.indexOf("return_1d")
        assertEquals(0.01, actual.b(1).b(returns).a as Double, 1e-12)
    }

    @Test
    fun emptyCursorDoesNotReadAnExemplar() {
        val source: Cursor = 0 j { _: Int -> error("Empty cursor has no schema row") }
        assertEquals(0, FeatureExtractor.compute(source).size)
    }

    @Test
    fun missingInputColumnIsRejected() {
        val source: Cursor = 1 j { _: Int ->
            1 j { _: Int -> 100.0 j { ColumnMeta("close", IOMemento.IoDouble) } }
        }
        val failure = assertFailsWith<IllegalStateException> { FeatureExtractor.compute(source) }
        assertContains(failure.message.orEmpty(), "high")
    }
}
