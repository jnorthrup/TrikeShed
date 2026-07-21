package borg.trikeshed.context.lcnc

import borg.trikeshed.context.nuid.*
import borg.trikeshed.lcnc.reduction.LcncReduction
import borg.trikeshed.lcnc.reduction.LcncCarrierAlg
import borg.trikeshed.lcnc.reduction.ReductionCarrier
import borg.trikeshed.lcnc.reduction.ReductionResult
import borg.trikeshed.lcnc.reduction.KeyAlg
import borg.trikeshed.lcnc.reduction.ValueAlg
import borg.trikeshed.lcnc.reduction.PhaseAlg
import borg.trikeshed.lcnc.reduction.CarrierAlg
import borg.trikeshed.lcnc.reduction.ReducerRegistry
import borg.trikeshed.lib.j
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LcncFanoutElementTest {

    @Test
    fun testDispatchCallsReducerRegistryWithWinningCapability() = runTest {
        val nuidFanout = NuidFanoutElement()
        nuidFanout.open()

        val processWorkgroup = Workgroup(
            name = "process-worker",
            scope = Subnet.core,
            traits = TraitSpace { 1 j { Capability.Process } }
        )

        nuidFanout.register(processWorkgroup)

        var runForCalled = false

        val stubReduction = object : LcncReduction<Any, Any, Any, Any> {
            override val keyAlg: KeyAlg<Any> get() = TODO("Not yet implemented")
            override val valueAlg: ValueAlg<Any, Any> get() = TODO("Not yet implemented")
            override val phaseAlg: PhaseAlg get() = TODO("Not yet implemented")
            override val carrierAlg: CarrierAlg<Any>
                get() = object : CarrierAlg<Any> {
                    override val carrier: (Any) -> ReductionCarrier<Any> = { borg.trikeshed.lcnc.reduction.emptySeriesCarrier() }
                }

            override fun execute(input: ReductionCarrier<*>): Any {
                runForCalled = true
                return "success"
            }

            override fun executeWithCheckpoints(input: ReductionCarrier<*>): ReductionResult<Any> {
                TODO("Not yet implemented")
            }
        }

        // Mock ReducerRegistry temporarily to verify fanout
        val originalRegistry = ReducerRegistry.registry
        try {
            ReducerRegistry.registry = mapOf("process" to stubReduction)

            val lcncFanout = LcncFanoutElement(nuidFanout)
            lcncFanout.open()

            val testPayload = emptyArray<Any>()
            val nuid = nuid(Capability.Process, Nonce.RandomBytes(), Subnet.core)

            // Launch consumer so that nuidFanout polling loop can actually complete
            val slot = nuidFanout.slotOf("process-worker")
            assertNotNull(slot)

            val job = launch {
                val claim = slot.consume()
            }

            val result = lcncFanout.dispatch(nuid, testPayload)

            assertTrue(runForCalled, "execute should have been called on the reduction")
            assertEquals("success", result)

            job.cancel()
        } finally {
            ReducerRegistry.registry = originalRegistry
        }
    }
}
