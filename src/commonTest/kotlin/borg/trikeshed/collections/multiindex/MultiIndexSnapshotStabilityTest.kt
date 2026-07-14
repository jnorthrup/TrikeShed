package borg.trikeshed.collections.multiindex

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * S1 RED — MultiIndexContainer snapshot stability after mutation.
 *
 * The plan: "a snapshot remains stable after later mutations."
 *
 * Proves: taking a snapshot captures the state at that moment, and subsequent
 * add/retract operations on the live container do not affect the snapshot.
 */
class MultiIndexSnapshotStabilityTest {

    @Test
    fun snapshotRemainsStableAfterAdd() {
        val container = MultiIndexContainer<String>()
        val byHash = MultiIndexK.ByHash { it }
        container.registerHash(byHash)

        container.add("aaa")
        container.add("bbb")

        val snap = container.snapshot()

        // Mutate the live container
        container.add("ccc")
        container.add("ddd")

        // Snapshot must be unchanged
        assertEquals(2, snap.size)
        assertEquals(setOf("aaa", "bbb"), (0 until snap.size).map { snap[it] }.toSet())
    }

    @Test
    fun snapshotRemainsStableAfterRetract() {
        val container = MultiIndexContainer<String>()
        val byHash = MultiIndexK.ByHash { it }
        container.registerHash(byHash)

        container.add("aaa")
        container.add("bbb")
        container.add("ccc")

        val snap = container.snapshot()

        // Retract from live container
        container.retract(1) // remove "bbb"

        // Snapshot must still contain all 3
        assertEquals(3, snap.size)
        assertTrue((0 until snap.size).map { snap[it] }.contains("bbb"),
            "snapshot must still contain retracted 'bbb'")
    }

    @Test
    fun snapshotIsIndependentView() {
        val container = MultiIndexContainer<Int>()
        val byOrder = MultiIndexK.ByOrder { it }
        container.registerOrder(byOrder)

        for (i in 0 until 10) container.add(i)

        val snap = container.snapshot()

        // Heavy mutation on live container
        for (i in 0 until 5) container.retract(i)
        for (i in 10 until 20) container.add(i)

        // Snapshot must show the original 10 elements in order
        val snapVals = (0 until snap.size).map { snap[it] }.filterNotNull()
        assertEquals((0 until 10).toList(), snapVals,
            "snapshot must show original state independent of mutations")
    }
}
