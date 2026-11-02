package borg.trikeshed.agent.modelmux

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BreadcrumbGenerationTest {
    @Test fun drainRejectsNewCrossingsAndWaitsForAdmittedWork() = runTest {
        val generation = BreadcrumbGeneration(this, "session-a", 2)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = async { generation.execute { started.complete(Unit); release.await(); "first" } }
        started.await()
        val second = async { generation.execute { "second" } }
        runCurrent()
        val drain = launch { generation.drain() }
        runCurrent()
        assertFalse(drain.isCompleted)
        assertTrue(runCatching { generation.execute { "late" } }.isFailure)
        release.complete(Unit)
        assertEquals("first", first.await())
        assertEquals("second", second.await())
        drain.join()
        assertTrue(drain.isCompleted)
    }

    @Test fun cancelledCallerDoesNotAbandonAnAdmittedCrossing() = runTest {
        val generation = BreadcrumbGeneration(this, "session-b")
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val caller = launch {
            generation.execute { started.complete(Unit); release.await(); finished.complete(Unit) }
        }
        started.await()
        caller.cancel()
        val drain = launch { generation.drain() }
        runCurrent()
        assertFalse(drain.isCompleted)
        release.complete(Unit)
        drain.join()
        assertTrue(finished.isCompleted)
    }
}
