package borg.trikeshed.lcnc

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs

class LcncInvocationAdmissionTest {
    private suspend fun rejectCancelledEntry(type: String) {
        val failure = CompletableDeferred<Throwable?>()
        var invoked = false
        val runner = LcncNodeRunner { _, _ ->
            invoked = true
            emptyMap()
        }
        kotlinx.coroutines.coroutineScope {
            launch(start = CoroutineStart.UNDISPATCHED) {
                currentCoroutineContext().job.cancel(CancellationException("cancelled before entry"))
                failure.complete(runCatching {
                    runner.run(LcncNode("cancelled", type), emptyMap())
                }.exceptionOrNull())
            }.join()
        }
        assertFalse(invoked, "A cancelled direct invocation must not execute its body")
        assertIs<CancellationException>(failure.await())
    }

    @Test
    fun cancelledPaletteEntryPreservesCancellation() = runTest {
        rejectCancelledEntry("text.value")
    }

    @Test
    fun cancelledExtensionEntryCannotExecuteEffects() = runTest {
        rejectCancelledEntry("extension.operation")
    }
}
