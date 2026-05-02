@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package borg.trikeshed.userspace

import kotlinx.cinterop.*
import platform.posix.*

actual class UserspaceBuffer(private val backing: ByteArray) {
    actual val size: Int get() = backing.size
    actual fun get(index: Int): Byte = backing[index]
    actual fun put(index: Int, value: Byte) { backing[index] = value }
}

actual class UserspaceFD(actual val id: Int) {
    actual fun isInvalid(): Boolean = id < 0
}

class PosixUserspaceRing(val entries: Int) : UserspaceRing {
    private val pendingReads = mutableListOf<Triple<UserspaceFD, UserspaceBuffer, Long>>()
    private val pendingWrites = mutableListOf<Triple<UserspaceFD, UserspaceBuffer, Long>>()
    private val pendingCloses = mutableListOf<Pair<UserspaceFD, Long>>()
    private val results = mutableListOf<UserspaceIOResult>()

    override fun prepRead(fd: UserspaceFD, buffer: UserspaceBuffer, offset: Long, userData: Long) {
        pendingReads.add(Triple(fd, buffer, userData))
    }

    override fun prepWrite(fd: UserspaceFD, buffer: UserspaceBuffer, offset: Long, userData: Long) {
        pendingWrites.add(Triple(fd, buffer, userData))
    }

    override fun prepAccept(fd: UserspaceFD, userData: Long) {
        // TODO: implement accept
    }

    override fun prepConnect(fd: UserspaceFD, address: String, port: Int, userData: Long) {
        // TODO: implement connect
    }

    override fun prepClose(fd: UserspaceFD, userData: Long) {
        pendingCloses.add(Pair(fd, userData))
    }

    override fun submit(): Int {
        var submitted = 0
        pendingReads.forEach { (fd, buffer, userData) ->
            memScoped {
                val buf = allocArray<ByteVar>(buffer.size)
                val res = platform.posix.read(fd.id, buf, buffer.size.toULong())
                results.add(UserspaceIOResult(res.toInt(), userData))
                submitted++
            }
        }
        pendingWrites.forEach { (fd, buffer, userData) ->
            memScoped {
                val buf = allocArray<ByteVar>(buffer.size)
                for (i in 0 until buffer.size) buf[i] = buffer.get(i)
                val res = platform.posix.write(fd.id, buf, buffer.size.toULong())
                results.add(UserspaceIOResult(res.toInt(), userData))
                submitted++
            }
        }
        pendingCloses.forEach { (fd, userData) ->
            val res = platform.posix.close(fd.id)
            results.add(UserspaceIOResult(res, userData))
            submitted++
        }
        pendingReads.clear()
        pendingWrites.clear()
        pendingCloses.clear()
        return submitted
    }

    override fun wait(minComplete: Int): List<UserspaceIOResult> {
        submit()
        val ret = results.toList()
        results.clear()
        return ret
    }

    override fun peek(): List<UserspaceIOResult> {
        val ret = results.toList()
        results.clear()
        return ret
    }
}

object PosixUserspaceSPI : UserspaceSPI {
    override fun createRing(entries: Int): UserspaceRing = PosixUserspaceRing(entries)
    override fun openFile(path: String, readOnly: Boolean): UserspaceFD {
        val flags = if (readOnly) platform.posix.O_RDONLY else (platform.posix.O_RDWR.toInt() or platform.posix.O_CREAT.toInt())
        val mode: UInt = 438u // 0666 in octal
        return UserspaceFD(platform.posix.open(path, flags, mode))
    }
    override fun createSocket(domain: Int, type: Int, protocol: Int): UserspaceFD = UserspaceFD(platform.posix.socket(domain, type, protocol))
    override fun wrapBuffer(byteArray: ByteArray): UserspaceBuffer = UserspaceBuffer(byteArray)
}

actual val userspaceSPI: UserspaceSPI = PosixUserspaceSPI
