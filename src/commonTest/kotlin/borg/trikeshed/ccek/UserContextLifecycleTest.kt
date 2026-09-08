package borg.trikeshed.ccek

import borg.trikeshed.context.ElementState
import borg.trikeshed.forge.ForgeBlockKind
import borg.trikeshed.forge.ForgeDoc
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class UserContextLifecycleTest {
    @Test
    fun lifecycleOwnsActivationAndCannotReopenAfterClose() = runTest {
        val context = UserContext("lifecycle", backgroundScope)
        assertEquals(ElementState.CREATED, context.lifecycleState)
        assertSame(UserContext.Key, context.key)
        context.open()
        assertEquals(ElementState.OPEN, context.lifecycleState)
        context.activate()
        assertEquals(ElementState.ACTIVE, context.lifecycleState)
        assertTrue(context.active)
        context.deactivate()
        assertFalse(context.active)
        assertEquals(ElementState.ACTIVE, context.lifecycleState)
        context.activate()
        context.close()
        context.close()
        context.cancel()
        context.open()
        assertEquals(ElementState.CLOSED, context.lifecycleState)
        assertFalse(context.active)
        assertFailsWith<IllegalStateException> { context.activate() }
        assertFailsWith<IllegalStateException> { context.fork("late") }
        assertFailsWith<IllegalStateException> { context.choreograph(ForgeDoc.empty("late")) }
        assertFailsWith<IllegalStateException> { context.assertFact(CausalAssertion("late", emptyMap())) }
    }

    @Test
    fun contextDrainFinishesQueuedCausalAssertionsAndForkedNodes() = runTest {
        val context = UserContext("root", backgroundScope)
        val fork = context.fork("child")
        val node = fork.choreograph(ForgeDoc.empty("queued"))
        val release = CompletableDeferred<Unit>()
        node.subscribeAgent("held") { release.await() }
        node.sendSignal(ForgeSignal.AppendBlock(ForgeBlockKind.TEXT, "first"))
        node.sendSignal(ForgeSignal.AppendBlock(ForgeBlockKind.TEXT, "second"))
        runCurrent()
        assertSame(fork, context.fanoutSubscribers.single())
        assertSame(node, fork.fanoutSubscribers.single())
        val drained = async { context.drain() }
        runCurrent()
        assertEquals(ElementState.DRAINING, context.lifecycleState)
        assertEquals(ElementState.DRAINING, fork.lifecycleState)
        assertEquals(ElementState.DRAINING, node.lifecycleState)
        assertFalse(drained.isCompleted)
        assertFailsWith<IllegalStateException> { context.fork("late") }
        assertFailsWith<IllegalStateException> { fork.loadPolyglotFacts(emptyList()) }
        release.complete(Unit)
        drained.await()
        assertEquals(2, fork.reteTable.query("block:appended").size)
        assertEquals(0, context.factCount)
        assertEquals(ElementState.CLOSED, context.lifecycleState)
        assertEquals(ElementState.CLOSED, fork.lifecycleState)
        assertEquals(ElementState.CLOSED, node.lifecycleState)
        assertTrue(context.fanoutSubscribers.isEmpty())
        assertTrue(context.supervisor.children.none())
    }

    @Test
    fun parentCancellationReachesNodesForksAndAdaptedWork() = runTest {
        val parent = Job()
        val context = UserContext("parent", CoroutineScope(coroutineContext + parent))
        val fork = context.fork("fork")
        val node = fork.choreograph(ForgeDoc.empty("node"))
        val adapted = context.adaptParadigm(MetaLcncParadigm("adapted", emptyList()))
        var cancelledNode = false
        var cancelledAdapted = false
        node.subscribeAgent("pending") {
            try {
                awaitCancellation()
            } finally {
                cancelledNode = true
            }
        }
        adapted.scope.launch {
            assertSame(context, currentCoroutineContext()[UserContext.Key])
            try {
                awaitCancellation()
            } finally {
                cancelledAdapted = true
            }
        }
        node.sendSignal(ForgeSignal.Continue("card"))
        runCurrent()
        assertTrue(adapted.isActive)
        parent.cancel()
        parent.join()
        assertTrue(cancelledNode)
        assertTrue(cancelledAdapted)
        assertFalse(adapted.isActive)
        assertEquals(ElementState.CLOSED, node.lifecycleState)
        assertEquals(ElementState.CLOSED, fork.lifecycleState)
        assertEquals(ElementState.CLOSED, context.lifecycleState)
    }

    @Test
    fun forkExecutionShadowsItsParentKeyAndRetainsIndependentFacts() = runTest {
        val root = UserContext("root", backgroundScope)
        root.assertFact(CausalAssertion("root", emptyMap()))
        val fork = root.fork("fork")
        val received = fork.executionScope.async { currentCoroutineContext()[UserContext.Key] }
        assertSame(fork, received.await())
        assertSame(root, root.executionScope.coroutineContext[UserContext.Key])
        fork.assertFact(CausalAssertion("fork", emptyMap()))
        assertEquals(1, root.factCount)
        assertEquals(2, fork.factCount)
        assertEquals(root.id, fork.parentId)
        root.drain()
    }

    @Test
    fun drainWaitsForAdaptedScopeWorkWithoutCancellingIt() = runTest {
        val context = UserContext("adapted", backgroundScope)
        val adapted = context.adaptParadigm(MetaLcncParadigm("rules", listOf(LcncRule("rule", "expr"))))
        val release = CompletableDeferred<Unit>()
        var completed = false
        adapted.scope.launch {
            release.await()
            completed = true
        }
        runCurrent()
        val closed = async { context.close() }
        runCurrent()
        assertEquals(ElementState.DRAINING, context.lifecycleState)
        assertFalse(closed.isCompleted)
        assertFalse(completed)
        assertFailsWith<IllegalStateException> { context.adaptParadigm(MetaLcncParadigm("late", emptyList())) }
        release.complete(Unit)
        closed.await()
        assertTrue(completed)
        assertEquals(1, context.factCount)
        assertFalse(adapted.isActive)
        assertEquals(ElementState.CLOSED, context.lifecycleState)
    }
}
