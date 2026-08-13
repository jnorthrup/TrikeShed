package borg.trikeshed.memory.ontology

import kotlin.test.Test
import kotlin.test.assertTrue

class UserSimulationMarkerTest {
    @Test
    fun userSimulationIsUserCentricMarker() {
        val gloss = UserSimulation.gloss
        assertTrue(gloss.isNotEmpty(), "UserSimulation gloss should not be empty")
        val marker: UserCentricMemory = UserSimulation
        assertTrue(marker === UserSimulation)
    }
}
