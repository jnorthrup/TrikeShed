package borg.trikeshed.lcnc.ccek

import borg.trikeshed.ccek.CCEK
import borg.trikeshed.ccek.ForgeProjection
import borg.trikeshed.lcnc.LcncNode
import borg.trikeshed.lcnc.LcncNodeRunner
import borg.trikeshed.lcnc.LcncProgram
import borg.trikeshed.lcnc.LcncRunner
import borg.trikeshed.lcnc.LcncScopeFrame
import borg.trikeshed.lib.emptySeriesOf
import borg.trikeshed.lib.j
import borg.trikeshed.lib.s_
import borg.trikeshed.userspace.reactor.MuxReactorElement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** P1 not-theater gates: cancellation, dual CCEK context, projected run receipts. */
class LcncCcekAssemblyTest {

    private fun program(name: String, vararg nodes: LcncNode): LcncProgram =
        LcncProgram(name, nodes.size j { i: Int -> nodes[i] }, emptySeriesOf())

    @Test
    fun cancellingAssemblyStopsWalkMidRing() = runBlocking {
        val reactor = MuxReactorElement()
        reactor.open()
        val binding = CCEK.initialize(reactor)
        val executed = mutableListOf<String>()
        val runner = LcncRunner(mapOf(
            "fast" to LcncNodeRunner { node, _ ->
                executed += node.id
                mapOf("done" to true)
            },
            "slow" to LcncNodeRunner { node, _ ->
                executed += "${node.id}:entered"
                delay(60_000)
                executed += "${node.id}:finished"
                mapOf("done" to true)
            },
        ))
        val run = LcncCcekAssembly(binding, runner).launch(
            "cancel-gate",
            program("cancel-gate", LcncNode("a", "fast"), LcncNode("b", "slow")),
        )
        withTimeout(5_000) {
            while ("b:entered" !in executed) delay(10)
        }
        run.cancel("gate abort")
        runCatching { run.result.await() }.exceptionOrNull().let { e ->
            assertTrue(e is CancellationException, "assembly result must cancel, got $e")
        }
        assertEquals(listOf("a", "b:entered"), executed, "fast output landed; slow never completed")
        run.node.stop()
        reactor.close()
    }

    @Test
    fun runnerSeesReactorAndRingFrameInOneContext() = runBlocking {
        val reactor = MuxReactorElement()
        reactor.open()
        val binding = CCEK.initialize(reactor)
        var seenReactor: MuxReactorElement? = null
        var seenFrame: LcncScopeFrame? = null
        val runner = LcncRunner(mapOf(
            "probe" to LcncNodeRunner { _, _ ->
                val ctx = currentCoroutineContext()
                seenReactor = ctx[MuxReactorElement.Key]
                seenFrame = ctx[LcncScopeFrame.Key]
                mapOf("ok" to true)
            }
        ))
        val run = LcncCcekAssembly(binding, runner).launch(
            "context-gate", program("context-gate", LcncNode("p", "probe")),
        )
        withTimeout(5_000) { run.result.await() }
        assertTrue(seenReactor === reactor, "MuxReactorElement must ride the assembly context")
        assertNotNull(seenFrame, "LcncScopeFrame must compose over the reactor context")
        assertEquals(0, seenFrame!!.depth)
        run.node.stop()
        reactor.close()
    }

    @Test
    fun startAndFinishReceiptsAreProjectedByArticulatedNode() = runBlocking {
        val reactor = MuxReactorElement()
        reactor.open()
        val binding = CCEK.initialize(reactor)
        val runner = LcncRunner(mapOf(
            "ok" to LcncNodeRunner { _, _ -> mapOf("value" to 1) },
        ))
        val run = LcncCcekAssembly(binding, runner).launch(
            "receipt-gate", program("receipt-gate", LcncNode("n", "ok")),
        )
        withTimeout(5_000) { run.result.await() }
        val projection = withTimeout(5_000) {
            run.node.projections.first { p ->
                p is ForgeProjection.MarkdownChanged && "lcnc:receipt-gate:finished" in p.markdown
            }
        }
        val markdown = (projection as ForgeProjection.MarkdownChanged).markdown
        assertTrue("lcnc:receipt-gate:started" in markdown, "start receipt projected")
        assertTrue("lcnc:receipt-gate:finished" in markdown, "finish receipt projected")
        run.node.stop()
        reactor.close()
    }
}
