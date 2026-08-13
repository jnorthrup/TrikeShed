package borg.trikeshed.memory.ontology

import kotlin.test.Test
import kotlin.test.assertTrue

class EpisodicMarkerTest {
    @Test
    fun episodicIsLongTermMemoryMarker() {
        val memory: LongTermMemory = Episodic
        assertTrue(memory.gloss.isNotEmpty())
    }
}
