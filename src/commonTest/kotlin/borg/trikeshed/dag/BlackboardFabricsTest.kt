package borg.trikeshed.dag

import borg.trikeshed.lib.size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BlackboardFabricsTest {
    @Test
    fun `create returns live fabric that publish and subscribe`() {
        val fabric = BlackboardFabrics.create()
        val seen = mutableListOf<BlackboardEvent>()
        val sub = fabric.TODO_subscribe("ClassLoad") { seen.add(it) }

        val coord = DagCoordinate("C", "m", 0, timestamp = 100L, threadId = 1L)
        fabric.publish(
            BlackboardEvent.ClassLoad(coord, className = "C", classLoader = "app"),
        )
        assertEquals(1, seen.size)
        assertTrue(seen[0] is BlackboardEvent.ClassLoad)

        val slice = fabric.TODO_getEvents(
            DagCoordinate("C", "m", 0, 50L, 1L),
            DagCoordinate("C", "m", 0, 150L, 1L),
        )
        assertEquals(1, slice.size)

        sub.unsubscribe()
        fabric.publish(
            BlackboardEvent.ClassLoad(
                coord.copy(timestamp = 200L),
                className = "C2",
                classLoader = "app",
            ),
        )
        assertEquals(1, seen.size) // unsubscribed
    }
}
