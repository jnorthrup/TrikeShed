package borg.trikeshed.couch

import borg.trikeshed.couch.handle.*
import borg.trikeshed.lib.*
import kotlin.test.*

/**
 * Red test: Snapshot isolation — two snapshots, mutate one path, other unchanged.
 *
 * Donor pattern: eclipse-collections MutableCollection.toImmutable()
 * produces a frozen copy; subsequent mutations to the mutable original
 * do not leak into the immutable snapshot.
 *
 * Will fail to compile until CollectionHandle.snapshot() returns an
 * isolated copy rather than a shared reference.
 */
class SnapshotIsolationTest {

   fun doc(vararg pairs: Pair<String, Any?>) =
        borg.trikeshed.miniduck.DocRowVec(
            pairs.map { it.first },
            pairs.map { it.second },
        )

    @Test
    fun twoSnapshotsAreIndependent() {
        val h = CollectionHandle.open()
        h.append(doc("v" to 1))

        val snap1 = h.snapshot()
        val snap2 = h.snapshot()

        // mutate after taking both snapshots
        h.append(doc("v" to 2))

        assertEquals(1, snap1.size)
        assertEquals(1, snap2.size)
        assertEquals(2, h.rowCount)
    }

    @Test
    fun mutationBetweenSnapshotsDoesNotAffectFirstSnapshot() {
        val h = CollectionHandle.open()
        h.append(doc("v" to 10))

        val snap1 = h.snapshot()
        assertEquals(1, snap1.size)
        assertEquals(10, (snap1[0] as borg.trikeshed.miniduck.DocRowVec)["v"])

        h.append(doc("v" to 20))

        val snap2 = h.snapshot()
        assertEquals(2, snap2.size)

        // snap1 must still be 1 row
        assertEquals(1, snap1.size)
    }

    @Test
    fun sealedBlockSnapshotIsFrozen() {
        val h = CollectionHandle.open()
        h.append(doc("x" to 1))
        h.append(doc("x" to 2))
        h.seal()

        val snap = h.snapshot()
        assertEquals(2, snap.size)
        // snapshot of sealed data is a stable reference; no mutation possible
    }

    @Test
    fun snapshotCellValueIsolation() {
        val h = CollectionHandle.open()
        h.append(doc("name" to "alice"))

        val snap1 = h.snapshot()
        // append more data to the handle
        h.append(doc("name" to "bob"))
        val snap2 = h.snapshot()

        assertEquals("alice", (snap1[0] as borg.trikeshed.miniduck.DocRowVec)["name"])
        assertEquals("alice", (snap2[0] as borg.trikeshed.miniduck.DocRowVec)["name"])
        assertEquals("bob", (snap2[1] as borg.trikeshed.miniduck.DocRowVec)["name"])
    }

    @Test
    fun emptySnapshotIsValid() {
        val h = CollectionHandle.open()
        val snap = h.snapshot()
        assertEquals(0, snap.size)
    }
}
