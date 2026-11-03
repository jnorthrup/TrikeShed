package borg.trikeshed.context.nuid

import borg.trikeshed.lib.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class NuidExactDispatchTest {
    @Test fun exactRegistrationDoesNotRouteToWildcardOrSameNameReplacement() = runTest {
        val fanout = NuidFanoutElement(coroutineContext[Job])
        val capability = Capability.Custom("lcnc", "program/review/version")
        val request = nuid(capability, Nonce.Restored(_a[1.toByte()]), Subnet.parse("local.inner"))
        val pinned = Workgroup("pinned", Subnet.local, traitSpaceOf(capability))
        val nearer = Workgroup("nearer", Subnet.parse("local.inner"), traitSpaceOf(Capability.CustomAll))
        var pinnedCalls = 0
        var nearerCalls = 0
        var replacementCalls = 0
        fanout.open()
        try {
            fanout.register(pinned) { pinnedCalls++; "pinned" }
            fanout.register(nearer) { nearerCalls++; "nearer" }
            val pinnedSlot = assertNotNull(fanout.slotOf(pinned.name))

            val ordinary = fanout.dispatch(request, "ordinary")
            assertEquals("nearer", ordinary.winner)
            assertEquals("nearer", ordinary.completion?.await())

            val exact = fanout.dispatchTo(pinnedSlot, request, "exact")
            assertEquals("pinned", exact.winner)
            assertEquals("pinned", exact.completion?.await())
            assertEquals(1, nearerCalls)
            assertEquals(1, pinnedCalls)

            fanout.unregister(pinned.name)
            fanout.register(pinned) { replacementCalls++; "replacement" }
            val replacementSlot = assertNotNull(fanout.slotOf(pinned.name))
            val stale = fanout.dispatchTo(pinnedSlot, request, "stale")
            assertNull(stale.winner)
            assertNull(stale.completion)
            assertEquals(0, replacementCalls)
            assertEquals(1, nearerCalls)

            val wrongScope = nuid(capability, Nonce.Restored(_a[2.toByte()]), Subnet.parse("other.inner"))
            assertNull(fanout.dispatchTo(replacementSlot, wrongScope).winner)
            val wrongCapability = nuid(Capability.Process("other"), Nonce.Restored(_a[3.toByte()]), request.subnet)
            assertNull(fanout.dispatchTo(replacementSlot, wrongCapability).winner)
            assertEquals(0, replacementCalls)
        } finally {
            fanout.drain()
        }
    }

    @Test fun cancelledWaiterDoesNotDropAdmittedExactWorkDuringDrain() = runTest {
        val fanout = NuidFanoutElement(coroutineContext[Job])
        val capability = Capability.Custom("lcnc", "program/review/version")
        val target = Workgroup("pinned", Subnet.local, traitSpaceOf(capability))
        val request = nuid(capability, Nonce.Restored(_a[4.toByte()]), Subnet.local)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fanout.open()
        try {
            fanout.register(target) {
                entered.complete(Unit)
                release.await()
                "finished"
            }
            val admitted = fanout.dispatchTo(assertNotNull(fanout.slotOf(target.name)), request, "payload")
            val completion = assertNotNull(admitted.completion)
            entered.await()
            val waiter = launch(start = CoroutineStart.UNDISPATCHED) { completion.await() }
            waiter.cancelAndJoin()
            val draining = async(start = CoroutineStart.UNDISPATCHED) { fanout.drain() }
            assertFalse(draining.isCompleted)
            release.complete(Unit)
            draining.await()
            assertEquals("finished", completion.await())
        } finally {
            release.complete(Unit)
            fanout.drain()
        }
    }
}
