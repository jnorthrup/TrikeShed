package borg.trikeshed.lcnc

import borg.trikeshed.context.nuid.*
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.job.ContentId
import borg.trikeshed.lcnc.ccek.*
import borg.trikeshed.lib.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class LcncPinnedBindingTest {
    @Test fun failedBindingPreparationLeavesNoRegistrationAndCanRetry() = runTest {
        val fanout = NuidFanoutElement(coroutineContext[Job])
        var attempt = 0
        val groups = LcncWorkgroupElement(ConfixBlackboard.empty(), fanout, { emptyMap() },
            bind = { _, _, _ ->
                when (attempt++) {
                    0 -> throw IllegalStateException("preparation failed")
                    1 -> throw CancellationException("preparation cancelled")
                }
                val cleanup: suspend (NuidFanoutElement.Claim) -> Unit = {}
                val execute: suspend (NuidFanoutElement.Claim) -> Any? = { "executed" }
                cleanup j execute
            }, parentJob = coroutineContext[Job])
        val program = LcncProgram("failure", emptySeriesOf(), emptySeriesOf())
        val cid = ContentId.of(LcncProgramConfix.toJson(program).encodeToByteArray())
        val key = "${LcncBlackboard.programKey(program.name)}@${cid.value}"
        fanout.open()
        groups.open()
        try {
            assertFailsWith<IllegalStateException> { groups.pin(program, cid, Subnet.local) }
            assertNull(fanout.slotOf(key))
            assertFailsWith<CancellationException> { groups.pin(program, cid, Subnet.local) }
            assertNull(fanout.slotOf(key))
            val slot = groups.pin(program, cid, Subnet.local)
            val request = nuid(Capability.Custom("lcnc", "program/${program.name}/${cid.value}"),
                Nonce.Restored(_a[1.toByte()]), Subnet.local)
            assertEquals("executed", fanout.dispatchTo(slot, request).completion?.await())
        } finally {
            groups.drain()
            fanout.drain()
        }
    }
}
