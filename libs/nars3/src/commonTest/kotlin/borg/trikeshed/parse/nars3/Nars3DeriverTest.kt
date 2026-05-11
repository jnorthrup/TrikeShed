package borg.trikeshed.parse.nars3

import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.toSeries
import borg.trikeshed.parse.narsive.NarsiveElement
import borg.trikeshed.parse.narsive.NarsiveElementKind
import borg.trikeshed.parse.narsive.NarsiveOperator
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Nars3DeriverTest {
    @Test
    fun deriveNonConditionalPairsEmitsInheritanceAndSimilaritySideBySide() = runTest {
        val messages = Nars3Machine(this).deriveNonConditionalPairs(
            messageWithOperator("bird --> animal", NarsiveOperator.INHERITANCE),
            messageWithOperator("robin <-> bird", NarsiveOperator.SIMILARITY),
        )

        assertEquals(2, messages.size)
        assertEquals("inheritance: bird --> animal | robin <-> bird", messages[0].content)
        assertEquals("similarity: bird --> animal | robin <-> bird", messages[1].content)
        assertTrue(messages[0].budget.priority > 0f)
        assertTrue(messages[1].budget.priority > 0f)
    }

    @Test
    fun deriveNonConditionalPairsIgnoresConditionalOperators() = runTest {
        val messages = Nars3Machine(this).deriveNonConditionalPairs(
            messageWithOperator("bird --> animal", NarsiveOperator.INHERITANCE),
            messageWithOperator("bird ==> flyer", NarsiveOperator.IMPLICATION),
        )

        assertEquals(1, messages.size)
        assertEquals("inheritance: bird --> animal | bird ==> flyer", messages[0].content)
    }

    @Test
    fun deriveAllWaitsForEveryNonConditionalPairBeforeReturning() = runTest {
        val input = 4 j { index: Int ->
            when (index) {
                0 -> messageWithOperator("bird --> animal", NarsiveOperator.INHERITANCE)
                1 -> messageWithOperator("robin <-> bird", NarsiveOperator.SIMILARITY)
                2 -> messageWithOperator("sparrow --> bird", NarsiveOperator.INHERITANCE)
                else -> messageWithOperator("bird ==> flyer", NarsiveOperator.IMPLICATION)
            }
        }

        val derived = mutableListOf<String>()
        Nars3Machine(this).deriveAll(input, this) { derived += it.content }

        assertEquals(
            listOf(
                "inheritance: bird --> animal | robin <-> bird",
                "inheritance: bird --> animal | sparrow --> bird",
                "inheritance: robin <-> bird | sparrow --> bird",
                "similarity: bird --> animal | robin <-> bird",
                "similarity: robin <-> bird | sparrow --> bird",
            ),
            derived.sorted(),
        )
    }

    private fun messageWithOperator(content: String, operator: NarsiveOperator): Nars3Message =
        Nars3Message(
            content = content,
            budget = Nars3Budget(priority = 0.8f, durability = 0.7f, quality = 0.6f),
            elements = 1 j { _: Int ->
                NarsiveElement(
                    kind = NarsiveElementKind.COPULA,
                    span = 0 j content.length,
                    lexeme = operator.asciiForm.toSeries(),
                )
            },
        )
}
