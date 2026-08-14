package borg.trikeshed.context.lcnc

import borg.trikeshed.context.lcnc.LcncFanoutElement
import borg.trikeshed.lib.j
import borg.trikeshed.userspace.reactor.KanbanEvent
import borg.trikeshed.userspace.reactor.KanbanFSM
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TDD-RED: the signal spine midpoint.
 *
 * user-signals (NUID dispatch) → lcnc (LcncFanoutElement reduction)
 * → forge-ui/board semantics (KanbanFSM.SignalFacetReduced).
 *
 * The FSM branch for SignalFacetReduced exists (KanbanFSM.kt:211) but had
 * ZERO emitters — vocabulary without execution. This test pins the first
 * production emitter: a dispatch that executes a reduction MUST land as a
 * SignalFacetReduced event on the kanban event flow.
 */
class LcncFanoutSignalSpineTest {

    @AfterTest
    fun resetFsm() = KanbanFSM.reset()

    private fun stubReduction(onExecute: () -> Unit = {}): borg.trikeshed.reduction.LcncReduction<Any, Any, Any, Any> =
        object : borg.trikeshed.reduction.LcncReduction<Any, Any, Any, Any> {
            override val keyAlg: borg.trikeshed.reduction.KeyAlg<Any> get() = object :
                borg.trikeshed.reduction.KeyAlg<Any> {
                override val extractor: borg.trikeshed.reduction.KeyExtractor<Any, Any> =
                    borg.trikeshed.reduction.KeyExtractor { it }
                override val hierarchy: borg.trikeshed.reduction.KeyHierarchy<Any> =
                    object : borg.trikeshed.reduction.KeyHierarchy<Any> {
                        override val levels: List<borg.trikeshed.reduction.KeyExtractor<Any, Any>> = emptyList()
                        override fun compositeKey(input: Any): List<Any> = emptyList()
                        override fun prefix(key: List<Any>, depth: Int): List<Any> = emptyList()
                    }
                override val order: borg.trikeshed.reduction.KeyOrder<Any> = object :
                    borg.trikeshed.reduction.KeyOrder<Any> {
                    override fun compare(a: Any, b: Any): Int = 0
                }
            }
            override val valueAlg: borg.trikeshed.reduction.ValueAlg<Any, Any>
                get() = object : borg.trikeshed.reduction.ValueAlg<Any, Any> {
                    override val folder = borg.trikeshed.reduction.Folder<Any, Any> { acc, _ -> acc }
                    override val merger = borg.trikeshed.reduction.Merger<Any> { _ -> Any() }
                    override val initial = Any()
                }
            override val phaseAlg: borg.trikeshed.reduction.PhaseAlg
                get() = TODO("Not used by this test")
            override val carrierAlg: borg.trikeshed.reduction.CarrierAlg<Any>
                get() = object : borg.trikeshed.reduction.CarrierAlg<Any> {
                    override val carrier: (Any) -> borg.trikeshed.reduction.ReductionCarrier<Any> =
                        { borg.trikeshed.reduction.emptySeriesCarrier() }
                }
            override fun execute(input: borg.trikeshed.reduction.ReductionCarrier<*>): Any {
                onExecute()
                return "reduced"
            }
            override fun executeWithCheckpoints(input: borg.trikeshed.reduction.ReductionCarrier<*>):
                borg.trikeshed.reduction.ReductionResult<Any> =
                TODO("Not used by this test")
        }

    @Test
    fun dispatchExecutingReductionEmitsSignalFacetReduced() = runTest {
        val nuidFanout = borg.trikeshed.context.nuid.NuidFanoutElement()
        nuidFanout.open()

        nuidFanout.register(
            borg.trikeshed.context.nuid.Workgroup(
                name = "signal-spine-worker",
                scope = borg.trikeshed.context.nuid.Subnet.core,
                traits = borg.trikeshed.context.nuid.TraitSpace {
                    1 j { borg.trikeshed.context.nuid.Capability.Process("signal_facet") }
                }
            )
        )

        var executed = false
        val lcncFanout = LcncFanoutElement(
            nuidFanout,
            mapOf("process" to stubReduction { executed = true })
        )
        lcncFanout.open()

        val slot = nuidFanout.slotOf("signal-spine-worker")
        assertNotNull(slot)
        val consumer = launch { slot.consume() }

        KanbanFSM.reset()
        val nuid = borg.trikeshed.context.nuid.nuid(
            borg.trikeshed.context.nuid.Capability.Process("signal_facet"),
            borg.trikeshed.context.nuid.Nonce.RandomBytes(),
            borg.trikeshed.context.nuid.Subnet.core
        )

        val result = lcncFanout.dispatch(nuid, "payload")

        // The reduction ran and returned its value, marked with the spine
        // classification (facet j causal j pointcut) j value.
        assertTrue(executed, "reduction must execute")
        val markedResult = result as? borg.trikeshed.lib.Join<*, *> ?: error("dispatch must return MarkedResult")
        assertEquals("reduced", markedResult.b)
        @Suppress("UNCHECKED_CAST")
        val spine = markedResult.a as borg.trikeshed.lib.Join<borg.trikeshed.lib.Join<FacetMark, CausalMark>, PointcutMark>
        assertEquals(FacetMark.Logic, spine.a.a)
        assertEquals(CausalMark.Dispatched, spine.a.b)
        assertEquals(PointcutMark.AfterGet, spine.b)

        // The midpoint edge: the reduction MUST have emitted SignalFacetReduced.
        // dispatch is synchronous w.r.t. the emission, so the replay cache
        // (replay=64) already holds it — no waiting required.
        val event = KanbanFSM.kanbanEvents.replayCache.lastOrNull { it is KanbanEvent.SignalFacetReduced }
            as? KanbanEvent.SignalFacetReduced
        assertNotNull(event, "SignalFacetReduced must appear in the kanban event replay cache")
        assertEquals("process", event.facetKey)
        assertEquals("reduced", event.reducedValue)
        assertTrue(event.timestampMs > 0L, "timestampMs must be a real wall-clock epoch ms")

        // And the FSM branch consumes it (KanbanFSM.kt:211 — previously dead).
        val state = KanbanFSM.reduce(event)
        assertEquals("SignalFacetReduced", state.lastEventKind)

        consumer.cancel()
        nuidFanout.close()
    }
}
