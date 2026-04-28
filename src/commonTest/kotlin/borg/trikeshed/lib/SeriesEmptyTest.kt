package borg.trikeshed.lib

import kotlin.test.Test
import kotlin.test.assertFailsWith

class SeriesEmptyTest {
    @Test
    fun `empty series should throw NoSuchElementException when indexed`() {
        val empty = emptySeries<Int>()
        assertFailsWith<NoSuchElementException> {
            // access index 0 on empty series should throw
            val x = empty[0]
            x
        }
    }
}
