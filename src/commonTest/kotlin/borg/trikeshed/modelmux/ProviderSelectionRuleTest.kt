package borg.trikeshed.modelmux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ProviderSelectionRuleTest {

    @Test
    fun selectionRuleEqualsByContent() {
        assertEquals(SelectionRule.MinCost, SelectionRule.MinCost)
        assertEquals(SelectionRule.SpecificProvider("anthropic"), SelectionRule.SpecificProvider("anthropic"))
        assertNotEquals(SelectionRule.SpecificProvider("anthropic"), SelectionRule.SpecificProvider("openai"))
    }

    @Test
    fun selectionRuleHashCodeIsConsistent() {
        assertEquals(SelectionRule.MinCost.hashCode(), SelectionRule.MinCost.hashCode())
        assertEquals(SelectionRule.Fallback("a", "b").hashCode(), SelectionRule.Fallback("a", "b").hashCode())
    }

    @Test
    fun providerDescriptorEqualityByCost() {
        val d1 = ProviderDescriptor("p1", "P1", 0.1, 100)
        val d2 = ProviderDescriptor("p1", "P1", 0.1, 100)
        assertEquals(d1, d2)
    }

    @Test
    fun promptMessageEqualityByKind() {
        val msg1: PromptMessage = PromptMessage.User("hi")
        val msg2: PromptMessage = PromptMessage.Assistant("hi")
        assertNotEquals(msg1, msg2)
    }
}
