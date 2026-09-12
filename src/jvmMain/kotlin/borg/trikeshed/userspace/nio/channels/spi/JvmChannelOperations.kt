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
    private val capacity: Int,
) : ChannelOperations.ChannelHandle {
    override val id: Int = capacity // ring identity, not a descriptor
    private val facade = FunctionalUringFacade(capacity, openUserspaceChannelBackend(capacity))
    private var nextToken = 0L
    private var closed = false
    // Ring token -> (fd, caller userData); caller identities may repeat.
    private val staged = HashMap<Long, Pair<Int, Long>>()

    @Synchronized
    private fun enqueue(submission: UringSubmission, userData: Long): Int {
        if (closed) return -9
        if (staged.size == capacity) return -11
        val token = nextToken
        facade.enqueue(submission.copy(userData = token))
        nextToken++
        staged[token] = submission.fd to userData
        return 0
    }

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

    @Synchronized
    override fun submit(): Int = if (closed) -9 else facade.submit()

    @Synchronized
    override fun wait(minComplete: Int): List<ChannelResult> {
        return facade.wait(minComplete).map { cqe ->
            val (fd, caller) = checkNotNull(staged.remove(cqe.userData)) {
                "Completion has no admitted channel submission: ${cqe.userData}"
            }
            ChannelResult(fd, cqe.res, caller)
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        facade.closeNow()
    }
}
