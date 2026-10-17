package borg.trikeshed.userspace

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The emulated ring's deferred watches over loopback TCP, served by poll(2) only, by the kernel set only, and
 * across the crossover in both directions. Each SQE ends in exactly one CQE whichever mechanism carried it.
 */
class JvmReadinessTest {
    @Test fun pollOnly() = exchange(Int.MAX_VALUE)
    @Test fun kernelSetOnly() = exchange(0)
    @Test fun acrossTheCrossover() = exchange(4)

    /** The same exchange through the FFM io_uring where Linux provides one; elsewhere native discovery must refuse. */
    @Test fun kernelRingWhereLinuxHasOne() {
        if (!System.getProperty("os.name").lowercase().contains("linux")) {
            assertNull(discoverJvmUringBackend(8, UringBackendMode.AUTO).backend)
            assertFailsWith<IllegalStateException> { discoverJvmUringBackend(8, UringBackendMode.NATIVE) }
            return
        }
        val discovery = discoverJvmUringBackend(8, UringBackendMode.NATIVE)
        val backend = assertNotNull(discovery.backend, discovery.report.description)
        for (op in listOf(UringOp.POLL_ADD, UringOp.ACCEPT, UringOp.RECV, UringOp.SEND))
            assertTrue(backend.nativeCapabilities and op.mask != 0L, "$op not executed by the kernel ring")
        exchange(EmulatedRing(backend), null)
    }

    private fun exchange(crossover: Int) {
        val readiness = JvmReadiness(crossover)
        exchange(EmulatedRing(JvmUserspaceChannelBackend(readiness = readiness)), readiness)
    }

    private class Fixture(val ring: EmulatedRing) {
        var token = 1L
        val cqes = HashMap<Long, Int>()

        fun next(): Long = token++

        /** Submits and keeps whatever CQEs are already available, so the CQ never holds the next batch back. */
        fun flush() {
            ring.submit().getOrThrow()
            while (true) {
                val completion = ring.peekCqe().getOrThrow() ?: return
                assertNull(cqes.put(completion.userData, completion.res), "second CQE for ${completion.userData}")
            }
        }

        /** Submits what is staged and collects CQEs until [wanted] tokens have one each. */
        fun settle(vararg wanted: Long): Map<Long, Int> {
            ring.submit().getOrThrow()
            while (wanted.any { it !in cqes }) {
                val completion = assertNotNull(ring.waitCqeTimeout(5_000_000_000L).getOrThrow(), "no CQE within 5 s")
                assertNull(cqes.put(completion.userData, completion.res), "second CQE for ${completion.userData}")
            }
            return cqes
        }

        fun one(stage: (Long) -> Result<Unit>): Int {
            val token = next()
            stage(token).getOrThrow()
            return settle(token).getValue(token)
        }
    }

