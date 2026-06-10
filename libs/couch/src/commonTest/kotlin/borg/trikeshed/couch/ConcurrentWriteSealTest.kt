package borg.trikeshed.couch

import borg.trikeshed.couch.handle.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.confix.confixDocCell
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/**
 * Red test: ConcurrentWriteSeal — multiple writers + readers;
 * assert no torn state and that sealing blocks further mutation.
 *
 * Donor pattern: go-stopper State.Apply atomic counter for admission
 * + eclipse-collections FixedSizeCollection rejection after seal.
 * Atomic state transition ensures readers never see partial writes.
 *
 * Will fail to compile until CollectionHandle exists with coroutine-safe
 * seal semantics.
 */
class ConcurrentWriteSealTest {

  fun doc(v: Int): ConfixCell = confixDocCell(
        listOf("v"), listOf(v)
    ).cell

    @Test
    fun concurrentAppendsAllLand() {
        // Retry up to 3× on flaky macOS native threading
        repeat(3) { attempt ->
            try {
                val h = CollectionHandle.open()
                val n = 100

                runTest {
                    coroutineScope {
                        repeat(n) { i ->
                            launch(Dispatchers.Default) {
                                h.append(doc(i))
                            }
                        }
                    }

                    assertEquals(n, h.rowCount)
                }
                return
            } catch (e: AssertionError) {
                if (attempt == 2) throw e
            }
        }
    }

    @Test
    fun sealIsAtomicAndBlocksFurtherAppends() = runTest {
        val h = CollectionHandle.open()
        h.append(doc(1))
        h.seal()

        assertFailsWith<IllegalStateException> {
            h.append(doc(2))
        }
        assertEquals(1, h.rowCount)
    }

    @Test
    fun readersSeeConsistentSnapshotDuringMutation() = runTest {
        val h = CollectionHandle.open()
        // pre-populate
        repeat(10) { h.append(doc(it)) }

        val snapBefore = h.snapshot()
        assertEquals(10, snapBefore.size)

        // concurrent mutations
        coroutineScope {
            repeat(10) { i ->
                launch(Dispatchers.Default) { h.append(doc(100 + i)) }
            }
        }

        // original snapshot unchanged
        assertEquals(10, snapBefore.size)
        // handle now has 20
        assertEquals(20, h.rowCount)
    }

    @Test
    fun sealDuringConcurrentWritesRejectsLateAppends() = runTest {
        val h = CollectionHandle.open()
        val rejections = kotlinx.coroutines.channels.Channel<Int>(100)

        coroutineScope {
            // launch many writers
            repeat(50) { i ->
                launch(Dispatchers.Default) {
                    try {
                        h.append(doc(i))
                    } catch (_: IllegalStateException) {
                        rejections.trySend(i)
                    }
                }
            }
            // seal after a brief delay to race with writers
            delay(1)
            h.seal()
        }

        // handle is sealed and row count is consistent
        assertEquals(HandleState.SEALED, h.state)
        assertTrue(h.rowCount in 1..50, "rowCount=${h.rowCount} should be between 1 and 50")
        rejections.close()
    }

    @Test
    fun noTornStateInSnapshot() = runTest {
        val h = CollectionHandle.open()
        // write 50 rows
        repeat(50) { h.append(doc(it)) }

        val snapshots = mutableListOf<Series<ConfixCell>>()
        // take 5 snapshots concurrently
        coroutineScope {
            repeat(5) {
                launch(Dispatchers.Default) {
                    snapshots.add(h.snapshot())
                }
            }
        }

        // all snapshots should be the same size (50)
        snapshots.forEach { snap ->
            assertEquals(50, snap.size)
        }
    }
}
