package borg.trikeshed

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList as flowToList
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ThinSliceTest {

    @Test
    fun `flow should emit items correctly`() = runTest {
        val testFlow: Flow<Int> = flowOf(1, 2, 33)
        val collectedItems = testFlow.flowToList()

        assertEquals(3, collectedItems.size, "Flow should emit 3 items")
        assertEquals(1, collectedItems[0], "First item should be 1")
        assertEquals(33, collectedItems[2], "Third item should be 33")
    }

    @Test
    fun `clock system now should return current time`() {
        val currentTime = Clock.System.now()
        assertTrue(currentTime.epochSeconds > 946684800L, "Current time should be after 2000-01-01")
    }
}
