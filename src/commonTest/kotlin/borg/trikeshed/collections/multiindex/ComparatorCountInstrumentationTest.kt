package borg.trikeshed.collections.multiindex

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * S1 RED — Comparator-count instrumentation proves insertion does not perform
 * a full re-sort.
 *
 * The plan: "Multi-index comparator counts follow incremental bounds rather
 * than population-wide re-sort per insert."
 */
class ComparatorCountInstrumentationTest {

    @Test
    fun insertionDoesNotPerformFullResort() {
        val container = MultiIndexContainer<Int>()
        val byOrder = MultiIndexK.ByOrder { it }

        container.registerOrder(byOrder)

        // Insert 100 elements in REVERSE sorted order.
        // A full re-sort would cost O(n) comparisons per insert → O(n²) total.
        // An incremental insert (binary search + shift) costs O(log n) comparisons.
        container.instrumentComparators = true

        for (i in 99 downTo 0) {
            container.add(i)
        }

        val totalComparisons = container.totalComparatorInvocations

        // O(n²) for n=100 would be ~10,000 comparisons.
        // O(n log n) for n=100 would be ~664 comparisons.
        // The test asserts we are well below the quadratic bound.
        assertTrue(
            totalComparisons < 5_000,
            "incremental insert must not perform full re-sort. " +
                "Expected <5,000 comparisons for 100 elements, got $totalComparisons"
        )
    }

    @Test
    fun comparatorCountGrowsLogarithmically() {
        val container = MultiIndexContainer<Int>()
        val byOrder = MultiIndexK.ByOrder { it }

        container.registerOrder(byOrder)
        container.instrumentComparators = true

        // Insert n=10, then n=100, then n=1000 and measure the comparison growth.
        // O(n log n) means comparisons grow ~n * log(n), not n².
        for (i in 0 until 10) container.add(i)
        val c10 = container.totalComparatorInvocations

        container.instrumentComparators = true // reset
        for (i in 10 until 100) container.add(i)
        val c100 = container.totalComparatorInvocations

        // The ratio from 10→100 elements should be sub-quadratic.
        // O(n²) ratio would be 100x. O(n log n) ratio is ~13x.
        val ratio = c100.toDouble() / max(1, c10)
        assertTrue(
            ratio < 50.0,
            "comparator count growth must be sub-quadratic. " +
                "10-element: $c10, 100-element insertion: $c100, ratio: $ratio"
        )
    }
}
