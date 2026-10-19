package borg.trikeshed.lcnc

import borg.trikeshed.context.ElementState
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class CamelRouteElementTest {
    private class Handle(override val id: String) : CamelRouteHandle {
        override fun status() = "Started"
        override fun routeIds() = listOf(id)
        override fun identity(): Map<String, Any?> = mapOf("id" to id)
        override fun describe() = identity()
    }

    private class Provider(val value: String) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Provider>
    }

    @Test
    fun cancellationCannotFreeCapacityOrDiscardAnAdmittedStart() = runTest {
        val owner = CamelRouteElement(coroutineContext.job, capacity = 1, maxCommands = 1)
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        var stopped = false
        var published = false
        owner.open()
        try {
            val waiter = launch(Provider("caller")) {
                owner.command {
                    assertEquals("caller", currentCoroutineContext()[Provider]?.value)
                    entered.complete(Unit)
                    gate.await()
                    owner.retain(Handle("owned")) {
                        owner.observe { published = true }
                        stopped = true
                    }
                }
            }
            entered.await()
            waiter.cancelAndJoin()
            assertFailsWith<IllegalStateException> { owner.command { error("excess work executed") } }
            val drain = async { owner.drain() }
            runCurrent()
            assertFalse(drain.isCompleted, "drain must join the accepted start even after its caller left")
            assertFalse(stopped)
            assertFailsWith<IllegalStateException> { owner.command { error("late work executed") } }
            gate.complete(Unit)
            drain.await()
            assertTrue(stopped)
            assertTrue(published, "the exchange lane must stay open through consumer cleanup")
            assertEquals(ElementState.CLOSED, owner.state)
            assertTrue(owner.supervisor.isCompleted)
        } finally { gate.complete(Unit); owner.drain() }
    }

    @Test
    fun failedConsumerCleanupRetainsPublicationAndRetriesOnlyFailedLeases() = runTest {
        val owner = CamelRouteElement(coroutineContext.job)
        val failed = Handle("failed")
        val healthy = Handle("healthy")
        var fail = true
        var failedStops = 0
        var healthyStops = 0
        var revisions = 0
        owner.open()
        try {
            owner.command {
                owner.retain(failed) {
                    failedStops++
                    if (fail) error("stop refused")
                    owner.observe { revisions++ }
                }
                owner.retain(healthy) { healthyStops++; owner.observe { revisions++ } }
                assertEquals(listOf(failed, healthy), owner.ownedRoutes())
            }
            assertEquals("stop refused", assertFailsWith<IllegalStateException> { owner.drain() }.message)
            assertEquals(1, failedStops)
            assertEquals(1, healthyStops, "one failure cannot abandon other consumer cleanup")
            assertEquals(ElementState.DRAINING, owner.state)
            assertTrue(owner.supervisor.isActive)
            assertFailsWith<IllegalStateException> { owner.command {} }
            owner.observe { revisions++ }
            fail = false
            owner.drain()
            assertEquals(2, failedStops)
            assertEquals(1, healthyStops)
            assertEquals(3, revisions)
            assertFailsWith<IllegalStateException> { owner.observe { revisions++ } }
        } finally { fail = false; owner.drain() }
    }

    @Test
    fun callbacksCannotJoinTheirOwnOwnerAndRefusalsDoNotPoisonCommands() = runTest {
        val owner = CamelRouteElement(coroutineContext.job)
        owner.open()
        try {
            owner.command {
                assertFailsWith<IllegalStateException> { owner.command {} }
                assertFailsWith<IllegalStateException> { owner.drain() }
                owner.observe {
                    assertFailsWith<IllegalStateException> { owner.command {} }
                    assertFailsWith<IllegalStateException> { owner.drain() }
                    assertFailsWith<IllegalStateException> { owner.observe {} }
                }
            }
            assertFailsWith<IllegalArgumentException> { owner.command { require(false) { "refused" } } }
            assertEquals(42, owner.command { 42 })
            assertFailsWith<IllegalStateException> { owner.retain(Handle("outside")) {} }
        } finally { owner.drain() }
    }

    @Test
    fun commandCompletionIncludesStructuredChildrenAndStopUsesContextIdentity() = runTest {
        val owner = CamelRouteElement(coroutineContext.job)
        val first = Handle("same-name")
        val replacement = Handle("same-name")
        val child = CompletableDeferred<Unit>()
        var stopped = false
        owner.open()
        try {
            val command = async {
                owner.command {
                    owner.retain(first) { stopped = true }
                    assertTrue(owner.owns(first))
                    assertFalse(owner.owns(replacement))
                    owner.release(replacement)
                    coroutineScope { launch { child.await() } }
                }
            }
            runCurrent()
            assertFalse(command.isCompleted)
            val drain = async { owner.drain() }
            runCurrent()
            assertFalse(stopped)
            child.complete(Unit)
            command.await()
            drain.await()
            assertTrue(stopped, "a reused name must not release another context's cleanup obligation")
        } finally { child.complete(Unit); owner.drain() }
    }

    @Test
    fun unopenedOwnerDrainsWithoutLaunchingWork() = runTest {
        val owner = CamelRouteElement(coroutineContext.job)
        owner.drain()
        assertTrue(owner.supervisor.isCompleted)
        assertFailsWith<IllegalStateException> { owner.open() }
        assertFailsWith<IllegalStateException> { owner.command {} }
    }
    @Test
    fun nestedCommandsRefuseAnActiveAncestorBeforeAdmission() = runTest {
        val first = CamelRouteElement(coroutineContext.job, capacity = 1)
        val second = CamelRouteElement(coroutineContext.job, capacity = 1)
        var nestedEffects = 0
        var refusal: String? = null
        first.open()
        second.open()
        try {
            assertEquals(7, first.command { second.command { 7 } },
                "A command may await a different owner without a cycle")
            val outcome = first.command {
                second.command {
                    // Cancel only this waiter if the ancestor was wrongly admitted. The real
                    // queued first-owner command stays owned and executes after the outer returns.
                    withTimeoutOrNull(100) {
                        try {
                            first.command { nestedEffects++ }
                            "accepted"
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (rejected: IllegalStateException) {
                            refusal = rejected.message
                            "refused"
                        }
                    } ?: "timed_out"
                }
            }
            // These real queue barriers settle any wrongly admitted effect before assertions
            // and cleanup. Timeout is an escape from the circular wait, never expected success.
            assertEquals(11, first.command { 11 })
            assertEquals(13, second.command { 13 })
            assertEquals("refused", outcome,
                "A -> B -> A must refuse before admission; timing out the nested waiter is breakage")
            assertEquals("A Camel callback cannot join its own command owner", refusal)
            assertEquals(0, nestedEffects, "No command may be queued behind its active ancestor")
        } finally {
            withContext(NonCancellable) { first.drain(); second.drain() }
        }
        assertTrue(first.supervisor.isCompleted)
        assertTrue(second.supervisor.isCompleted)
    }

    @Test
    fun publicationCrossingCannotAdmitAnAncestorCommandOrBorrowItsAuthority() = runTest {
        val first = CamelRouteElement(coroutineContext.job, capacity = 1)
        val second = CamelRouteElement(coroutineContext.job, capacity = 1)
        var nestedEffects = 0
        var outcome: String? = null
        var refusal: String? = null
        var ancestorAuthority: Throwable? = null
        var publicationAuthority: Throwable? = null
        first.open()
        second.open()
        try {
            // A publication has no lifetime-mutation authority from its caller's command.
            // Capture results here so a broken authority check cannot poison the publication lane.
            first.command {
                second.observe {
                    ancestorAuthority = runCatching { first.retain(Handle("ancestor")) {} }.exceptionOrNull()
                    publicationAuthority = runCatching { second.retain(Handle("publication")) {} }.exceptionOrNull()
                }
            }
            assertEquals("Camel lifetime changes require an admitted command",
                assertIs<IllegalStateException>(ancestorAuthority).message)
            assertEquals("Camel lifetime changes require an admitted command",
                assertIs<IllegalStateException>(publicationAuthority).message)
            assertTrue(first.command { first.ownedRoutes().isEmpty() })
            assertTrue(second.command { second.ownedRoutes().isEmpty() })
            first.command {
                second.observe {
                    // Escape only the inner waiter if caller context was lost at publication.
                    // The accepted first-owner effect remains queued until this outer returns.
                    outcome = withTimeoutOrNull(100) {
                        try {
                            first.command { nestedEffects++ }
                            "accepted"
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (rejected: IllegalStateException) {
                            refusal = rejected.message
                            "refused"
                        }
                    } ?: "timed_out"
                }
            }
            assertEquals(11, first.command { 11 })
            assertEquals(13, second.command { 13 })
            assertEquals("refused", outcome,
                "A command -> B publication -> A command must refuse before admission")
            assertEquals("A Camel callback cannot join its own command owner", refusal)
            assertEquals(0, nestedEffects)
        } finally {
            withContext(NonCancellable) { first.drain(); second.drain() }
        }
        assertTrue(first.supervisor.isCompleted)
        assertTrue(second.supervisor.isCompleted)
    }

    @Test
    fun nestedPublicationsRefuseAnActiveAncestorBeforeAdmission() = runTest {
        val first = CamelRouteElement(coroutineContext.job, capacity = 1)
        val second = CamelRouteElement(coroutineContext.job, capacity = 1)
        var control = 0
        var nestedEffects = 0
        var outcome: String? = null
        var refusal: String? = null
        var firstBarrier = false
        var secondBarrier = false
        first.open()
        second.open()
        try {
            first.observe { second.observe { control = 7 } }
            assertEquals(7, control, "Different publication owners may compose without a cycle")
            first.observe {
                second.observe {
                    outcome = withTimeoutOrNull(100) {
                        try {
                            first.observe { nestedEffects++ }
                            "accepted"
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (rejected: IllegalStateException) {
                            refusal = rejected.message
                            "refused"
                        }
                    } ?: "timed_out"
                }
            }
            // The real serial publication lanes settle any wrongly accepted effect before
            // assertions and normal drain; cancellation of its waiter is not discarded work.
            first.observe { firstBarrier = true }
            second.observe { secondBarrier = true }
            assertTrue(firstBarrier && secondBarrier)
            assertEquals(11, first.command { 11 })
            assertEquals(13, second.command { 13 })
            assertEquals("refused", outcome,
                "A publication -> B publication -> A publication must refuse before admission")
            assertEquals("A Camel publication cannot await its own lane", refusal)
            assertEquals(0, nestedEffects)
        } finally {
            withContext(NonCancellable) { first.drain(); second.drain() }
        }
        assertTrue(first.supervisor.isCompleted)
        assertTrue(second.supervisor.isCompleted)
    }

}