    private fun exchange(ring: EmulatedRing, readiness: JvmReadiness?) {
        val crossover = readiness?.crossover ?: 0
        ring.open(8, 0).getOrThrow()
        val count = 24
        Arena.ofConfined().use { arena ->
            val f = Fixture(ring)
            val port = ServerSocket(0).use { it.localPort }
            val address: MemorySegment = arena.allocate(16).also {
                it.set(ValueLayout.JAVA_BYTE, 0, 2)
                it.set(ValueLayout.JAVA_BYTE, 2, (port shr 8).toByte())
                it.set(ValueLayout.JAVA_BYTE, 3, port.toByte())
                it.set(ValueLayout.JAVA_BYTE, 4, 127)
                it.set(ValueLayout.JAVA_BYTE, 7, 1)
            }
            val listener = f.one { ring.prepSocket(2, 1, 0, it) }.also { assertTrue(it >= 0) }
            assertEquals(0, f.one { ring.prepBind(listener, address.address(), 16, it) })
            assertEquals(0, f.one { ring.prepListen(listener, 64, it) })
            val clients = IntArray(count) { f.one { token -> ring.prepSocket(2, 1, 0, token) }.also { assertTrue(it >= 0) } }
            // CONNECTs beyond the SQ depth: each is a deferred watch until the handshake settles.
            val connects = LongArray(count) { f.next() }
            for (k in 0 until count step 8) {
                for (j in k until minOf(count, k + 8)) ring.prepConnect(clients[j], address.address(), 16, connects[j]).getOrThrow()
                f.flush()
            }
            for (token in connects) assertEquals(0, f.settle(token).getValue(token))
            // The listener is non-blocking: an empty backlog answers -EAGAIN, and a POLLIN watch waits for more.
            val servers = ArrayList<Int>()
            while (servers.size < count) {
                val accepted = f.one { ring.prepAccept(listener, it) }
                if (accepted >= 0) servers.add(accepted)
                else {
                    assertEquals(-11, accepted)
                    assertEquals(1, f.one { ring.prepPollAdd(listener, 1, it) } and 1)
                }
            }
            val watches = LongArray(count) { f.next() }
            for (k in 0 until count step 8) {
                for (j in k until minOf(count, k + 8)) ring.prepPollAdd(servers[j], 1, watches[j]).getOrThrow()
                f.flush()
            }
            assertNull(ring.peekCqe().getOrThrow())
            if (readiness != null) {
                assertEquals(count, readiness.watches)
                if (crossover < count) assertTrue(readiness.kernel, "$count watched sockets exceed crossover $crossover")
                else assertTrue(!readiness.kernel)
            }
            val byte = arena.allocate(16).also { it.set(ValueLayout.JAVA_BYTE, 0, 7) }
            for (k in 0 until count) assertEquals(1, f.one { ring.prepSend(clients[k], byte.address(), 1, 0, it) })
            for (token in watches) assertEquals(1, f.settle(token).getValue(token) and 1)
            for (k in 0 until count) assertEquals(1, f.one { ring.prepRecv(servers[k], byte.address(), 16, 0, it) })
            // Re-armed: half removed (-ECANCELED, remover 0), one closed under its watch (-EBADF), the rest fire.
            val again = LongArray(count) { f.next() }
            for (k in 0 until count step 8) {
                for (j in k until minOf(count, k + 8)) ring.prepPollAdd(servers[j], 1, again[j]).getOrThrow()
                f.flush()
            }
            for (k in 0 until count / 2) {
                assertEquals(0, f.one { ring.prepPollRemove(again[k], it) })
                assertEquals(-125, f.settle(again[k]).getValue(again[k]))
            }
            val closed = count / 2
            assertEquals(0, f.one { ring.prepClose(servers[closed], it) })
            assertEquals(-9, f.settle(again[closed]).getValue(again[closed]))
            for (k in closed + 1 until count) assertEquals(1, f.one { ring.prepSend(clients[k], byte.address(), 1, 0, it) })
            for (k in closed + 1 until count) assertEquals(1, f.settle(again[k]).getValue(again[k]) and 1)
            if (readiness != null) {
                assertEquals(0, readiness.watches)
                if (crossover in 1 until count) assertTrue(readiness.switches >= 2, "switched ${readiness.switches} times")
            }
            // Drain ends unready watches (peers still open, nothing to read) with -ECANCELED; then nothing is admitted.
            val idle = LongArray(4) { f.next() }
            for (k in idle.indices) ring.prepPollAdd(clients[k], 1, idle[k]).getOrThrow()
            ring.submit().getOrThrow()
            ring.drain().getOrThrow()
            for (token in idle) assertEquals(-125, assertNotNull(ring.peekCqe().getOrThrow()).res)
            assertNull(ring.peekCqe().getOrThrow())
            assertTrue(ring.prepPollAdd(clients[0], 1, f.next()).isFailure)
            // Descriptors outlive the ring; the caller closes them.
            for (k in servers.indices) if (k != closed) assertEquals(0, JvmFileTable.close(servers[k]))
            assertEquals(0, JvmFileTable.close(listener))
            for (fd in clients) assertEquals(0, JvmFileTable.close(fd))
        }
        ring.close().getOrThrow()
    }
}
