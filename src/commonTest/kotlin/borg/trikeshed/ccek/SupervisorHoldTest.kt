package borg.trikeshed.ccek

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `hold` generalises the supervisor from fanout-only to any resource, which is the orchestration
 * discount: release becomes a property of the hierarchy instead of a property of remembering.
 *
 * Both defects this closes were live in this repo. `OroborosDaemon`'s `--once` hung forever because
 * shutdown cancelled jobs by name and one launch was not on the list. `GuestModules` mounted
 * `URLClassLoader`s — open jar handles the daemon executes code from — into a static map that
 * nothing ever closed. Neither is a coding mistake so much as the predictable end state of a
 * hand-maintained cancel list.
 */
class SupervisorHoldTest {

    private class Probe : CancelToken {
        var cancels = 0
        override fun cancel() { cancels++ }
    }

    @Test
    fun closingReleasesEverythingHeld() {
        val s = RealSupervisorJob("t").also { it.open() }
        val a = Probe(); val b = Probe(); val c = Probe()
        s.hold(a); s.hold(b); s.hold(c)
        assertEquals(0, a.cancels + b.cancels + c.cancels, "nothing releases before close")
        s.close()
        assertEquals(1, a.cancels); assertEquals(1, b.cancels); assertEquals(1, c.cancels)
        assertEquals(FanoutLifecycle.CLOSED, s.lifecycle)
    }

    @Test
    fun aResourceHandedToAClosedSupervisorIsReleasedNotRetained() {
        // The leak this method exists to prevent: acquiring during shutdown and being kept by
        // something that will never close again. Cancelling on arrival is the only honest answer.
        val s = RealSupervisorJob("t").also { it.open(); it.close() }
        val late = Probe()
        s.hold(late)
        assertEquals(1, late.cancels, "a late resource must be released immediately")
    }

    @Test
    fun oneFailingReleaseDoesNotStrandTheRest() {
        // A resource that throws on close must not keep the ones behind it in the list. This is the
        // shape of the original bug: one uncancelled member and the whole shutdown never completes.
        val s = RealSupervisorJob("t").also { it.open() }
        val after = Probe()
        s.hold(object : CancelToken { override fun cancel() = throw IllegalStateException("boom") })
        s.hold(after)
        s.close()
        assertEquals(1, after.cancels, "a throwing token stranded the tokens after it")
    }

    @Test
    fun closeIsIdempotentAndDoesNotDoubleRelease() {
        val s = RealSupervisorJob("t").also { it.open() }
        val p = Probe()
        s.hold(p)
        s.close(); s.close()
        assertEquals(1, p.cancels, "close must not release the same resource twice")
    }

    @Test
    fun holdAndSlotShareTheOneLifecycle() {
        // The point of putting hold on the SAME supervisor as slot: a subsystem does not get to
        // have two lifecycles, one for its subscriptions and a forgotten one for its resources.
        val s = RealSupervisorJob("t").also { it.open() }
        val src = MutableObservable(1)
        val mirrored = s.slot(src)
        val res = Probe()
        s.hold(res)
        src.update(2)
        assertEquals(2, mirrored.value)
        s.close()
        assertEquals(1, res.cancels)
        src.update(3)
        assertEquals(2, mirrored.value, "close must have detached the fanout too")
        assertEquals(0, s.fanoutSubscribers)
    }

    @Test
    fun drainIsNotYetClosedSoInFlightWorkCanFinish() {
        val s = RealSupervisorJob("t").also { it.open() }
        val p = Probe()
        s.hold(p)
        s.drain()
        assertEquals(FanoutLifecycle.DRAINING, s.lifecycle)
        assertEquals(0, p.cancels, "draining must not release; that is what close is for")
        s.close()
        assertEquals(1, p.cancels)
    }
}
