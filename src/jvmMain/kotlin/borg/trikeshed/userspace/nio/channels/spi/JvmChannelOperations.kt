package borg.trikeshed.userspace.nio.channels.spi

import borg.trikeshed.userspace.FunctionalUringFacade
import borg.trikeshed.userspace.UringOp
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.openUserspaceChannelBackend

/**
 * JVM actual of the channel SPI. uring-shaped only: every operation the handle
 * offers is a prepared SQE settled by exactly one CQE. Execution belongs to the
 * backend the kernel probe selected ([openUserspaceChannelBackend] — the JNI
 * ring when the host has one, the emulation otherwise); this class adds no
 * second mechanism — no selector, no worker pool, no channel registry.
 *
 * DNS is control-plane ([resolve] yields sockaddr octets for the SQE); the
 * connection itself is a CONNECT SQE like any other op.
 */
class JvmChannelOperations : ChannelOperations {

    override fun openChannel(entries: Int): ChannelOperations.ChannelHandle =
        JvmChannelHandle(entries)

    /** sockaddr octets for a CONNECT/BIND SQE; null when resolution fails. */
    override fun resolve(host: String): ByteArray? = try {
        java.net.InetAddress.getAllByName(host).firstOrNull { it.address.size == 4 }?.address
    } catch (_: Exception) { null }
}

/**
 * One ring. prep* stages SQEs on the common facade; submit/wait settle CQEs
 * with the kernel's res contract verbatim (fd for SOCKET/ACCEPT, 0 or -errno
 * otherwise, -EAGAIN = -11 while the op would block).
 */
class JvmChannelHandle(
    capacity: Int,
) : ChannelOperations.ChannelHandle {
    override val id: Int = capacity // ring identity, not a descriptor
    private val facade = FunctionalUringFacade(capacity, openUserspaceChannelBackend(capacity))
    private var nextToken = 0L
    // caller userData -> (fd, opcode) so CQEs map back to ChannelResult rows.
    private val staged = HashMap<Long, Pair<Int, UringOp>>()

    private fun enqueue(submission: UringSubmission, userData: Long): Int {
        val token = nextToken++
        facade.enqueue(submission.copy(userData = token))
        staged[token] = submission.fd to submission.opcode
        // Re-key the completion back to the caller's userData.
        completions[userData] = token
        return 0
    }

    // caller userData -> internal token awaiting its CQE
    private val completions = HashMap<Long, Long>()

    // This handle is a ring, not a descriptor.
    override fun read(buffer: ByteBuffer, offset: Long): Int = -9
    override fun write(buffer: ByteBuffer, offset: Long): Int = -9

    override fun readv(fd: Int, buffer: ByteBuffer, userData: Long): Int =
        enqueue(UringSubmission(UringOp.READ, fd, 0, buffer.remaining(), -1, buffer = buffer), userData)

    override fun writev(fd: Int, buffer: ByteBuffer, userData: Long): Int =
        enqueue(UringSubmission(UringOp.WRITE, fd, 0, buffer.remaining(), -1, buffer = buffer), userData)

    override fun prepAccept(serverFd: Int, userData: Long): Int =
        enqueue(UringSubmission(UringOp.ACCEPT, serverFd, 0, 0, 0), userData)

    override fun prepSocket(domain: Int, type: Int, protocol: Int, userData: Long): Int =
        enqueue(UringSubmission(UringOp.SOCKET, domain, 0, protocol, type.toLong()), userData)

    override fun prepBind(fd: Int, address: ByteBuffer, userData: Long): Int =
        enqueue(UringSubmission(UringOp.BIND, fd, 0, address.remaining(), 0, buffer = address), userData)

    override fun prepListen(fd: Int, backlog: Int, userData: Long): Int =
        enqueue(UringSubmission(UringOp.LISTEN, fd, 0, backlog, 0), userData)

    override fun prepConnect(fd: Int, address: ByteBuffer, userData: Long): Int =
        enqueue(UringSubmission(UringOp.CONNECT, fd, 0, address.remaining(), 0, buffer = address), userData)

    override fun prepClose(fd: Int, userData: Long): Int =
        enqueue(UringSubmission(UringOp.CLOSE, fd, 0, 0, 0), userData)

    override fun sendmsg(fd: Int, msgHdrPtr: Long, userData: Long): Int =
        enqueue(UringSubmission(UringOp.SENDMSG, fd, msgHdrPtr, 0, 0), userData)

    override fun recvmsg(fd: Int, msgHdrPtr: Long, userData: Long): Int =
        enqueue(UringSubmission(UringOp.RECVMSG, fd, msgHdrPtr, 0, 0), userData)

    override fun submit(): Int = facade.submit()

    override fun wait(minComplete: Int): List<ChannelResult> {
        val results = ArrayList<ChannelResult>()
        while (results.size < minComplete) {
            val settled = facade.wait(0)
            if (settled.isEmpty()) break
            for (cqe in settled) {
                val (fd, _) = staged.remove(cqe.userData) ?: continue
                // Find the caller userData whose token this CQE settles.
                val caller = completions.entries.firstOrNull { it.value == cqe.userData }?.key ?: continue
                completions.remove(caller)
                results.add(ChannelResult(fd, cqe.res, caller))
            }
        }
        return results
    }

    override fun close() {
        facade.closeNow()
        staged.clear()
        completions.clear()
    }
}
