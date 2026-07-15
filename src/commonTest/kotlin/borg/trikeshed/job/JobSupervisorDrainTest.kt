package borg.trikeshed.job

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class JobSupervisorDrainTest {

    @Test
    fun drainWaitsForAcceptedCommandsBeforeClosing() = runTest {
        val nexus = JobSupervisorElement.open(this, capacity = 2)

        nexus.submit(JobCommand.Submit(JobId.of("j-1"), "k-1"))
        nexus.submit(JobCommand.Submit(JobId.of("j-2"), "k-2"))

        nexus.drain()

        assertEquals(2L, nexus.committedSequence, "drain returned before accepted commands committed")
        assertEquals(ElementState.CLOSED, nexus.state)
    }
}
