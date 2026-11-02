package borg.trikeshed.docs

import kotlin.test.*

class ReviewQuoteSpanTest {
    @Test fun preservesCrLfAndSupplementaryCharactersInUtf16Offsets() {
        val quote = "A\r\n😀B"
        val selected = assertNotNull(reviewQuoteSpan(100, 106, quote, 3, 5))
        assertEquals(103, selected.begin)
        assertEquals(105, selected.end)
        assertEquals("😀", selected.quote)
        assertEquals("A\r\n😀B", assertNotNull(reviewQuoteSpan(100, 106, quote, 0, 6)).quote)
    }

    @Test fun refusesMalformedBoundsEmptySelectionsAndSurrogateSplits() {
        val quote = "A\r\n😀B"
        assertNull(reviewQuoteSpan(100, 105, quote, 0, 1))
        assertNull(reviewQuoteSpan(-1, 5, quote, 0, 1))
        assertNull(reviewQuoteSpan(100, 106, quote, 2, 2))
        assertNull(reviewQuoteSpan(100, 106, quote, 1, 3))
        assertNull(reviewQuoteSpan(100, 106, quote, 3, 4))
        assertNull(reviewQuoteSpan(100, 106, quote, 4, 5))
        assertNull(reviewQuoteSpan(100, 106, quote, 0, 7))
        assertNull(reviewQuoteSpan(Int.MAX_VALUE - 2, Int.MAX_VALUE, "😀x", 0, 2))
        assertNull(reviewQuoteSpan(0, 1, "\uD800", 0, 1))
    }
}
