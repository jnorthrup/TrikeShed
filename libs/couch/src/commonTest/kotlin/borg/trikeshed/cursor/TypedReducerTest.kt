package borg.trikeshed.cursor

import borg.trikeshed.lib.*
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * RED test for U6: typed Reducer<T, R> interface.
 *
 * RowReducer = (Any?, Any?) -> Any? is the untyped alias used in Cursor.groupBy.
 * Reducer<T, R> is the typed equivalent with explicit type parameters.
 *
 * This test pins the Reducer interface contract without requiring
 * full Cursor/groupBy infrastructure.
 */
class TypedReducerTest {

    /** Sum reducer for Int values */
    class SumInt : Reducer<Int, Int> {
        override val zero: Int get() = 0
        override fun combine(acc: Int, element: Int): Int = acc + element
    }

    /** Count reducer — ignores input element, just counts */
    class Count<T> : Reducer<T, Int> {
        override val zero: Int get() = 0
        override fun combine(acc: Int, element: T): Int = acc + 1
    }

    /** Collect-to-list reducer */
    class CollectList<T> : Reducer<T, List<T>> {
        override val zero: List<T> get() = emptyList()
        override fun combine(acc: List<T>, element: T): List<T> = acc + element
    }

    @Test
    fun `Reducer SumInt has correct zero and combine`() {
        val r = SumInt()
        assertEquals(0, r.zero)
        assertEquals(5, r.combine(2, 3))
        assertEquals(30, r.combine(10, 20))
    }

    @Test
    fun `Reducer Count has correct zero and combine`() {
        val r = Count<String>()
        assertEquals(0, r.zero)
        assertEquals(1, r.combine(0, "ignored"))
        assertEquals(3, r.combine(2, "also ignored"))
    }

    @Test
    fun `Reducer CollectList accumulates elements typed`() {
        val r = CollectList<Int>()
        assertEquals(emptyList<Int>(), r.zero)
        val step1 = r.combine(r.zero, 1)
        assertEquals(listOf(1), step1)
        val step2 = r.combine(step1, 2)
        assertEquals(listOf(1, 2), step2)
    }

    @Test
    fun `Series fold with typed Reducer produces correct result`() {
        // This verifies Reducer integrates with Series.fold
        val s: Series<Int> = (1..5).toList().toSeries()
        val result = s.fold(SumInt())
        assertEquals(15, result) // 1+2+3+4+5
    }

    @Test
    fun `Series fold with Count reducer produces correct result`() {
        val s: Series<String> = listOf("a", "b", "c", "d").toSeries()
        val result = s.fold(Count<String>())
        assertEquals(4, result)
    }

    @Test
    fun `Series fold with CollectList reducer produces correct result`() {
        val s: Series<Double> = listOf(1.0, 2.0, 3.0).toSeries()
        @Suppress("UNCHECKED_CAST")
        val result = s.fold(CollectList<Double>()) as List<Double>
        assertEquals(listOf(1.0, 2.0, 3.0), result)
    }
}
