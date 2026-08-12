package borg.trikeshed.memory.ontology

import kotlin.test.Test
import kotlin.test.assertTrue

class ProceduralMarkerTest {
    @Test
    fun proceduralIsLongTermMemoryMarker() {
        val marker: LongTermMemory = Procedural
        assertTrue(Procedural.gloss.isNotEmpty())
        assertTrue(marker is MemoryMechanism)
    }
}
