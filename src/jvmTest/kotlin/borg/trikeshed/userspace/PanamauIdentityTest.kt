package borg.trikeshed.userspace

import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.channels.sockaddrIpv4
import java.net.InetSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The FFM kernel ring keeps its own SQE identities apart from caller tokens: any Long is a valid token, including
 * the ones its internal cancellations would once have used, and a caller's buffer is written only by its own
 * operation's terminal CQE. Linux only (elsewhere there is no kernel ring to test).
 */
class PanamauIdentityTest {
    private val linux = System.getProperty("os.name").lowercase().contains("linux")

    /** Every CQE delivered, by caller token; a second CQE for a token fails the test. */
    private class Seen(val backend: UserspaceChannelBackend) {
        val cqes = HashMap<Long, Int>()

        fun take(results: List<SelectionResult>) {
            for (r in results) assertNull(cqes.put(r.userData, r.res), "second CQE for ${r.userData}")
        }

        fun submit(vararg subs: UringSubmission) = take(backend.submitBatch(subs.toList()))

        fun await(vararg tokens: Long) {
            val deadline = System.nanoTime() + 5_000_000_000L
            while (tokens.any { it !in cqes }) {
                assertTrue(System.nanoTime() < deadline, "no CQE for ${tokens.filter { it !in cqes }} within 5 s")
                take(backend.reapCompletions(0, 50_000_000L))
            }
        }

        /** Collects for [nanos] and returns: what arrived meanwhile is in [cqes]. */
        fun idle(nanos: Long) {
            val deadline = System.nanoTime() + nanos
            while (System.nanoTime() < deadline) take(backend.reapCompletions(0, 10_000_000L))
        }
    }

    private fun connect(seen: Seen, server: ServerSocketChannel, token: Long): Pair<Int, SocketChannel> {
        seen.submit(UringSubmission(UringOp.SOCKET, 2, 0, 0, 1, userData = token))
        seen.await(token)
        val fd = seen.cqes.getValue(token)
        assertTrue(fd >= 0, "socket $fd")
        val address = ByteBuffer(sockaddrIpv4(byteArrayOf(127, 0, 0, 1), (server.localAddress as InetSocketAddress).port))
        seen.submit(UringSubmission(UringOp.CONNECT, fd, 0, address.remaining(), 0, userData = token + 1, buffer = address))
        val peer = server.accept()
        seen.await(token + 1)
        assertEquals(0, seen.cqes.getValue(token + 1))
        return fd to peer
    }

    @Test fun callerTokensNeverMeetTheKernelIdentitiesOfACloseCancellation() {
        if (!linux) return
        val backend = PanamauChannelBackend(openPanamaRing(16))
        val seen = Seen(backend)
        val peers = ArrayList<SocketChannel>()
        try {
            ServerSocketChannel.open().use { server ->
                server.bind(InetSocketAddress("127.0.0.1", 0))
                val (a, pa) = connect(seen, server, 10)
                val (b, pb) = connect(seen, server, 20)
                val (c, pc) = connect(seen, server, 30)
                peers += listOf(pa, pb, pc)
                // Watches the kernel holds on a and c, so each CLOSE issues an internal cancellation first.
                seen.submit(UringSubmission(UringOp.POLL_ADD, a, 0, 0, 0, userData = 100, operationFlags = 1),
                    UringSubmission(UringOp.POLL_ADD, c, 0, 0, 0, userData = 101, operationFlags = 1))
                assertEquals(2L, backend.counters()["native.inFlight"])

                // Same batch: CLOSE(a), then a NOP whose token was the first internal identity of the old scheme.
                seen.submit(UringSubmission(UringOp.CLOSE, a, 0, 0, 0, userData = 200),
                    UringSubmission(UringOp.NOP, -1, 0, 0, 0, userData = Long.MIN_VALUE))
                seen.await(200, Long.MIN_VALUE, 100)
                assertEquals(0, seen.cqes[200], "CLOSE")
                assertEquals(0, seen.cqes[Long.MIN_VALUE], "the NOP's own result, not the cancellation's count")
                assertEquals(-9, seen.cqes[100], "the watch its descriptor's CLOSE cancelled")

                // Same batch: CLOSE(c), then a RECV on b whose token was the next internal identity of the old scheme.
                val buffer = ByteBuffer(ByteArray(64))
                seen.submit(UringSubmission(UringOp.CLOSE, c, 0, 0, 0, userData = 201),
                    UringSubmission(UringOp.RECV, b, 0, 64, 0, userData = Long.MIN_VALUE + 1, buffer = buffer))
                seen.await(201, 101)
                seen.idle(100_000_000L)
                assertFalse(Long.MIN_VALUE + 1 in seen.cqes, "the RECV completes only with its own terminal CQE")
                assertEquals(0, buffer.position(), "no bytes reach the caller's buffer before that CQE")
                assertEquals(1L, backend.counters()["native.inFlight"])

                pb.write(java.nio.ByteBuffer.wrap("hello".encodeToByteArray()))
                seen.await(Long.MIN_VALUE + 1)
                assertEquals(5, seen.cqes[Long.MIN_VALUE + 1])
                assertEquals(5, buffer.position())
                assertEquals("hello", buffer.array().copyOfRange(0, 5).decodeToString())

                seen.submit(UringSubmission(UringOp.CLOSE, b, 0, 0, 0, userData = 202))
                seen.await(202)
                assertEquals(0L, backend.counters()["native.inFlight"])
                assertEquals(0L, backend.counters()["native.internalInFlight"])
            }
        } finally {
            for (p in peers) runCatching { p.close() }
            backend.close()
        }
    }
}
