package borg.trikeshed.lcnc.ccek

import borg.trikeshed.ccek.ArticulatedNode
import borg.trikeshed.ccek.CCEK
import borg.trikeshed.ccek.ForgeProjection
import borg.trikeshed.ccek.ForgeSignal
import borg.trikeshed.context.ElementState
import borg.trikeshed.htx.HtxElement
import borg.trikeshed.htx.HtxExchangeResult
import borg.trikeshed.htx.HtxExchangeState
import borg.trikeshed.htx.HtxKey
import borg.trikeshed.htx.HtxRequest
import borg.trikeshed.htx.HtxRouteService
import borg.trikeshed.lcnc.LcncNode
import borg.trikeshed.lcnc.LcncNodeRunner
import borg.trikeshed.lcnc.LcncProgram
import borg.trikeshed.lcnc.LcncRunner
import borg.trikeshed.lcnc.LcncScopeFrame
import borg.trikeshed.lib.emptySeriesOf
import borg.trikeshed.lib.j
import borg.trikeshed.lib.s_
import borg.trikeshed.userspace.concurrency.ParseScope
import borg.trikeshed.userspace.concurrency.ParseScopeKey
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.userspace.nio.file.spi.InMemoryFileOperations
import borg.trikeshed.userspace.reactor.MuxReactorElement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LcncCcekAssemblyTest {
    private class HostElement : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<HostElement>
    }

    private class RunnerFailure(val input: String) : IllegalStateException(input)

    private fun program(name: String, vararg nodes: LcncNode): LcncProgram =
        LcncProgram(name, nodes.size j { i: Int -> nodes[i] }, emptySeriesOf())

    private suspend fun withBinding(block: suspend (CCEK.CcekReactorBinding) -> Unit) {
        val reactor = MuxReactorElement()
        reactor.open()
        try {
            block(CCEK.initialize(reactor))
        } finally {
            reactor.close()
        }
    }

    private fun assertClosed(run: LcncCcekAssembly.Run) {
        assertTrue(run.result.isCompleted)
        assertTrue(run.scope.coroutineContext.job.isCompleted)
        assertFalse(run.node.isActive)
        assertEquals(ElementState.CLOSED, run.node.lifecycleState)
        assertTrue(run.node.supervisor.isCompleted)
        assertEquals(0, run.node.childScopeCount)
        assertFalse(run.result.children.any(), "result must release all receipt and walker jobs")
    }

    @Test
    fun runnerSeesHostKeysAndBindingReactorAcrossRings() = runTest {
        withBinding { binding ->
            withBinding { otherBinding ->
                val host = HostElement()
                val files = InMemoryFileOperations()
                val parse = ParseScope(emptySeriesOf(), 0 j 0)
                val htx = HtxElement(routeService = object : HtxRouteService {
                    override suspend fun exchange(state: HtxExchangeState, request: HtxRequest): HtxExchangeResult =
                        error("context lookup must not dispatch HTTP")
                })
                htx.open()
                try {
                    val seen = mutableListOf<CoroutineContext>()
                    val runner = LcncRunner(mapOf(
                        "probe" to LcncNodeRunner { _, _ ->
                            seen += currentCoroutineContext()
                            mapOf("ok" to true)
                        },
                    ))
                    val caller = currentCoroutineContext().job
                    val children = caller.children.toList()
                    val run = LcncCcekAssembly(binding, runner).launch(
                        "context-gate",
                        program("context-gate", LcncNode("p", "probe"),
                            LcncNode("ring", "scope", children = s_[LcncNode("q", "probe")])),
                        context = currentCoroutineContext() + host + htx + files + parse + otherBinding.reactor,
                    )
                    run.result.await()
                    assertEquals(listOf(0, 1), seen.map { assertNotNull(it[LcncScopeFrame]).depth })
                    for (context in seen) {
                        assertSame(host, context[HostElement])
                        assertSame(htx, context[HtxKey])
                        assertSame(files, context[FileOperations])
                        assertSame(parse, context[ParseScopeKey])
                        assertSame(binding.reactor, context[MuxReactorElement])
                        assertSame(run.node, context[ArticulatedNode])
                    }
                    assertClosed(run)
                    assertEquals(children, caller.children.toList())
                    assertFalse(binding.reactor.supervisor.children.any())
                    assertTrue(caller.isActive)
                    assertTrue(htx.supervisor.isActive, "borrowed host elements remain caller-owned")
                    assertTrue(parse.supervisor.isActive)
                } finally {
                    htx.close()
                    parse.close()
                }
            }
        }
    }

    private suspend fun assertCancellation(
        cancel: suspend (LcncCcekAssembly.Run, Job, MuxReactorElement) -> Unit,
    ) {
        withBinding { binding ->
            val caller = SupervisorJob(currentCoroutineContext().job)
            try {
                val executed = mutableListOf<String>()
                val walkerStopped = CompletableDeferred<Unit>()
                val agentEntered = CompletableDeferred<Unit>()
                val agentStopped = CompletableDeferred<Unit>()
                val runner = LcncRunner(mapOf(
                    "fast" to LcncNodeRunner { node, _ ->
                        executed += node.id
                        mapOf("done" to true)
                    },
                    "slow" to LcncNodeRunner { node, _ ->
                        executed += "${node.id}:entered"
                        try {
                            awaitCancellation()
                        } finally {
                            walkerStopped.complete(Unit)
                        }
                    },
                ))
                val run = LcncCcekAssembly(binding, runner).launch(
                    "cancel-gate",
                    program("cancel-gate", LcncNode("a", "fast"), LcncNode("b", "slow"), LcncNode("c", "fast")),
                    context = currentCoroutineContext() + caller,
                )
                run.node.subscribeAgent("receipt-observer") {
                    agentEntered.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        agentStopped.complete(Unit)
                    }
                }
                agentEntered.await()
                assertTrue(caller.children.any(), "invocation must own the assembly")
                assertTrue(run.result.children.any { it === run.node.supervisor }, "result must own receipts")
                cancel(run, caller, binding.reactor)
                assertIs<CancellationException>(runCatching { run.result.await() }.exceptionOrNull())
                run.result.join()
                assertTrue(walkerStopped.isCompleted)
                assertTrue(agentStopped.isCompleted)
                assertEquals(listOf("a", "b:entered"), executed)
                assertClosed(run)
                assertFalse(caller.children.any())
                assertFalse(binding.reactor.supervisor.children.any())
            } finally {
                caller.cancelAndJoin()
            }
        }
    }

    @Test
    fun cancellingAssemblyStopsWalkerAndReceiptAgent() = runTest {
        assertCancellation { run, caller, reactor ->
            run.cancel("assembly abort")
            assertTrue(caller.isActive)
            assertTrue(reactor.supervisor.isActive)
        }
    }

    @Test
    fun cancellingResultStopsWalkerAndReceiptAgent() = runTest {
        assertCancellation { run, caller, reactor ->
            run.result.cancel(CancellationException("result abort"))
            assertTrue(caller.isActive)
            assertTrue(reactor.supervisor.isActive)
        }
    }

    @Test
    fun cancellingCallerStopsAssemblyWithoutCancellingReactor() = runTest {
        assertCancellation { _, caller, reactor ->
            caller.cancel(CancellationException("caller abort"))
            assertTrue(reactor.supervisor.isActive)
        }
    }

    @Test
    fun cancellingReactorStopsAssemblyWithoutCancellingCaller() = runTest {
        assertCancellation { _, caller, reactor ->
            reactor.close()
            assertTrue(caller.isActive)
        }
    }

    @Test
    fun cancelledCallerDoesNotStartWalkerOrLeaveReceiptJobs() = runTest {
        withBinding { binding ->
            val caller = Job().apply { cancel() }
            var executed = false
            val runner = LcncRunner(mapOf("probe" to LcncNodeRunner { _, _ ->
                executed = true
                emptyMap()
            }))
            val run = LcncCcekAssembly(binding, runner).launch(
                "cancelled-entry", program("cancelled-entry", LcncNode("p", "probe")),
                context = currentCoroutineContext() + caller,
            )
            assertIs<CancellationException>(runCatching { run.result.await() }.exceptionOrNull())
            run.result.join()
            assertFalse(executed)
            assertClosed(run)
            assertFalse(caller.children.any())
            assertFalse(binding.reactor.supervisor.children.any())
        }
    }

    @Test
    fun resultWaitsForFinishReceiptProjectionAndAgentCompletion() = runTest {
        withBinding { binding ->
            val releaseWalker = CompletableDeferred<Unit>()
            val receiptEntered = CompletableDeferred<CoroutineContext>()
            val releaseReceipt = CompletableDeferred<Unit>()
            val host = HostElement()
            val runner = LcncRunner(mapOf("ok" to LcncNodeRunner { _, _ ->
                releaseWalker.await()
                mapOf("value" to 1)
            }))
            val run = LcncCcekAssembly(binding, runner).launch(
                "receipt-gate", program("receipt-gate", LcncNode("n", "ok")),
                context = currentCoroutineContext() + host,
            )
            val owner = currentCoroutineContext().job.children.single()
            run.node.subscribeAgent("receipt-observer") { signal ->
                if (signal is ForgeSignal.AppendBlock && signal.properties["state"] == "finished") {
                    receiptEntered.complete(currentCoroutineContext())
                    releaseReceipt.await()
                }
            }
            releaseWalker.complete(Unit)
            val receiptContext = receiptEntered.await()
            assertSame(host, receiptContext[HostElement])
            assertSame(binding.reactor, receiptContext[MuxReactorElement])
            assertSame(run.node, receiptContext[ArticulatedNode])
            runCurrent()
            assertFalse(run.result.isCompleted, "accepted receipts and agents must finish before result")
            releaseReceipt.complete(Unit)
            assertEquals(1, run.result.await().nodeOutputs["n"]?.get("value"))
            val projection = assertIs<ForgeProjection.MarkdownChanged>(run.node.projections.replayCache.last())
            assertTrue("lcnc:receipt-gate:started" in projection.markdown)
            assertTrue("lcnc:receipt-gate:finished" in projection.markdown)
            assertEquals(listOf("started", "finished"), run.node.recording().map {
                (it as ForgeSignal.AppendBlock).properties["state"]
            })
            assertClosed(run)
            assertTrue(owner.isCompleted, "assembly supervisor must not remain active after normal result")
            assertFalse(binding.reactor.supervisor.children.any())
        }
    }

    @Test
    fun runnerFailureDrainsFailureReceiptAndPreservesOriginalException() = runTest {
        withBinding { binding ->
            val failure = RunnerFailure("runner rejected input")
            val caller = currentCoroutineContext().job
            val runner = LcncRunner(mapOf("fail" to LcncNodeRunner { _, _ -> throw failure }))
            val run = LcncCcekAssembly(binding, runner).launch(
                "failure-gate", program("failure-gate", LcncNode("n", "fail")),
                context = currentCoroutineContext(),
            )
            assertSame(failure, runCatching { run.result.await() }.exceptionOrNull())
            assertEquals(listOf("started", "failed"), run.node.recording().map {
                (it as ForgeSignal.AppendBlock).properties["state"]
            })
            assertClosed(run)
            assertTrue(caller.isActive)
            assertTrue(binding.reactor.supervisor.isActive)
            assertFalse(caller.children.any())
            assertFalse(binding.reactor.supervisor.children.any())
        }
    }

    @Test
    fun closedReceiptChannelDoesNotMaskRunnerFailure() = runTest {
        withBinding { binding ->
            val failure = RunnerFailure("original runner failure")
            val runner = LcncRunner(mapOf("fail" to LcncNodeRunner { _, _ ->
                assertNotNull(currentCoroutineContext()[ArticulatedNode]).signalIn.close()
                throw failure
            }))
            val run = LcncCcekAssembly(binding, runner).launch(
                "closed-receipt", program("closed-receipt", LcncNode("n", "fail")),
                context = currentCoroutineContext(),
            )
            assertSame(failure, runCatching { run.result.await() }.exceptionOrNull())
            assertClosed(run)
            assertTrue(binding.reactor.supervisor.isActive)
        }
    }

    @Test
    fun cancellationDuringReceiptDrainStopsInflightAgent() = runTest {
        withBinding { binding ->
            val releaseWalker = CompletableDeferred<Unit>()
            val receiptEntered = CompletableDeferred<Unit>()
            val receiptStopped = CompletableDeferred<Unit>()
            val runner = LcncRunner(mapOf("ok" to LcncNodeRunner { _, _ ->
                releaseWalker.await()
                emptyMap()
            }))
            val run = LcncCcekAssembly(binding, runner).launch(
                "drain-cancel", program("drain-cancel", LcncNode("n", "ok")),
                context = currentCoroutineContext(),
            )
            run.node.subscribeAgent("receipt-observer") { signal ->
                if (signal is ForgeSignal.AppendBlock && signal.properties["state"] == "finished") {
                    receiptEntered.complete(Unit)
                    try { awaitCancellation() } finally { receiptStopped.complete(Unit) }
                }
            }
            releaseWalker.complete(Unit)
            receiptEntered.await()
            runCurrent()
            assertFalse(run.result.isCompleted)
            run.result.cancel(CancellationException("abort draining receipt"))
            run.result.join()
            assertTrue(receiptStopped.isCompleted)
            assertClosed(run)
            assertTrue(currentCoroutineContext().job.isActive)
            assertTrue(binding.reactor.supervisor.isActive)
        }
    }

    @Test
    fun runnerCancellationStopsReceiptAgentBeforeDraining() = runTest {
        withBinding { binding ->
            val receiptEntered = CompletableDeferred<Unit>()
            val receiptStopped = CompletableDeferred<Unit>()
            val runner = LcncRunner(mapOf("cancel" to LcncNodeRunner { _, _ ->
                receiptEntered.await()
                throw CancellationException("runner cancelled")
            }))
            val run = LcncCcekAssembly(binding, runner).launch(
                "runner-cancel", program("runner-cancel", LcncNode("n", "cancel")),
                context = currentCoroutineContext(),
            )
            run.node.subscribeAgent("receipt-observer") {
                receiptEntered.complete(Unit)
                try { awaitCancellation() } finally { receiptStopped.complete(Unit) }
            }
            val failure = assertIs<CancellationException>(runCatching { run.result.await() }.exceptionOrNull())
            assertEquals("runner cancelled", failure.message)
            run.result.join()
            assertTrue(receiptStopped.isCompleted)
            assertClosed(run)
            assertTrue(currentCoroutineContext().job.isActive)
            assertTrue(binding.reactor.supervisor.isActive)
        }
    }

    @Test
    fun legacyLaunchWithoutContextRetainsReactorAndClosesOwnedJobs() = runTest {
        withBinding { binding ->
            val runner = LcncRunner(mapOf("probe" to LcncNodeRunner { _, _ ->
                assertSame(binding.reactor, currentCoroutineContext()[MuxReactorElement])
                assertNull(currentCoroutineContext()[HostElement])
                mapOf("ok" to true)
            }))
            val run = LcncCcekAssembly(binding, runner).launch(
                "legacy", program("legacy", LcncNode("n", "probe")), emptyMap(), false,
            )
            run.result.await()
            assertClosed(run)
            assertTrue(run.node.recording().isEmpty())
            assertFalse(binding.reactor.supervisor.children.any())
            assertTrue(binding.reactor.supervisor.isActive)
        }
    }
}
