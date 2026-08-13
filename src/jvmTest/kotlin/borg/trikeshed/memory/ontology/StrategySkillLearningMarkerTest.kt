package borg.trikeshed.memory.ontology

import kotlin.test.Test
import kotlin.test.assertTrue

class StrategySkillLearningMarkerTest {
    @Test
    fun strategySkillLearningIsAgentCentricMarker() {
        val marker: AgentCentricMemory = StrategySkillLearning
        assertTrue(StrategySkillLearning.gloss.isNotEmpty())
        assertTrue(marker is MemorySubject)
    }
}
