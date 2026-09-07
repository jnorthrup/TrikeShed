@file:Suppress("NonAsciiCharacters")

package borg.trikeshed.lib

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

class JoinFactoryTest {
    @Test
    fun callAndLiteralPreserveReferencesNullsAndDestructuring() {
        val value = Any()
        val joined: Join<Any, String?> = `⋈`(value, null)
        val (left, right) = joined
        assertSame(value, left)
        assertNull(right)

        val literal: Join<String?, Any> = `⋈`[null, value]
        assertNull(literal.a)
        assertSame(value, literal.b)
    }

    @Test
    fun pairConversionUsesExistingPackingWithoutChangingFloatBits() {
        val bits = 0xFFC12345.toInt()
        val joined = `⋈`(Int.MIN_VALUE to Float.fromBits(bits))
        assertIs<IntFloatJoin>(joined)
        assertEquals(Int.MIN_VALUE, joined.a)
        assertEquals(bits, joined.b.toRawBits())

        val value = Any()
        val references = `⋈`(value to null)
        assertSame(value, references.a)
        assertNull(references.b)
    }

    @Test
    fun seriesOracleIsInferredAndRemainsLazyThroughProjection() {
        var reads = 0
        val series = `⋈`(4) { i -> reads++; i * 10 }
        val projected = series α { it + 1 }
        assertEquals(4, series.size)
        assertEquals(0, reads)
        assertEquals(21, projected[2])
        assertEquals(1, reads)
    }

    @Test
    fun nonIntegerDomainsAndLiteralOraclesKeepTheirIdentity() {
        val domain = "scope"
        val oracle: (String) -> Int = { it.length }
        val indexed: MetaSeries<String, Int> = `⋈`(domain, oracle)
        assertSame(domain, indexed.domain)
        assertSame(oracle, indexed.b)
        assertEquals(5, indexed["child"])

        val literal = `⋈`[domain, { key -> key.length }]
        assertEquals(4, literal["leaf"])
    }

    @Test
    fun zipReadsOnlyTheRequestedPositionAndRetainsPacking() {
        var leftReads = 0
        var rightReads = 0
        val left = `⋈`(3) { i -> leftReads++; i }
        val right = `⋈`(3) { i -> rightReads++; i.toFloat() }
        val joined = `⋈`.zip(left, right)
        assertEquals(3, joined.size)
        assertEquals(0, leftReads)
        assertEquals(0, rightReads)

        val entry = joined[2]
        assertIs<IntFloatJoin>(entry)
        assertEquals(2, entry.a)
        assertEquals(2.0f, entry.b)
        assertEquals(1, leftReads)
        assertEquals(1, rightReads)
    }

    @Test
    fun zipRejectsUnequalBoundsWithoutReadingEitherOracle() {
        var reads = 0
        val left = `⋈`(1) { reads++ }
        val right = `⋈`(2) { reads++ }
        assertFailsWith<IllegalArgumentException> { `⋈`.zip(left, right) }
        assertFailsWith<IllegalArgumentException> { `⋈`.zip(right, left) }
        assertEquals(0, reads)
        assertEquals(0, `⋈`.zip(emptySeriesOf<Int>(), emptySeriesOf<String>()).size)
    }
}
