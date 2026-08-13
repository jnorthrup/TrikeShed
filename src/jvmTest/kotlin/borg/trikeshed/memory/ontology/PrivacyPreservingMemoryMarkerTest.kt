package borg.trikeshed.memory.ontology

import kotlin.test.Test
import kotlin.test.assertTrue

class PrivacyPreservingMemoryMarkerTest {

    @Test
    fun privacyPreservingMemoryIsUserCentricMarker() {
        val memory: UserCentricMemory = PrivacyPreservingMemory
        assertTrue(memory.gloss.isNotEmpty())
    }
}
