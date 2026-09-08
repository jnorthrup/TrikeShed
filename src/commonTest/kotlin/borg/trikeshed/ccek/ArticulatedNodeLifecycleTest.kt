package borg.trikeshed.ccek

import borg.trikeshed.context.ElementState
import borg.trikeshed.forge.ForgeBlockKind
import borg.trikeshed.forge.ForgeDoc
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ArticulatedNodeLifecycleTest {
    private fun signal(text: String) = ForgeSignal.AppendBlock(ForgeBlockKind.TEXT, text)

    @Test
    fun drainWaitsForQueuedSignalsAndStructuredDescendants() = runTest {
        val node = ArticulatedNode(ForgeDoc.empty("drain"), backgroundScope, record = true, autoStart = false)
        assertEquals(ElementState.CREATED, node.lifecycleState)
        node.open()
        assertEquals(ElementState.OPEN, node.lifecycleState)
        val release = CompletableDeferred<Unit>()
        val delivered = mutableListOf<String>()
        var childCompleted = false
        node.subscribeAgent("consumer") { received ->
            release.await()
            delivered += (received as ForgeSignal.AppendBlock).text
            kotlinx.coroutines.coroutineScope {
                launch { childCompleted = true }
            }
        }
        assertEquals(ElementState.ACTIVE, node.lifecycleState)
        node.sendSignal(signal("first"))
        node.sendSignal(signal("second"))
        runCurrent()
        val drained = async { node.drain() }
        runCurrent()
        assertEquals(ElementState.DRAINING, node.lifecycleState)
        assertFalse(drained.isCompleted)
        assertFalse(childCompleted)
        assertFalse(node.signalIn.trySend(signal("late")).isSuccess)
        assertFailsWith<IllegalStateException> { node.subscribeAgent("late") {} }
        release.complete(Unit)
        drained.await()
        assertEquals(listOf("first", "second"), delivered)
        assertEquals(2, node.recording().size)
        assertTrue(childCompleted)
        assertEquals(ElementState.CLOSED, node.lifecycleState)
        assertTrue(node.supervisor.isCompleted)
        assertEquals(0, node.childScopeCount)
        assertEquals(0, node.agentCount)
        val finalDoc = node.documentProjections.replayCache.single()
        val finalMarkdown = node.markdownProjections.replayCache.single()
        assertEquals(1, node.boardProjections.replayCache.size)
        assertEquals(ForgeDoc.renderMarkdown(finalDoc), finalMarkdown)
        assertTrue(finalMarkdown.contains("first"))
        assertTrue(finalMarkdown.contains("second"))
        assertEquals(1, node.markdownProjectionCount)
        node.close()
        node.stop()
        node.start()
        node.open()
        assertFalse(node.isActive)
        assertEquals(ElementState.CLOSED, node.lifecycleState)
        assertFailsWith<IllegalStateException> { node.sendSignal(signal("restart")) }
    }

    @Test
    fun cancelBeforeDispatchDrainsWithoutRestarting() = runTest {
        val node = ArticulatedNode(ForgeDoc.empty("startup"), backgroundScope, record = true)
        assertTrue(node.signalIn.trySend(signal("queued")).isSuccess)
        node.cancel()
        node.stop()
        node.start()
        node.drain()
        assertEquals(1, node.recording().size)
        assertEquals(ElementState.CLOSED, node.lifecycleState)
        assertTrue(node.supervisor.isCompleted)
        assertFalse(node.signalIn.trySend(signal("closed")).isSuccess)
    }

    @Test
    fun directInputCloseCannotRestartTheConsumer() = runTest {
        val node = ArticulatedNode(ForgeDoc.empty("input"), backgroundScope, record = true)
        node.signalIn.send(signal("last"))
        node.signalIn.close()
        assertEquals(ElementState.DRAINING, node.lifecycleState)
        assertFailsWith<IllegalStateException> { node.subscribeAgent("closed") {} }
        node.start()
        node.supervisor.join()
        node.start()
        assertEquals(ElementState.CLOSED, node.lifecycleState)
        assertEquals(1, node.recording().size)
        assertFalse(node.isActive)
    }

    @Test
    fun callbacksResolveTheOwningSingletonKeyAndRetainOtherElements() = runTest {
        val context = UserContext("parent", backgroundScope)
        val node = context.choreograph(ForgeDoc.empty("identity"))
        val received = CompletableDeferred<CoroutineContext>()
        node.subscribeAgent("identity") { received.complete(currentCoroutineContext()) }
        node.sendSignal(signal("lookup"))
        val execution = received.await()
        assertSame(node, execution[ArticulatedNode.Key])
        assertSame(context, execution[UserContext.Key])
        assertSame(ArticulatedNode.Key, node.key)
        assertNull((EmptyCoroutineContext + node).minusKey(ArticulatedNode.Key)[ArticulatedNode.Key])
        assertSame(context, node.fanoutSubscribers.single())
        context.drain()
    }

    @Test
    fun parentCancellationCancelsSuspendingFanoutAndReleasesTheElement() = runTest {
        val parent = Job()
        val node = ArticulatedNode(ForgeDoc.empty("parent"), CoroutineScope(coroutineContext + parent))
        var cancelled = false
        node.subscribeAgent("suspended") {
            try {
                awaitCancellation()
            } finally {
                cancelled = true
            }
        }
        node.sendSignal(signal("pending"))
        runCurrent()
        parent.cancel()
        parent.join()
        assertTrue(cancelled)
        assertEquals(ElementState.CLOSED, node.lifecycleState)
        assertFalse(node.signalIn.trySend(signal("late")).isSuccess)
        assertFalse(node.projections.replayCache.any { it is ForgeProjection.Error })
        assertFailsWith<CancellationException> { node.drain() }
    }

    @Test
    fun abortDoesNotWaitForCancelledParentOrNonCancellableCleanup() = runTest {
        val parent = Job()
        val node = ArticulatedNode(ForgeDoc.empty("abort"), CoroutineScope(coroutineContext + parent))
        val cleanup = CompletableDeferred<Unit>()
        var enteredCleanup = false
        node.subscribeAgent("cleanup") {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    enteredCleanup = true
                    cleanup.await()
                }
            }
        }
        node.sendSignal(signal("pending"))
        runCurrent()
        parent.cancel()
        runCurrent()
        assertTrue(enteredCleanup)
        node.abort(CancellationException("assembly cancelled"))
        assertEquals(ElementState.DRAINING, node.lifecycleState)
        assertFalse(node.supervisor.isCompleted)
        assertFalse(node.signalIn.trySend(signal("late")).isSuccess)
        assertFailsWith<IllegalStateException> { node.subscribeAgent("late") {} }
        cleanup.complete(Unit)
        parent.join()
        assertEquals(ElementState.CLOSED, node.lifecycleState)
    }

    @Test
    fun fanoutBoundsChildJobsAndBackpressuresInput() = runTest {
        val node = ArticulatedNode(ForgeDoc.empty("bounded"), backgroundScope, maxConcurrency = 2)
        val release = CompletableDeferred<Unit>()
        var entered = 0
        repeat(40) { index ->
            node.subscribeAgent("agent-$index") {
                entered++
                release.await()
            }
        }
        node.sendSignal(signal("first"))
        runCurrent()
        assertEquals(2, entered)
        fun descendants(job: Job): Int = job.children.sumOf { 1 + descendants(it) }
        // Consumer, per-signal supervisor, and two callbacks; no 38 waiting launches.
        assertEquals(4, descendants(node.supervisor))
        var queued = 0
        while (queued < 1024 && node.signalIn.trySend(signal("queued-$queued")).isSuccess) queued++
        assertTrue(queued in 1 until 1024)
        runCurrent()
        assertEquals(2, entered)
        assertEquals(4, descendants(node.supervisor))
        release.complete(Unit)
        node.drain()
        assertEquals(40 * (queued + 1), entered)
    }

    @Test
    fun subscribersUsePerSignalSnapshotsAndRejectAdmissionAfterDrain() = runTest {
        val node = ArticulatedNode(ForgeDoc.empty("admission"), backgroundScope)
        val release = CompletableDeferred<Unit>()
        val original = mutableListOf<String>()
        val replacement = mutableListOf<String>()
        val late = mutableListOf<String>()
        node.subscribeAgent("agent") {
            original += (it as ForgeSignal.AppendBlock).text
            release.await()
        }
        node.sendSignal(signal("first"))
        runCurrent()
        node.subscribeAgent("agent") { replacement += (it as ForgeSignal.AppendBlock).text }
        node.subscribeAgent("late") { late += (it as ForgeSignal.AppendBlock).text }
        node.sendSignal(signal("second"))
        node.cancel()
        assertFailsWith<IllegalStateException> { node.subscribeAgent("agent") {} }
        release.complete(Unit)
        node.drain()
        assertEquals(listOf("first"), original)
        assertEquals(listOf("second"), replacement)
        assertEquals(listOf("second"), late)
    }

    @Test
    fun agentFailureIsReportedAndDoesNotCancelSiblingDelivery() = runTest {
        val node = ArticulatedNode(ForgeDoc.empty("failure"), backgroundScope, enabledProjections = emptySet())
        val failure = IllegalArgumentException("agent failure")
        var delivered = 0
        node.subscribeAgent("failure") { throw failure }
        node.subscribeAgent("sibling") { delivered++ }
        node.sendSignal(signal("first"))
        node.sendSignal(signal("second"))
        node.drain()
        assertEquals(2, delivered)
        assertSame(failure, (node.projections.replayCache.last() as ForgeProjection.Error).cause)
        assertEquals(ElementState.CLOSED, node.lifecycleState)
    }
}
