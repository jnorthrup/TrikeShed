package borg.trikeshed.memory.ontology

import kotlin.test.Test
import kotlin.test.assertTrue

class LongTermPersonalizationMarkerTest {
    @Test
    fun longTermPersonalizationIsUserCentricMarker() {
        val marker: UserCentricMemory = LongTermPersonalization
        assertTrue(marker.gloss.isNotEmpty())
    }
}
