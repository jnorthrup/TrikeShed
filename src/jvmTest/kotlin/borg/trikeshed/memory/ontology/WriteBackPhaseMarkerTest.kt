package borg.trikeshed.memory.ontology

import kotlin.test.Test
import kotlin.test.assertTrue

class WriteBackPhaseMarkerTest {
    @Test
    fun writeBackIsSubstratePhaseMarker() {
        val phase: SubstratePhase = WriteBack
        assertTrue(phase.gloss.isNotEmpty())
    }
}
