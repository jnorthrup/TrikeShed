package borg.trikeshed.memory.ontology

import kotlin.test.Test
import kotlin.test.assertTrue

class WorkingMarkerTest {
    @Test
    fun workingIsShortTermMemoryMarker() {
        val working: ShortTermMemory = Working
        assertTrue(working.gloss.isNotEmpty())
    }
}
