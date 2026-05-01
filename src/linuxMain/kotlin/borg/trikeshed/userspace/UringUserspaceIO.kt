@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package borg.trikeshed.userspace

import kotlinx.cinterop.*
import platform.posix.*
import zlinux_uring.*

actual class UserspaceBuffer(val pointer: CPointer<ByteVar>, actual val size: Int) {
    actual fun get(index: Int): Byte = pointer[index]
    actual fun put(index: Int, value: Byte) { pointer[index] = value }
}

actual class UserspaceFD(actual val id: Int) {
    actual fun isInvalid(): Boolean = id < 0
}

class UringUserspaceRing(val entries: Int) : UserspaceRing {
    private val ring: io_uring = nativeHeap.alloc()
    init { io_uring_queue_init(entries.toUInt(), ring.ptr, 0U) }

    override fun prepRead(fd: UserspaceFD, buffer: UserspaceBuffer, offset: Long, userData: Long) {
        val sqe = io_uring_get_sqe(ring.ptr) ?: return
        io_uring_prep_read(sqe, fd.id, buffer.pointer, buffer.size.toUInt(), offset.toULong())
        io_uring_sqe_set_data(sqe, userData.toCPointer<ByteVar>())
    }

    override fun prepWrite(fd: UserspaceFD, buffer: UserspaceBuffer, offset: Long, userData: Long) {
        val sqe = io_uring_get_sqe(ring.ptr) ?: return
        io_uring_prep_write(sqe, fd.id, buffer.pointer, buffer.size.toUInt(), offset.toULong())
        io_uring_sqe_set_data(sqe, userData.toCPointer<ByteVar>())
    }

    override fun prepAccept(fd: UserspaceFD, userData: Long) {
        val sqe = io_uring_get_sqe(ring.ptr) ?: return
        io_uring_prep_accept(sqe, fd.id, null, null, 0)
        io_uring_sqe_set_data(sqe, userData.toCPointer<ByteVar>())
    }

    override fun prepConnect(fd: UserspaceFD, address: String, port: Int, userData: Long) {}

    override fun prepClose(fd: UserspaceFD, userData: Long) {
        val sqe = io_uring_get_sqe(ring.ptr) ?: return
        io_uring_prep_close(sqe, fd.id)
        io_uring_sqe_set_data(sqe, userData.toCPointer<ByteVar>())
    }

    override fun submit(): Int = io_uring_submit(ring.ptr)

    override fun wait(minComplete: Int): List<UserspaceIOResult> = memScoped {
        val results = mutableListOf<UserspaceIOResult>()
        val cqePtr = alloc<CPointerVar<io_uring_cqe>>()
        if (io_uring_wait_cqe(ring.ptr, cqePtr.ptr) >= 0) {
            val cqe = cqePtr.value!!.pointed
            results.add(UserspaceIOResult(cqe.res, cqe.user_data.toLong()))
            io_uring_cqe_seen(ring.ptr, cqe.ptr)
        }
        results.addAll(peek())
        return results
    }

    override fun peek(): List<UserspaceIOResult> = memScoped {
        val results = mutableListOf<UserspaceIOResult>()
        val cqePtr = alloc<CPointerVar<io_uring_cqe>>()
        while (io_uring_peek_cqe(ring.ptr, cqePtr.ptr) >= 0) {
            val cqe = cqePtr.value!!.pointed
            results.add(UserspaceIOResult(cqe.res, cqe.user_data.toLong()))
            io_uring_cqe_seen(ring.ptr, cqe.ptr)
        }
        return results
    }
}

object LinuxUserspaceSPI : UserspaceSPI {
    override fun createRing(entries: Int): UserspaceRing = UringUserspaceRing(entries)
    override fun openFile(path: String, readOnly: Boolean): UserspaceFD = UserspaceFD(platform.posix.open(path, if (readOnly) platform.posix.O_RDONLY else platform.posix.O_RDWR or platform.posix.O_CREAT, 0))
    override fun createSocket(domain: Int, type: Int, protocol: Int): UserspaceFD = UserspaceFD(platform.posix.socket(domain, type, protocol))
    override fun wrapBuffer(byteArray: ByteArray): UserspaceBuffer {
        val pinned = byteArray.pin()
        return UserspaceBuffer(pinned.addressOf(0), byteArray.size)
    }
}

actual val userspaceSPI: UserspaceSPI = LinuxUserspaceSPI
