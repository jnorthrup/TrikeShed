package borg.trikeshed.userspace.reactor

import borg.trikeshed.userspace.UringCompletion
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * FanoutDispatcherElement — ChannelRunner-idiom completion tests.
 *
 * Covers the [FanoutDispatcherElement.awaitCompletion] contract: distinct
 * tokens resolve independently, a completion that arrives before its awaiter is
 * stashed rather than dropped, cancelling one awaiter leaves its same-token peer
 * armed, `close` fails whatever is still armed, and the deprecated
 * registerHandler callback path still fires.
 *
 * Lives in jvmTest rather than commonTest because commonTest excludes
 * `userspace/reactor` outside the focusedTransportSlice build, and the slice
 * build has pre-existing unrelated compile failures — a commonTest placement
 * would never actually execute.
 *
 * Two timing notes:
 *  - The element's consumer loop runs on its own supervisor scope, i.e. a real
 *    dispatcher rather than the test scheduler, so each body runs inside
 *    `withContext(Dispatchers.Default)`: virtual time never advances here and
 *    `withTimeout` must measure real elapsed time.
 *  - Awaiters start [CoroutineStart.UNDISPATCHED] so each runs inline through
 *    the uncontended `Mutex` that arms its deferred and only then suspends on
 *    it. That makes "armed before dispatch" a property of the call rather than
 *    of a scheduling race.
 */
class FanoutDispatcherElementTest {

    @Test
    fun concurrentAwaitersOnDistinctTokensEachReceiveTheirCompletion() = runTest {
        withContext(Dispatchers.Default) {
            val element = FanoutDispatcherElement()
            element.open()
            try {
                val a = async(start = CoroutineStart.UNDISPATCHED) { element.awaitCompletion(1L) }
                val b = async(start = CoroutineStart.UNDISPATCHED) { element.awaitCompletion(2L) }

                // Dispatch out of order: each awaiter gets its own token's completion.
                element.dispatch(UringCompletion(userData = 2L, res = 20, flags = 0))
                element.dispatch(UringCompletion(userData = 1L, res = 10, flags = 0))

                val first = withTimeout(TIMEOUT_MS) { a.await() }
                val second = withTimeout(TIMEOUT_MS) { b.await() }

                assertEquals(1L, first.userData)
                assertEquals(10, first.res)
                assertEquals(2L, second.userData)
                assertEquals(20, second.res)
            } finally {
                element.close()
            }
        }
    }

    @Test
    fun legacyRegisterHandlerCallbackStillFires() = runTest {
        withContext(Dispatchers.Default) {
            val element = FanoutDispatcherElement()
            element.open()
            val received = mutableListOf<UringCompletion>()
            val handler: (UringCompletion) -> Unit = { received.add(it) }
            @Suppress("DEPRECATION")
            element.registerHandler(7L, handler)
            try {
                val awaiter = async(start = CoroutineStart.UNDISPATCHED) {
                    element.awaitCompletion(7L)
                }

                element.dispatch(UringCompletion(userData = 7L, res = 42, flags = 0))

                // The consumer loop runs legacy callbacks before completing the
                // deferred, so a resumed awaiter proves the callback already ran.
                val completion = withTimeout(TIMEOUT_MS) { awaiter.await() }
                assertEquals(7L, completion.userData)
                assertEquals(42, completion.res)

                assertEquals(1, received.size)
                assertEquals(7L, received[0].userData)
                assertEquals(42, received[0].res)
            } finally {
                @Suppress("DEPRECATION")
                element.removeHandler(7L, handler)
                element.close()
            }
        }
    }

    /** Natural io_uring order is submit-then-await; the CQE may beat the awaiter. */
    @Test
    fun completionDispatchedBeforeAwaitIsStillDelivered() = runTest {
        withContext(Dispatchers.Default) {
            val element = FanoutDispatcherElement()
            element.open()
            try {
                element.dispatch(UringCompletion(userData = 9L, res = 99, flags = 0))

                val completion = withTimeout(TIMEOUT_MS) { element.awaitCompletion(9L) }
                assertEquals(9L, completion.userData)
                assertEquals(99, completion.res)
            } finally {
                element.close()
            }
        }
    }

    @Test
    fun cancellingOneAwaiterLeavesItsSameTokenPeerArmed() = runTest {
        withContext(Dispatchers.Default) {
            val element = FanoutDispatcherElement()
            element.open()
            try {
                // Both queue on token 3; `doomed` is the head, so cancelling it
                // must not take the queue (or `survivor`) down with it.
                val doomed = launch(start = CoroutineStart.UNDISPATCHED) { element.awaitCompletion(3L) }
                val survivor = async(start = CoroutineStart.UNDISPATCHED) { element.awaitCompletion(3L) }

                // cancelAndJoin waits for the NonCancellable disarm to finish.
                doomed.cancelAndJoin()

                element.dispatch(UringCompletion(userData = 3L, res = 33, flags = 0))

                val completion = withTimeout(TIMEOUT_MS) { survivor.await() }
                assertEquals(3L, completion.userData)
                assertEquals(33, completion.res)
            } finally {
                element.close()
            }
        }
    }

    @Test
    fun closeFailsStillArmedAwaiters() = runTest {
        withContext(Dispatchers.Default) {
            val element = FanoutDispatcherElement()
            element.open()
            // runCatching keeps the failure local: an async failing outright
            // would tear down the enclosing test scope.
            val awaiter = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { element.awaitCompletion(4L) }
            }

            element.close()

            val outcome = withTimeout(TIMEOUT_MS) { awaiter.await() }
            assertTrue(outcome.isFailure, "close() must fail an armed awaiter, got $outcome")
            assertTrue(
                outcome.exceptionOrNull() is IllegalStateException,
                "expected IllegalStateException, got ${outcome.exceptionOrNull()}",
            )
        }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
