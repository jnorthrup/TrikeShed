package borg.trikeshed.jules

import borg.trikeshed.userspace.containment.CrossInstanceCollusionDetector
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class CrossInstanceCollusionDetectorTest {

    @Test
    fun `test containment of coordinated burst through drain path`() {
        val detector = CrossInstanceCollusionDetector()
        val now = Clock.System.now().toEpochMilliseconds()
        
        val arms = listOf(
            Triple(
                JulesRestClient.SessionInfo(
                    id = "s1", 
                    state = "COMPLETED", 
                    title = "Task 1", 
                    patchBytes = 0L, 
                    source = "unknown", 
                    updateTime = now.toString()
                ),
                null as String?,
                "+++ b/src/commonMain/kotlin/App.kt\n"
            ),
            Triple(
                JulesRestClient.SessionInfo(
                    id = "s2", 
                    state = "COMPLETED", 
                    title = "Task 2", 
                    patchBytes = 0L, 
                    source = "unknown", 
                    updateTime = (now + 60000).toString()
                ),
                null as String?,
                "+++ b/src/commonMain/kotlin/App.kt\n"
            ),
            Triple(
                JulesRestClient.SessionInfo(
                    id = "s3", 
                    state = "COMPLETED", 
                    title = "Task 3", 
                    patchBytes = 0L, 
                    source = "unknown", 
                    updateTime = (now + 120000).toString()
                ),
                null as String?,
                "+++ b/src/commonMain/kotlin/App.kt\n"
            )
        )
        
        val report = detector.detect(arms)
        assertTrue(report.flaggedSessions.containsAll(listOf("s1", "s2", "s3")))
        assertTrue(report.innocenceScore < 1.0)
        assertEquals(3, report.flaggedSessions.size)
        assertTrue(report.evidence.any { it.contains("Burst:") })
    }

    @Test
    fun `test containment of author overlap through drain path`() {
        val detector = CrossInstanceCollusionDetector()
        val now = Clock.System.now().toEpochMilliseconds()
        
        val arms = listOf(
            Triple(
                JulesRestClient.SessionInfo(
                    id = "s1", 
                    state = "COMPLETED", 
                    title = "Task 1", 
                    patchBytes = 0L, 
                    source = "sockpuppet", 
                    updateTime = now.toString()
                ),
                null as String?,
                "+++ b/src/commonMain/kotlin/App1.kt\n"
            ),
            Triple(
                JulesRestClient.SessionInfo(
                    id = "s2", 
                    state = "COMPLETED", 
                    title = "Task 2", 
                    patchBytes = 0L, 
                    source = "sockpuppet", 
                    updateTime = (now + 86400000).toString() // Next day
                ),
                null as String?,
                "+++ b/src/commonMain/kotlin/App2.kt\n"
            ),
            Triple(
                JulesRestClient.SessionInfo(
                    id = "s3", 
                    state = "COMPLETED", 
                    title = "Task 3", 
                    patchBytes = 0L, 
                    source = "sockpuppet", 
                    updateTime = (now + 172800000).toString() // Day after
                ),
                null as String?,
                "+++ b/src/commonMain/kotlin/App3.kt\n"
            )
        )
        
        val report = detector.detect(arms)
        assertTrue(report.flaggedSessions.containsAll(listOf("s1", "s2", "s3")))
        assertTrue(report.innocenceScore < 1.0)
        assertEquals(3, report.flaggedSessions.size)
        assertTrue(report.evidence.any { it.contains("Author overlap:") })
    }
    
    @Test
    fun `test containment of message similarity through drain path`() {
        val detector = CrossInstanceCollusionDetector()
        val now = Clock.System.now().toEpochMilliseconds()
        
        val arms = listOf(
            Triple(
                JulesRestClient.SessionInfo(
                    id = "s1", 
                    state = "COMPLETED", 
                    title = "Fix typo in App", 
                    patchBytes = 0L, 
                    source = "unknown", 
                    updateTime = now.toString()
                ),
                null as String?,
                "+++ b/src/commonMain/kotlin/App1.kt\n"
            ),
            Triple(
                JulesRestClient.SessionInfo(
                    id = "s2", 
                    state = "COMPLETED", 
                    title = "fix typo in app", 
                    patchBytes = 0L, 
                    source = "unknown", 
                    updateTime = (now + 86400000).toString() // Next day
                ),
                null as String?,
                "+++ b/src/commonMain/kotlin/App2.kt\n"
            ),
            Triple(
                JulesRestClient.SessionInfo(
                    id = "s3", 
                    state = "COMPLETED", 
                    title = "FIX TYPO IN APP!!!", 
                    patchBytes = 0L, 
                    source = "unknown", 
                    updateTime = (now + 172800000).toString() // Day after
                ),
                null as String?,
                "+++ b/src/commonMain/kotlin/App3.kt\n"
            )
        )
        
        val report = detector.detect(arms)
        assertTrue(report.flaggedSessions.containsAll(listOf("s1", "s2", "s3")))
        assertTrue(report.innocenceScore < 1.0)
        assertEquals(3, report.flaggedSessions.size)
        assertTrue(report.evidence.any { it.contains("Message similarity:") })
    }
}
