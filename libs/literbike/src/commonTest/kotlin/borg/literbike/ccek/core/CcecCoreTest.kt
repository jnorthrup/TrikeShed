package borg.literbike.ccek.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CcecCoreTest {

    // Test Key/Element pair
    object TestKey : Key<TestElement> {
        override val elementClass = TestElement::class
        override fun factory() = TestElement(this, "default")
    }

    data class TestElement(override val keyType: KClass<out Key<*>>, val value: String) : Element

    object OtherKey : Key<OtherElement> {
        override val elementClass = OtherElement::class
        override fun factory() = OtherElement(this, 0)
    }

    data class OtherElement(override val keyType: KClass<out Key<*>>, val count: Int) : Element

    @Test
    fun emptyContextIsEmpty() {
        val ctx = Context.empty()
        assertTrue(ctx.isEmpty)
        assertFalse(ctx.isNotEmpty)
        assertEquals(0, ctx.size)
    }

    @Test
    fun plusAddsElement() {
        val ctx = Context.empty().plus(TestKey, TestElement(TestKey, "hello"))
        assertEquals(1, ctx.size)
        assertFalse(ctx.isEmpty)
    }

    @Test
    fun getReturnsCorrectElement() {
        val ctx = Context.empty()
            .plus(TestKey, TestElement(TestKey, "hello"))
            .plus(OtherKey, OtherElement(OtherKey, 42))

        val test = ctx.get<TestElement>()
        assertEquals("hello", test?.value)

        val other = ctx.get<OtherElement>()
        assertEquals(42, other?.count)
    }

    @Test
    fun newerBindingShadowsOlder() {
        val ctx = Context.empty()
            .plus(TestKey, TestElement(TestKey, "old"))
            .plus(TestKey, TestElement(TestKey, "new"))

        assertEquals("new", ctx.get<TestElement>()?.value)
        assertEquals(2, ctx.size)
    }

    @Test
    fun minusRemovesMatchingKey() {
        val ctx = Context.empty()
            .plus(TestKey, TestElement(TestKey, "hello"))
            .plus(OtherKey, OtherElement(OtherKey, 42))

        val withoutOther = ctx.minus(OtherKey)
        assertNull(withoutOther.get<OtherElement>())
        assertEquals("hello", withoutOther.get<TestElement>()?.value)
    }

    @Test
    fun minusAllElementsOfKey() {
        val ctx = Context.empty()
            .plus(TestKey, TestElement(TestKey, "first"))
            .plus(OtherKey, OtherElement(OtherKey, 1))
            .plus(TestKey, TestElement(TestKey, "second"))

        val withoutTest = ctx.minus(TestKey)
        assertNull(withoutTest.get<TestElement>())
        assertEquals(1, withoutTest.get<OtherElement>()?.count)
    }

    @Test
    fun containsReturnsTrueWhenPresent() {
        val ctx = Context.empty().plus(TestKey, TestElement(TestKey, "x"))
        assertTrue(ctx.contains(TestKey))
        assertFalse(ctx.contains(OtherKey))
    }

    @Test
    fun factoryProducesDefaultElement() {
        val default = TestKey.factory()
        assertEquals("default", default.value)
    }

    @Test
    fun chainTraversalOrder() {
        val ctx = Context.empty()
            .plus(TestKey, TestElement(TestKey, "1"))
            .plus(OtherKey, OtherElement(OtherKey, 2))
            .plus(TestKey, TestElement(TestKey, "3"))

        assertEquals(3, ctx.size)
        // Head-to-tail: most recent (3) wins
        assertEquals("3", ctx.get<TestElement>()?.value)
    }
}
