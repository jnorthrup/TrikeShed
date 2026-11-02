@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package borg.trikeshed.userspace.nio.channels.spi

import borg.trikeshed.userspace.DatagramWheel
import borg.trikeshed.userspace.nio.ByteBuffer

/**
 * [PosixChannelOperations] on Linux with the datagram path on a [DatagramWheel]: SENDMSG and RECVMSG over a datagram
 * window whose userData is a window index go to a kernel ring of their own, as one SQE each and one CQE each, with no
 * request table in between. Every other request (socket, bind, socket options, close) takes the POSIX handle's path.
 * A kernel that will not make the ring leaves every request on that path.
 */
class LinuxChannelOperations : PosixChannelOperations() {
    override fun openChannel(entries: Int): ChannelOperations.ChannelHandle = LinuxChannelHandle(entries)
}

/** One POSIX handle for the setup calls, one wheel (made on first use) for the datagrams. One owner. */
internal class LinuxChannelHandle(private val capacity: Int) : ChannelOperations.ChannelHandle {
    private val generic = PosixChannelHandle(capacity)
    private var wheel: DatagramWheel? = null
    private var wheelRefused = false
    /** Requests on the POSIX handle: prepared and not yet submitted, and submitted or not and not yet returned by a wait. */
    private var queued = 0
    private var admitted = 0

    override val id: Int get() = generic.id

    private fun admit(rc: Int): Int {
        if (rc == 0) { queued++; admitted++ }
        return rc
    }

    private fun wheelFor(userData: Long): DatagramWheel? {
        if (userData < 0 || userData >= capacity) return null
        wheel?.let { return it }
        if (wheelRefused) return null
        val made = DatagramWheel.open(capacity)
        if (made == null) wheelRefused = true else wheel = made
        return made
    }

    override fun read(buffer: ByteBuffer, offset: Long): Int = generic.read(buffer, offset)
    override fun write(buffer: ByteBuffer, offset: Long): Int = generic.write(buffer, offset)
    override fun readv(fd: Int, buffer: ByteBuffer, userData: Long): Int = admit(generic.readv(fd, buffer, userData))
    override fun writev(fd: Int, buffer: ByteBuffer, userData: Long): Int = admit(generic.writev(fd, buffer, userData))
    override fun prepAccept(serverFd: Int, userData: Long): Int = admit(generic.prepAccept(serverFd, userData))
    override fun prepSocket(domain: Int, type: Int, protocol: Int, userData: Long): Int = admit(generic.prepSocket(domain, type, protocol, userData))
    override fun prepBind(fd: Int, address: ByteBuffer, userData: Long): Int = admit(generic.prepBind(fd, address, userData))
    override fun prepListen(fd: Int, backlog: Int, userData: Long): Int = admit(generic.prepListen(fd, backlog, userData))
    override fun prepConnect(fd: Int, address: ByteBuffer, userData: Long): Int = admit(generic.prepConnect(fd, address, userData))
    override fun prepClose(fd: Int, userData: Long): Int = admit(generic.prepClose(fd, userData))
    override fun prepSetsockopt(fd: Int, level: Int, optname: Int, value: ByteArray, userData: Long): Int =
        admit(generic.prepSetsockopt(fd, level, optname, value, userData))
    override fun sendmsg(fd: Int, msgHdrPtr: Long, userData: Long): Int = admit(generic.sendmsg(fd, msgHdrPtr, userData))
    override fun recvmsg(fd: Int, msgHdrPtr: Long, userData: Long): Int = admit(generic.recvmsg(fd, msgHdrPtr, userData))
    override fun prepCancel(target: Long, userData: Long): Int = admit(generic.prepCancel(target, userData))

    override fun sendmsg(fd: Int, datagram: ByteBuffer, userData: Long, flags: Int): Int {
        val w = wheelFor(userData) ?: return admit(generic.sendmsg(fd, datagram, userData, flags))
        return w.prep(true, fd, datagram, userData.toInt(), flags)
    }

    override fun recvmsg(fd: Int, datagram: ByteBuffer, userData: Long, flags: Int): Int {
        val w = wheelFor(userData) ?: return admit(generic.recvmsg(fd, datagram, userData, flags))
        return w.prep(false, fd, datagram, userData.toInt(), flags)
    }

    override fun submit(): Int {
        var submitted = 0
        if (queued > 0) {
            queued = 0
            val rc = generic.submit()
            if (rc < 0) return rc
            submitted += rc
        }
        val rc = wheel?.submit() ?: 0
        if (rc < 0) return rc
        return submitted + rc
    }

    override fun wait(minComplete: Int): List<ChannelResult> {
        val w = wheel
        if (admitted > 0 || w == null || w.pending == 0) {
            val done = generic.wait(minComplete)
            admitted -= done.size
            return done
        }
        // The datagrams only: the list form of waitInto.
        val ud = LongArray(w.windows)
        val res = IntArray(w.windows)
        val n = w.reap(minComplete, ud, res)
        return List(n) { ChannelResult(w.fdOf(ud[it].toInt()), res[it], ud[it]) }
    }

    override fun waitInto(minComplete: Int, userData: LongArray, res: IntArray): Int {
        val w = wheel
        if (admitted == 0) return w?.reap(minComplete, userData, res) ?: 0
        // Setup calls are in flight: their results first, then any datagram completions already on the wheel's ring.
        val done = generic.wait(minComplete)
        admitted -= done.size
        check(done.size <= userData.size && done.size <= res.size) { "${done.size} completions for ${userData.size} slots" }
        for (i in 0 until done.size) { userData[i] = done[i].userData; res[i] = done[i].res }
        return done.size + if (w != null && w.pending > 0) w.reap(0, userData, res, done.size) else 0
    }

    override fun close() {
        wheel?.close()
        wheel = null
        generic.close()
    }
}
