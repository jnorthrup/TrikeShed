package borg.trikeshed.browser

import borg.trikeshed.job.JobCommand
import borg.trikeshed.job.JobId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BrowserMutationsLowerToJobCommandTest {
    @Test
    fun boundedIngressAcceptsJobCommands() = runTest {
        val ingress = boundedIngress(64)
        val cmd = JobCommand.Submit(JobId.of("test-job"), "ik-test")
        assertTrue(ingress.trySend(cmd).isSuccess)
        val received = ingress.receive()
        assertEquals("test-job", received.jobId.value)
    }
}
