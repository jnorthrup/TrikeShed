package borg.trikeshed.lcnc

import borg.trikeshed.context.ElementState
import borg.trikeshed.runBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LcncNodeElementTest {
    private class Host : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Host>
    }

    @Test
    fun everyCompiledPaletteItemHasAKeyOrAnExplicitStructuralException() {
        val contracts = LcncContracts.all()
        assertTrue(contracts.none { it.context.role == LcncContextRole.UNDECLARED })
        assertEquals(setOf("scope", "scope.in", "scope.out", "note", "program.ref"),
            contracts.filter { it.context.role != LcncContextRole.INVOCATION }.map { it.type }.toSet())
        val invocations = contracts.filter { it.context.role == LcncContextRole.INVOCATION }
        assertEquals(invocations.map { it.type }.toSet(), LcncNodeKey.entries.map { it.type }.toSet())
        assertEquals(invocations.size, LcncNodeKey.entries.size)
        for (contract in invocations) {
            assertSame(LcncNodeKey.of(contract.type), contract.context.key)
            assertSame(contract.context.key, LcncContracts.find(contract.type)!!.context.key)
        }
        assertSame(LcncScopeFrame.Key, LcncContextContract.of("scope").key)
        assertNull(LcncContextContract.of("note").key)
        assertEquals(LcncContextRole.UNDECLARED, LcncContextContract.of("future.node").role)
        assertEquals(LcncContextRole.COMPOSITE, LcncContextContract.of("stored-program", composite = true).role)
    }

    @Test
    fun directRegistryInvocationInstallsItsKeyAndRetainsHostContext() = runBlocking {
        val host = Host()
        var captured: LcncNodeElement? = null
        val node = LcncNode("literal", "text.value")
        val inputs = mapOf("input" to 3)
        val runner = LcncNodeRunner { seen, values ->
            val context = currentCoroutineContext()
            val element = assertNotNull(context[LcncNodeKey.TEXT_VALUE])
            captured = element
            assertSame(node, element.node)
            assertSame(inputs, element.inputs)
            assertSame(host, context[Host])
            assertEquals(ElementState.ACTIVE, element.lifecycleState)
            assertEquals(node, seen)
            assertEquals(inputs, values)
            mapOf("value" to "ready")
        }
        val result = withContext(host) { runner.run(node, inputs) }
        val element = assertNotNull(captured)
        assertEquals(mapOf("value" to "ready"), result)
        assertEquals(result, element.result)
        assertEquals(ElementState.CLOSED, element.lifecycleState)
        assertTrue(element.supervisor.isCompleted)
        assertNull(currentCoroutineContext()[LcncNodeKey.TEXT_VALUE])
    }

    @Test
    fun nestedSameKeyShadowsAndRestoresTheEnclosingInvocation() = runBlocking {
        val inner = LcncNodeRunner { node, _ ->
            assertEquals("inner", currentCoroutineContext()[LcncNodeKey.TEXT_VALUE]!!.node.id)
            mapOf("value" to node.id)
        }
        val outer = LcncNodeRunner { _, _ ->
            val enclosing = currentCoroutineContext()[LcncNodeKey.TEXT_VALUE]!!
            val value = inner.run(LcncNode("inner", "text.value"), emptyMap())
            assertSame(enclosing, currentCoroutineContext()[LcncNodeKey.TEXT_VALUE])
            value
        }
        assertEquals("inner", outer.run(LcncNode("outer", "text.value"), emptyMap())["value"])
    }

    @Test
    fun drainWaitsForInFlightWorkAndRejectsAnotherInvocation() = runBlocking {
        withTimeout(5_000) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val element = LcncNodeKey.TEXT_VALUE.construct(LcncNode("n", "text.value"), emptyMap(),
                LcncNodeRunner { _, _ -> entered.complete(Unit); release.await(); mapOf("value" to 1) },
                currentCoroutineContext().job)
            assertEquals(ElementState.CREATED, element.lifecycleState)
            element.open()
            assertEquals(ElementState.OPEN, element.lifecycleState)
            val execution = async { element.execute() }
            entered.await()
            val draining = async { element.drain() }
            yield()
            assertEquals(ElementState.DRAINING, element.lifecycleState)
            assertFalse(draining.isCompleted)
            assertFailsWith<IllegalStateException> { element.execute() }
            release.complete(Unit)
            assertEquals(1, execution.await()["value"])
            draining.await()
            assertEquals(ElementState.CLOSED, element.lifecycleState)
            element.close()
            assertFailsWith<IllegalStateException> { element.open() }
        }
    }

    @Test
    fun structuredChildrenCompleteBeforeTheOutputIsPublished() = runBlocking {
        withTimeout(5_000) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var completed = false
            val runner = LcncNodeRunner { _, _ ->
                CoroutineScope(currentCoroutineContext()).launch {
                    entered.complete(Unit)
                    release.await()
                    completed = true
                }
                mapOf("value" to 1)
            }
            val execution = async { runner.run(LcncNode("n", "text.value"), emptyMap()) }
            entered.await()
            assertFalse(execution.isCompleted)
            release.complete(Unit)
            execution.await()
            assertTrue(completed)
        }
    }

    @Test
    fun parentCancellationClosesTheElementAndCancelsItsWork() = runBlocking {
        withTimeout(5_000) {
            val parent = Job(currentCoroutineContext().job)
            val entered = CompletableDeferred<Unit>()
            val element = LcncNodeKey.TEXT_VALUE.construct(LcncNode("n", "text.value"), emptyMap(),
                LcncNodeRunner { _, _ -> entered.complete(Unit); CompletableDeferred<Unit>().await(); emptyMap() }, parent)
            val execution = async { element.execute() }
            entered.await()
            parent.cancel()
            assertFailsWith<CancellationException> { execution.await() }
            parent.join()
            assertEquals(ElementState.CLOSED, element.lifecycleState)
            assertNull(element.result)
            assertTrue(element.supervisor.isCompleted)
        }
    }

    @Test
    fun failureKeepsItsCauseAndReleasesTheOwnedSupervisor() = runBlocking {
        val failure = IllegalArgumentException("bad input")
        val element = LcncNodeKey.TEXT_VALUE.construct(LcncNode("n", "text.value"), emptyMap(),
            LcncNodeRunner { _, _ -> throw failure }, currentCoroutineContext().job)
        val caught = assertFailsWith<IllegalArgumentException> { element.execute() }
        assertSame(failure, caught)
        assertEquals(ElementState.CLOSED, element.lifecycleState)
        assertTrue(element.supervisor.isCompleted)
        assertTrue(currentCoroutineContext().job.isActive)
    }
}
