package borg.trikeshed.jules

import kotlin.test.Test
import kotlin.test.assertEquals

class FlywheelHierarchyRankingTest {
    @Test
    fun hierarchyRanksBlockingChildrenBeforeTheirParentsAndScores() {
        val ranked = FlywheelDriver.rankWork(
            listOf(
                FlywheelDriver.RankedWork("parent", parent = null, score = 1.0, queuedAt = 1L),
                FlywheelDriver.RankedWork("other", parent = null, score = 0.9, queuedAt = 2L),
                FlywheelDriver.RankedWork("child", parent = "parent", score = 0.1, queuedAt = 3L),
            )
        )

        assertEquals(listOf("child", "parent", "other"), ranked.map { it.workId })
    }
}
