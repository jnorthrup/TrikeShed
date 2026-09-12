package borg.trikeshed.userspace.nio.channels.spi

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.j
import borg.trikeshed.userspace.FunctionalUringFacade
import borg.trikeshed.userspace.UringOp
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.UserspaceChannelBackend
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.openUserspaceChannelBackend
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * The common facade owns admissions and terminal CQEs. This handle only maps
 * ring tokens to caller identities, which may repeat. CQE results are verbatim:
 * EOF is zero, EAGAIN is -11, and unavailable backend opcodes settle as -95.
 * Staging socket SQEs does not imply that this backend supports live sockets.
 */
internal class PosixChannelHandle(
    private val capacity: Int,
    override val id: Int = -1,
    backend: UserspaceChannelBackend = openUserspaceChannelBackend(capacity),
) : ChannelOperations.ChannelHandle {
    private val lock = SynchronizedObject()
    private val facade = FunctionalUringFacade(capacity, backend)
    private var nextToken = 0L
    private var closing = false
    private var closed = false
    private val requests = mutableMapOf<Long, Join<Int, Long>>()

    private fun stage(submission: UringSubmission, userData: Long): Int {
        if (closing) return -9
        if (requests.size == capacity) return -11
        val token = nextToken
        facade.enqueue(submission.copy(userData = token))
        nextToken++
        requests[token] = submission.fd j userData
        return 0
    }

    private fun enqueue(submission: UringSubmission, userData: Long): Int = synchronized(lock) {
        stage(submission, userData)
    }

    /** Synchronous descriptor access requires an idle ring, preserving caller CQEs. */
    private fun execute(opcode: UringOp, buffer: ByteBuffer, offset: Long): Int = synchronized(lock) {
        if (closing || id < 0) return@synchronized -9
        if (requests.isNotEmpty()) return@synchronized -11
        val token = nextToken
        stage(UringSubmission(opcode, id, 0, buffer.remaining(), offset, buffer = buffer), 0)
        facade.submit()
        val completion = facade.wait(1).single()
        check(completion.userData == token) { "foreign descriptor completion" }
        checkNotNull(requests.remove(token))
        completion.res
    }

    override fun read(buffer: ByteBuffer, offset: Long): Int = execute(UringOp.READ, buffer, offset)

    override fun write(buffer: ByteBuffer, offset: Long): Int = execute(UringOp.WRITE, buffer, offset)

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

    override fun submit(): Int = synchronized(lock) {
        if (closing) -9 else facade.submit()
    }

    override fun wait(minComplete: Int): List<ChannelResult> = synchronized(lock) {
        facade.wait(minComplete).map { completion ->
            val (fd, caller) = checkNotNull(requests.remove(completion.userData)) {
                "Completion has no admitted channel submission: ${completion.userData}"
            }
            ChannelResult(fd, completion.res, caller)
        }
    }

    override fun close() = synchronized(lock) {
        if (!closed) {
            closing = true
            facade.closeNow()
            closed = true
        }
    }
}
