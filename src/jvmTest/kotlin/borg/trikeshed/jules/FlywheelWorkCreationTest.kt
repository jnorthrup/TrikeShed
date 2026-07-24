package borg.trikeshed.jules

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FlywheelWorkCreationTest {
    @Test
    fun lagunaDraftsTddWorkFromCurrentResearchEvidence() {
        var capturedPrompt = ""
        val driver = FlywheelDriver(
            apiKey = "test-key",
            workDrafter = { prompt ->
                capturedPrompt = prompt
                """
                WORK	-	0.90	Repair cycle gate	Test: src/jvmTest/kotlin/CycleGateTest.kt; implement src/jvmMain/kotlin/CycleGate.kt; run ./gradlew jvmTest --tests CycleGateTest
                WORK	0	0.80	Wire cycle consumer	Test: src/jvmTest/kotlin/CycleConsumerTest.kt; implement src/jvmMain/kotlin/CycleConsumer.kt; run ./gradlew jvmTest --tests CycleConsumerTest
                """.trimIndent()
            },
        )

        val work = driver.draftWorkFromResearch("HEAD abc123\nsrc/jvmMain/kotlin/CycleGate.kt")

        assertTrue(capturedPrompt.contains("HEAD abc123"))
        assertTrue(capturedPrompt.contains("TDD"))
        assertEquals(2, work.size)
        assertEquals(work[0].workId, work[1].parent)
        assertEquals(listOf("Wire cycle consumer", "Repair cycle gate"),
            FlywheelDriver.rankWork(work.map { it.ranked }).map { ranked ->
                work.single { it.workId == ranked.workId }.title
            })
        assertTrue(work.all { it.spec.contains("Test:") && it.spec.contains("run ./gradlew") })
    }
}
