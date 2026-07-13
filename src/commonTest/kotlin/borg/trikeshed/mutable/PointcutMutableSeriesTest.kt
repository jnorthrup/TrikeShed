package borg.trikeshed.mutable

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PointcutMutableSeriesTest {
    @Test
    fun `PointcutMutableSeries should intercept add and dispatch Action`() {
        val backing = ChunkedMutableSeries<String>()
        val actions = mutableListOf<MutationAction<String>>()
        val pointcut = PointcutMutableSeries(backing, { actions.add(it) }, null)

        pointcut.add("Hello")

        assertEquals(1, backing.a)
        assertEquals(1, actions.size)
        assertTrue(actions[0] is MutationAction.Add)
        assertEquals("Hello", (actions[0] as MutationAction.Add).item)
    }

    @Test
    fun `PointcutMutableSeries should intercept set and dispatch Action`() {
        val backing = ChunkedMutableSeries<String>()
        backing.add("Old")
        val actions = mutableListOf<MutationAction<String>>()
        val pointcut = PointcutMutableSeries(backing, { actions.add(it) }, null)

        pointcut.set(0, "New")

        assertEquals("New", backing.b(0))
        assertTrue(actions[0] is MutationAction.Set)
        assertEquals("New", (actions[0] as MutationAction.Set).item)
    }

    @Test
    fun `PointcutMutableSeries should intercept remove and dispatch Action`() {
        val backing = ChunkedMutableSeries<String>()
        backing.add("Hello")
        val actions = mutableListOf<MutationAction<String>>()
        val pointcut = PointcutMutableSeries(backing, { actions.add(it) }, null)

        pointcut.remove("Hello")

        assertEquals(0, backing.a)
        assertTrue(actions[0] is MutationAction.Remove)
    }
}
