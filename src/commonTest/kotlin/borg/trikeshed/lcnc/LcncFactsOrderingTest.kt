package borg.trikeshed.lcnc

import kotlin.test.Test
import kotlin.test.assertEquals

class LcncFactsOrderingTest {
    @Test
    fun shapesUseKeyOrderRatherThanAssertionOrder() {
        val facts = LcncFacts.parse("(shape Zeta field) (shape Alpha field) (shape Beta other)")

        assertEquals(listOf("Alpha", "Beta", "Zeta"), facts.shapes().keys.toList())
        assertEquals(listOf("field"), facts.shapes()["Alpha"])
    }

    @Test
    fun equallySpecificLiteralShapesChooseTheFirstOrderedKind() {
        val facts = LcncFacts.parse("(shape Zeta field) (shape Alpha field)")
        val node = LcncNode("literal", "literal", params = mapOf("value" to "[{\"field\":1}]"))

        assertEquals("Alpha", facts.refineLiteral(node, "value", "json"))
    }
}
