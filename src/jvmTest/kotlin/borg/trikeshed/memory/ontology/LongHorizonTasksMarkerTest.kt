package borg.trikeshed.memory.ontology

import kotlin.test.Test
import kotlin.test.assertTrue

class LongHorizonTasksMarkerTest {
    @Test
    fun longHorizonTasksIsAgentCentricMarker() {
        val memory: AgentCentricMemory = LongHorizonTasks
        assertTrue(memory.gloss.isNotEmpty(), "LongHorizonTasks gloss should not be empty")
    }
}
