package borg.trikeshed.userspace

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

actual class UserspaceBuffer(val nioBuffer: ByteBuffer) {
    actual val size: Int get() = nioBuffer.capacity()
    actual fun get(index: Int): Byte = nioBuffer.get(index)
    actual fun put(index: Int, value: Byte) { nioBuffer.put(index, value) }
}

actual class UserspaceFD(actual val id: Int, val channel: FileChannel? = null) {
    actual fun isInvalid(): Boolean = channel == null || !channel.isOpen
}

class NioUserspaceRing(val entries: Int) : UserspaceRing {
    private val pendingResults = mutableListOf<UserspaceIOResult>()
    private val preparedOps = mutableListOf<() -> Unit>()

    override fun prepRead(fd: UserspaceFD, buffer: UserspaceBuffer, offset: Long, userData: Long) {
        preparedOps.add {
            try {
                val res = fd.channel!!.read(buffer.nioBuffer, offset)
                synchronized(pendingResults) { pendingResults.add(UserspaceIOResult(res, userData)) }
            } catch (e: Exception) {
                synchronized(pendingResults) { pendingResults.add(UserspaceIOResult(-1, userData)) }
            }
        }
    }

    override fun prepWrite(fd: UserspaceFD, buffer: UserspaceBuffer, offset: Long, userData: Long) {
        preparedOps.add {
            try {
                val res = fd.channel!!.write(buffer.nioBuffer, offset)
                synchronized(pendingResults) { pendingResults.add(UserspaceIOResult(res, userData)) }
            } catch (e: Exception) {
                synchronized(pendingResults) { pendingResults.add(UserspaceIOResult(-1, userData)) }
            }
        }
    }

    override fun prepAccept(fd: UserspaceFD, userData: Long) {}
    override fun prepConnect(fd: UserspaceFD, address: String, port: Int, userData: Long) {}

    override fun prepClose(fd: UserspaceFD, userData: Long) {
        preparedOps.add {
            try {
                fd.channel!!.close()
                synchronized(pendingResults) { pendingResults.add(UserspaceIOResult(0, userData)) }
            } catch (e: Exception) {
                synchronized(pendingResults) { pendingResults.add(UserspaceIOResult(-1, userData)) }
            }
        }
    }

    override fun submit(): Int {
        val count = preparedOps.size
        preparedOps.forEach { it() }
        preparedOps.clear()
        return count
    }

    override fun wait(minComplete: Int): List<UserspaceIOResult> = peek()

    override fun peek(): List<UserspaceIOResult> {
        synchronized(pendingResults) {
            val results = pendingResults.toList()
            pendingResults.clear()
            return results
        }
    }
}

object JvmUserspaceSPI : UserspaceSPI {
    private var nextFd = 1
    override fun createRing(entries: Int): UserspaceRing = NioUserspaceRing(entries)
    override fun openFile(path: String, readOnly: Boolean): UserspaceFD {
        val options = if (readOnly) setOf(StandardOpenOption.READ)
                     else setOf(StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE)
        return UserspaceFD(nextFd++, FileChannel.open(Paths.get(path), options))
    }
    override fun createSocket(domain: Int, type: Int, protocol: Int): UserspaceFD = UserspaceFD(-1, null)
    override fun wrapBuffer(byteArray: ByteArray): UserspaceBuffer = UserspaceBuffer(ByteBuffer.wrap(byteArray))
}

actual val userspaceSPI: UserspaceSPI = JvmUserspaceSPI
