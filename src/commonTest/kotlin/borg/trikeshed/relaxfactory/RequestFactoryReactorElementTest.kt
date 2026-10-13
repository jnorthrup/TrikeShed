package borg.trikeshed.relaxfactory

import borg.trikeshed.context.ElementState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RequestFactoryReactorElementTest {
    @Test
    fun boundedAdmissionSerializesProcessingAndDrainClosesAfterEveryReceipt() = runTest {
        val release = CompletableDeferred<Unit>()
        val processed = mutableListOf<String>()
        var closeCount = 0
        val element = RequestFactoryReactorElement(
            processor = { payload ->
                if (payload == "one") release.await()
                processed += payload
                "reply:$payload"
            },
            closeProcessor = { closeCount++ },
            capacity = 1,
        )
        element.open()
        withContext(element) { assertSame(element, currentCoroutineContext()[RequestFactoryReactorElement]) }
        val first = async { element.processRequest("one") }
        runCurrent()
        val second = async { element.processRequest("two") }
        runCurrent()
        val third = async { element.processRequest("three") }
        runCurrent()
        val drain = async { element.drain() }
        runCurrent()
        assertFalse(first.isCompleted)
        assertFalse(second.isCompleted)
        assertFalse(third.isCompleted)
        assertFalse(drain.isCompleted)
        assertEquals(0, closeCount)

        release.complete(Unit)
        advanceUntilIdle()
        assertEquals("reply:one", first.await())
        assertEquals("reply:two", second.await())
        assertEquals("reply:three", third.await())
        drain.await()
        assertEquals(listOf("one", "two", "three"), processed)
        assertEquals(ElementState.CLOSED, element.state)
        assertTrue(element.supervisor.isCompleted)
        assertEquals(1, closeCount)
        assertIs<IllegalStateException>(runCatching { element.processRequest("late") }.exceptionOrNull())
        element.drain()
        assertEquals(1, closeCount)
    }

    @Test
    fun callerCancellationPropagatesAndItsAdmittedEffectStillFinishes() = runTest {
        val release = CompletableDeferred<Unit>()
        val processed = mutableListOf<String>()
        val element = RequestFactoryReactorElement(processor = { payload ->
            if (payload == "one") release.await()
            processed += payload
            payload
        }, capacity = 1)
        element.open()
        val first = async { element.processRequest("one") }
        runCurrent()
        val canceled = async { element.processRequest("two") }
        runCurrent()
        canceled.cancel()
        assertIs<CancellationException>(runCatching { canceled.await() }.exceptionOrNull())
        release.complete(Unit)
        element.drain()
        assertEquals("one", first.await())
        assertEquals(listOf("one", "two"), processed)
    }

    @Test
    fun processorFailureSettlesItsReceiptWithoutPoisoningTheQueue() = runTest {
        val failure = IllegalArgumentException("service failure")
        val element = RequestFactoryReactorElement(processor = { payload ->
            if (payload == "fail") throw failure
            payload
        })
        element.open()
        val failed = async { runCatching { element.processRequest("fail") } }
        val succeeded = async { element.processRequest("next") }
        assertSame(failure, failed.await().exceptionOrNull())
        assertEquals("next", succeeded.await())
        element.drain()
    }

    @Test
    fun ownerCancellationSettlesTheActiveQueuedAndBoundedRequests() = runTest {
        val owner = Job(currentCoroutineContext()[Job])
        val started = CompletableDeferred<Unit>()
        val blocked = CompletableDeferred<Unit>()
        var closeCount = 0
        val element = RequestFactoryReactorElement(
            processor = {
                started.complete(Unit)
                blocked.await()
                it
            },
            closeProcessor = { closeCount++ },
            parentJob = owner,
            capacity = 1,
        )
        element.open()
        val first = async { runCatching { element.processRequest("one") } }
        started.await()
        val second = async { runCatching { element.processRequest("two") } }
        runCurrent()
        val third = async { runCatching { element.processRequest("three") } }
        runCurrent()
        owner.cancel()
        advanceUntilIdle()
        assertIs<CancellationException>(first.await().exceptionOrNull())
        assertIs<CancellationException>(second.await().exceptionOrNull())
        assertIs<CancellationException>(third.await().exceptionOrNull())
        element.drain()
        assertEquals(1, closeCount)
        assertEquals(ElementState.CLOSED, element.state)
    }
}
