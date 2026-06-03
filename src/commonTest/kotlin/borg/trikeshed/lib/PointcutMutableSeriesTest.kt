package borg.trikeshed.lib

import kotlin.test.Test
import kotlin.test.fail

class PointcutMutableSeriesTest {
    @Test
    fun `PointcutMutableSeries should intercept add and dispatch Action`() {
        val backing = ChunkedMutableSeries<String>()
        val actions = mutableListOf<MutationAction<String>>()
        val pointcut = PointcutMutableSeries(backing) { actions.add(it) }

        pointcut.add("Hello")

        kotlin.test.assertEquals(1, backing.size)
        kotlin.test.assertEquals(1, actions.size)
        kotlin.test.assertTrue(actions[0] is MutationAction.Add)
        kotlin.test.assertEquals("Hello", (actions[0] as MutationAction.Add).item)
    }

    @Test
    fun `PointcutMutableSeries should intercept set and dispatch Action`() {
        val backing = ChunkedMutableSeries<String>()
        backing.add("Old")
        val actions = mutableListOf<MutationAction<String>>()
        val pointcut = PointcutMutableSeries(backing) { actions.add(it) }

        pointcut[0] = "New"

        kotlin.test.assertEquals("New", backing[0])
        kotlin.test.assertTrue(actions[0] is MutationAction.Set)
        kotlin.test.assertEquals("New", (actions[0] as MutationAction.Set).item)
    }

    @Test
    fun `PointcutMutableSeries should intercept remove and dispatch Action`() {
        val backing = ChunkedMutableSeries<String>()
        backing.add("Hello")
        val actions = mutableListOf<MutationAction<String>>()
        val pointcut = PointcutMutableSeries(backing) { actions.add(it) }

        pointcut.remove("Hello")

        kotlin.test.assertEquals(0, backing.size)
        kotlin.test.assertTrue(actions[0] is MutationAction.Remove)
    }
}
