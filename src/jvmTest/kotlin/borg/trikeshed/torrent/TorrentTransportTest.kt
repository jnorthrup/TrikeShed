package borg.trikeshed.torrent

import borg.trikeshed.userspace.UringOp
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.UringTrace
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import kotlin.test.*

/** Actual common facade SOCKET/BIND/LISTEN/ACCEPT/CONNECT/READ/WRITE/CLOSE loopback effects. */
class TorrentTransportTest {
    private suspend fun listen(scope: CoroutineScope): Pair<PeerAddress, TorrentTcpListener> {
        var last: Throwable? = null
        repeat(16) {
            val address = PeerAddress("127.0.0.1", Random.nextInt(20000, 60000))
            try { return address to TorrentTcpListener.bind(scope, address) }
            catch (failure: borg.trikeshed.userspace.nio.UringIOException) {
                if (failure.message?.contains("-98") != true && failure.message?.contains("-48") != true) throw failure
                last = failure
            }
        }
        throw IllegalStateException("No loopback test port", last)
    }

    @Test fun idleReadDoesNotBlockOppositeDirectionAndEofIsEmpty(): Unit = runBlocking {
        withTimeout(15000) {
            val (address, listener) = listen(this)
            var client: PeerStream? = null
            var server: PeerStream? = null
            try {
                val incoming = async { listener.accept() }
                val connected = TorrentTcpStream.connect(this, address, 5000).also { client = it }
                val accepted = incoming.await().also { server = it }
                val inbound = async { accepted.read(4) }
                accepted.write(byteArrayOf(1, 2, 3, 4))
                assertContentEquals(byteArrayOf(1, 2, 3, 4), connected.read(4))
                connected.write(byteArrayOf(5, 6, 7, 8))
                assertContentEquals(byteArrayOf(5, 6, 7, 8), inbound.await())
                connected.close()
                assertContentEquals(byteArrayOf(), accepted.read(4))
                accepted.close(); accepted.close()
            } finally {
                withContext(NonCancellable) { server?.close(); client?.close(); listener.close(); listener.close() }
            }
        }
    }

    @Test fun cancelledAcceptClosesAllocatedDescriptorBeforeReturning(): Unit = runBlocking {
        withTimeout(15000) {
            val accepting = AtomicReference<Job?>()
            val acceptedFd = AtomicReference<Int?>()
            val closed = ConcurrentLinkedQueue<Int>()
            val trace = object : UringTrace {
                override fun channel(id: Long, availability: String, nativeCapabilities: Long) = Unit
                override fun submit(channel: Long, submission: UringSubmission) = Unit
                override fun failed(channel: Long, submission: UringSubmission, failure: String) = Unit
                override fun complete(channel: Long, submission: UringSubmission, result: Int) {
                    if (submission.opcode == UringOp.ACCEPT && result >= 0) {
                        acceptedFd.set(result)
                        accepting.get()!!.cancel(CancellationException("Cancel exactly after ACCEPT completion"))
                    }
                    if (submission.opcode == UringOp.CLOSE && result == 0) closed += submission.fd
                }
            }
            val (address, listener) = listen(CoroutineScope(coroutineContext + trace))
            var client: PeerStream? = null
            try {
                val incoming = async(start = CoroutineStart.LAZY) { listener.accept() }
                accepting.set(incoming)
                incoming.start()
                client = TorrentTcpStream.connect(this, address, 5000)
                assertFailsWith<CancellationException> { incoming.await() }
                incoming.join()
                val allocated = assertNotNull(acceptedFd.get())
                assertTrue(closed.contains(allocated), "Accepted descriptor must receive CLOSE before listener teardown")
            } finally { withContext(NonCancellable) { client?.close(); listener.close() } }
        }
    }
}
