package borg.trikeshed.memory.ontology

import kotlin.test.Test
import kotlin.test.assertTrue

class DialogueManagementMarkerTest {
    @Test
    fun dialogueManagementIsUserCentricMarker() {
        val marker: UserCentricMemory = DialogueManagement
        assertTrue(DialogueManagement.gloss.isNotEmpty())
        assertTrue(marker is MemorySubject)
    }
}
