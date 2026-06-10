package borg.trikeshed.couch

import borg.trikeshed.couch.handle.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.confix.confixDocCell
import kotlin.test.*

/**
 * Red test: CollectionHandle lifecycle — open → mutate → snapshot → seal → close.
 *
 * Donor pattern: go-stopper two-phase State machine (Running→Stopping→Stopped)
 * mapped to OPEN→SEALED→CLOSED with atomic admission gate.
 *
 * Will fail to compile until CollectionHandle, Snapshot, and HandleState exist.
 */
class CollectionHandleTest {

  fun doc(vararg pairs: Pair<String, Any?>): ConfixCell =
        confixDocCell(
            keys = pairs.map { it.first },
            cells = pairs.map { it.second },
        ).cell

    @Test
    fun handleStartsOpen() {
        val h = CollectionHandle.open()
        assertEquals(HandleState.OPEN, h.state)
    }

    @Test
    fun openHandleAcceptsAppends() {
        val h = CollectionHandle.open()
        h.append(doc("x" to 1))
        assertEquals(1, h.rowCount)
    }

    @Test
    fun snapshotReturnsCurrentView() {
        val h = CollectionHandle.open()
        h.append(doc("a" to 10))
        h.append(doc("b" to 20))
        val snap = h.snapshot()
        assertEquals(2, snap.size)
        assertEquals(10, snap[0].get("a")?.reify())
    }

    @Test
    fun sealTransitionsToSealed() {
        val h = CollectionHandle.open()
        h.append(doc("x" to 1))
        h.seal()
        assertEquals(HandleState.SEALED, h.state)
    }

    @Test
    fun sealedHandleRejectsAppend() {
        val h = CollectionHandle.open()
        h.seal()
        assertFailsWith<IllegalStateException> {
            h.append(doc("x" to 1))
        }
    }

    @Test
    fun sealedHandleStillAllowsSnapshot() {
        val h = CollectionHandle.open()
        h.append(doc("x" to 42))
        h.seal()
        val snap = h.snapshot()
        assertEquals(1, snap.size)
    }

    @Test
    fun closeTransitionsToClosed() {
        val h = CollectionHandle.open()
        h.append(doc("x" to 1))
        h.seal()
        h.close()
        assertEquals(HandleState.CLOSED, h.state)
    }

    @Test
    fun closedHandleRejectsSnapshot() {
        val h = CollectionHandle.open()
        h.seal()
        h.close()
        assertFailsWith<IllegalStateException> {
            h.snapshot()
        }
    }

    @Test
    fun closeWithoutSealIsAllowedAndSealsFirst() {
        val h = CollectionHandle.open()
        h.append(doc("x" to 1))
        h.close()
        assertEquals(HandleState.CLOSED, h.state)
    }

    @Test
    fun openRejectsAppendAfterClose() {
        val h = CollectionHandle.open()
        h.close()
        assertFailsWith<IllegalStateException> {
            h.append(doc("x" to 1))
        }
    }

    @Test
    fun snapshotImmutabilityAfterSubsequentMutation() {
        val h = CollectionHandle.open()
        h.append(doc("v" to 1))
        val snap = h.snapshot()
        h.append(doc("v" to 2))
        // snapshot must not see the second row
        assertEquals(1, snap.size)
    }
}
