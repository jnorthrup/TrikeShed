@file:Suppress("NonAsciiCharacters")
package borg.trikeshed.cursor

import borg.trikeshed.lib.*
import kotlin.test.*

/**
 * Basic Series and cursor literal tests — ported from columnar's SimpleCursorTest
 */
class SeriesLiteralTest {
    @Test
    fun `series literal creates correct size`() {
        val s = s_[1, 2, 3, 4, 5]
        assertEquals(5, s.size)
        assertEquals(1, s[0])
        assertEquals(5, s[4])
    }

    @Test
    fun `join operator creates pairs`() {
        val pair = "key" j 42
        assertEquals("key", pair.a)
        assertEquals(42, pair.b)
    }

    @Test
    fun `series of strings`() {
        val names = s_["dog", "cat", "act", "lib", "nil"]
        assertEquals(5, names.size)
        assertEquals("dog", names[0])
        assertEquals("nil", names[4])
    }

    @Test
    fun `series projection with alpha`() {
        val numbers = s_[1, 2, 3, 4, 5]
        val doubled = numbers α { it * 2 }
        assertEquals(5, doubled.size)
        assertEquals(2, doubled[0])
        assertEquals(10, doubled[4])
    }

    @Test
    fun `cursor range selection`() {
        val series = s_[0, 1, 2, 3, 4, 5, 6, 7, 8, 9]
        val range = series[2..5]
        assertEquals(4, range.size)
        assertEquals(2, range[0])
        assertEquals(5, range[3])
    }
}
