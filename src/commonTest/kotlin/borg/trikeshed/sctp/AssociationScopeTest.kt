package borg.trikeshed.sctp
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import borg.trikeshed.reactor.ngsctp.SctpAssociationScope
import kotlinx.coroutines.test.runCurrent
class AssociationScopeTest {
    @Test
    fun `association creates and owns a CoroutineScope, closing cancels all child jobs`() = runTest {
        val scope: SctpAssociationScope = borg.trikeshed.reactor.ngsctp.SctpAssociationScopeImpl(0uL, this)
        var jobCompleted = false
        var jobCancelled = false
        val job = scope.launch {
                delay(1000)
                jobCompleted = true
            }
        job.invokeOnCompletion { jobCancelled = it is kotlinx.coroutines.CancellationException }
        runCurrent() // Allow the job to start
        scope.close()
        job.join()
        assertFalse(jobCompleted, "Job should not have completed")
        assertTrue(jobCancelled, "Job should have been cancelled")
    }
}
