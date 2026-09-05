package borg.trikeshed.lcnc

import borg.trikeshed.lib.toList
import borg.trikeshed.lib.toSeries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LcncTreeShakeCollectionsTest {
    @Test
    fun existingEndpointKeysMatchByValueAndNewWiresAreAppendedExactlyOnce() {
        val existing = LcncWire("source-a", "content", "sink-a", "x")
        val program = LcncProgram("collection-regression", listOf(
            LcncNode("source-a", "prompt.chat", x = 100.0),
            LcncNode("sink-a", "display", x = 200.0),
            LcncNode("source-b", "prompt.chat", x = 100.0, y = 100.0),
            LcncNode("sink-b", "display", x = 200.0, y = 100.0),
        ).toSeries(), listOf(existing).toSeries())

        val first = LcncTreeShake.shake(program)
        val added = LcncWire("source-b", "content", "sink-b", "x")
        assertEquals(listOf(added), first.made)
        assertEquals(listOf(existing, added), first.program.wires.toList())
        assertEquals(listOf(existing), program.wires.toList())

        val second = LcncTreeShake.shake(first.program)
        assertTrue(second.made.isEmpty())
        assertEquals(listOf(existing, added), second.program.wires.toList())
        assertEquals(listOf(existing, added), first.program.wires.toList())
    }
}
