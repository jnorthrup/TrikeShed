package borg.trikeshed.couch

import borg.trikeshed.job.CasStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The daemon regression behind "_changes resumes after the sequence without
 * going backwards": many writing coroutines (boot reconcile, worktree-quake
 * reconcile, panels, replication) share ONE ingress; the unsynchronized
 * rev-read → sequence++ → commit interleaving minted equal/reordered
 * sequences, tripping CouchHeadProjection's monotonicity guard and ABORTING
 * whichever reconcile lost — a boot that lost left the Rete/blackboard plane
 * with zero facts. The ingress commit path is now one critical section.
 */
class CouchIngressConcurrencyTest {

    @Test
    fun concurrentWritersNeverTripTheMonotonicityGuard(): Unit = runBlocking {
        val store = CouchStoreFactory.casBacked(CasStore.inMemory())
        val failures = AtomicInteger()
        withContext(Dispatchers.Default) {
            (1..8).map { w ->
                launch {
                    for (i in 1..250) {
                        try {
                            store.put(Document("w$w-d$i", listOf(Field("n", "$i"))), null)
                        } catch (t: Throwable) {
                            failures.incrementAndGet()
                        }
                    }
                }
            }.joinAll()
        }
        assertEquals(0, failures.get(), "no writer may lose the sequence race")
        assertEquals(8 * 250, store.head.size, "every committed doc must reach the head projection")
    }

    @Test
    fun concurrentUpdatesToSameDocStayConsistent(): Unit = runBlocking {
        val store = CouchStoreFactory.casBacked(CasStore.inMemory())
        store.put(Document("contended", listOf(Field("v", "0"))), null)
        val guardTrips = AtomicInteger()
        withContext(Dispatchers.Default) {
            (1..8).map { w ->
                launch {
                    for (i in 1..100) {
                        try {
                            val rev = store.head.getRev("contended")
                            store.put(Document("contended", listOf(Field("v", "$w-$i"))), rev)
                            // stale-rev rejections (false) are FINE — optimistic concurrency;
                            // only the monotonicity guard blowing up is the bug.
                        } catch (t: IllegalArgumentException) {
                            guardTrips.incrementAndGet()
                        }
                    }
                }
            }.joinAll()
        }
        assertEquals(0, guardTrips.get(), "contended same-doc updates must reject cleanly, never trip the guard")
    }
}
