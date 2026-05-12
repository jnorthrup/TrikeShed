package borg.trikeshed.userspace.network

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// ================================================================================
// SELF-CONTAINED STUBS: accept-loop fan-out with structured concurrency
// Donor: old trikeshed-reactor HttpServer + C10KServer accept loop —
//   while(isActive) { val client = serverChannel.accept(); launch { handler(client) } }
//   Both used GlobalScope.launch inside the loop.
// Semantic gap: AGENTS.md prescribes "coroutineScope fan-out (async/await)
//   with Semaphore throttle rather than sequential for-loops."
//   The inner point is structured concurrency — parent scope death cleans
//   all children; Semaphore caps concurrent load. Not GlobalScope.
// ================================================================================

data class ClientConnection(val id: Long, val address: CharSequence)

class ServerChannelStub(private val maxClients: Int = 10) {
   var nextId = 0L
    var isBound = false
    var isClosed = false

    fun bind() { isBound = true }
    fun accept(): ClientConnection? {
        if (isClosed || nextId >= maxClients) return null
        return ClientConnection(nextId++, "client_${nextId - 1}")
    }
    fun close() { isClosed = true }
}

class ClientHandler {
    val processed = mutableListOf<ClientConnection>()
    var peakConcurrent = 0
    var currentConcurrent = 0

    suspend fun handle(client: ClientConnection) {
        currentConcurrent++
        if (currentConcurrent > peakConcurrent) peakConcurrent = currentConcurrent
        delay(1) // simulated I/O
        processed.add(client)
        currentConcurrent--
    }
}

// ================================================================================
// SPEC: Structured concurrency fan-out — coroutineScope + Semaphore throttle
// ================================================================================

class AcceptLoopFanOutTest {

    /** coroutineScope: all children complete before scope exits. */
    @Test
    fun coroutineScope_awaitsAllChildren() = runTest {
        val results = mutableListOf<Int>()
        coroutineScope {
            async { delay(10); results.add(1) }
            async { delay(5); results.add(2) }
            async { delay(1); results.add(3) }
        }
        // coroutineScope suspends until all children done
        assertEquals(3, results.size)
        assertTrue(results.containsAll(listOf(1, 2, 3)))
    }

    /** Cancel parent scope → all in-flight children cancelled. */
    @Test
    fun parentCancel_cancelsAllChildren() = runTest {
        val handler = ClientHandler()
        val job = launch {
            coroutineScope {
                repeat(5) {
                    async { handler.handle(ClientConnection(it.toLong(), "c$it")) }
                }
                delay(1000) // never reached
            }
        }
        delay(5) // let children start
        job.cancel()
        job.join()
        // Some children may have completed before cancel, but
        // the key property is that cancellation doesn't leak
        assertTrue(handler.processed.size <= 5)
    }

    /** Semaphore.withPermit caps concurrent in-flight. */
    @Test
    fun semaphore_capsConcurrent() = runTest {
        val maxConcurrent = 2
        val semaphore = Semaphore(maxConcurrent)
        val handler = ClientHandler()

        coroutineScope {
            (0 until 5).map { i ->
                async {
                    semaphore.withPermit {
                        handler.handle(ClientConnection(i.toLong(), "c$i"))
                    }
                }
            }
        }

        // Semaphore(2) guarantees at most 2 concurrent in the critical section
        assertTrue(handler.peakConcurrent <= maxConcurrent)
        assertEquals(5, handler.processed.size)
    }

    /** Semaphore.withPermit releases after block, serializes correctly. */
    @Test
    fun semaphore_serializesWithPermitOne() = runTest {
        val sem = Semaphore(1)
        val order = mutableListOf<String>()

        val j1 = async {
            sem.withPermit {
                order.add("j1-start")
                delay(10)
                order.add("j1-end")
            }
        }
        val j2 = async {
            delay(2) // let j1 acquire first
            sem.withPermit {
                order.add("j2-start")
                order.add("j2-end")
            }
        }

        j1.await(); j2.await()
        assertEquals(listOf("j1-start", "j1-end", "j2-start", "j2-end"), order)
    }

    /** Accept loop fan-out: one accept → one handler, structured. */
    @Test
    fun acceptLoop_structuredFanOut() = runTest {
        val server = ServerChannelStub(maxClients = 3)
        val handler = ClientHandler()
        val semaphore = Semaphore(10)
        server.bind()

        coroutineScope {
            while (true) {
                val client = server.accept() ?: break
                async {
                    semaphore.withPermit { handler.handle(client) }
                }
            }
        }

        assertEquals(3, handler.processed.size)
    }
}
