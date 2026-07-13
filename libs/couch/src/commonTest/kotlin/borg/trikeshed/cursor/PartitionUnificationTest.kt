package borg.trikeshed.cursor

import borg.trikeshed.lib.*
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * RED test for U5: `Series.div(Int)` and `Cursor.groupBy` share partition algebra.
 *
 * Both operations construct a Series of parts using the same pattern:
 *   `count j { subRange(it) }`
 *
 * This test documents and verifies the shared construction algebra.
 */
class PartitionUnificationTest {

    // ── Series.div ──────────────────────────────────────────────────────────

    @Test
    fun `div splits a series into d equal-sized sub-series`() {
        // Build a simple Series: [0, 2, 4, 6, 8, 10]
        val s: Series<Int> = (0..5).toList().toSeries() α { it * 2 }

        @Suppress("UNCHECKED_CAST")
        val parts: Series<Series<Int>> = s.div(3) as Series<Series<Int>>

        assertEquals(3, parts.size)
        // Each part has 2 elements (size / d = 6 / 3 = 2)
        assertEquals(2, (parts[0]).size)
        assertEquals(2, (parts[1]).size)
        assertEquals(2, (parts[2]).size)
    }

    // ── Shared partition pattern ─────────────────────────────────────────────
    //
    // The div operator uses the same construction as groupBy:
    //   count j { i -> subRange(i) }
    //
    // div:     d       j { x -> subSize j { i -> this[i + x * subSize] } }
    // groupBy: keys.size j { cy -> colCount j { cx -> ... } }
    //
    // Both produce a Series of Series/Cursor by partitioning an input.

    @Test
    fun `div manual construction produces same structure as div operator`() {
        // Create a Series of 8 integers
        val s: Series<Int> = (0..7).toList().toSeries()

        // Manual partition following the same pattern as div operator
        val d = 4
        val subSize = s.size / d
        val manual: Series<Series<Int>> = d j { x: Int ->
            subSize j { i: Int -> s[i + x * subSize] }
        }

        // Compare with operator form
        @Suppress("UNCHECKED_CAST")
        val viaOperator: Series<Series<Int>> = s.div(d) as Series<Series<Int>>

        assertEquals(manual.size, viaOperator.size)
        for (i in 0 until manual.size) {
            assertEquals(manual[i].size, viaOperator[i].size)
            for (j in 0 until manual[i].size) {
                assertEquals(manual[i][j], viaOperator[i][j])
            }
        }
    }

    @Test
    fun `div with remainder distributes extra elements to last partition`() {
        // 5 elements, divided by 2 → parts of size floor(5/2)=2 and 2+1=3
        val s: Series<Int> = (0..4).toList().toSeries()
        @Suppress("UNCHECKED_CAST")
        val parts: Series<Series<Int>> = s.div(2) as Series<Series<Int>>

        assertEquals(2, parts.size)
        assertEquals(2, parts[0].size)
        assertEquals(3, parts[1].size)  // remainder goes to last
    }
}
