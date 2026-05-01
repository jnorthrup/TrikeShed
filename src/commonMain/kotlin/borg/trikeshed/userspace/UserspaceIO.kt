package borg.trikeshed.userspace

import kotlin.coroutines.CoroutineContext

/**
 * High-level Userspace IO Facade for Trikeshed.
 * This commonMain implementation defines the unified model for high-performance I/O.
 */

expect class UserspaceBuffer {
    val size: Int
    fun get(index: Int): Byte
    fun put(index: Int, value: Byte)
}

expect class UserspaceFD {
    val id: Int
    fun isInvalid(): Boolean
}

data class UserspaceIOResult(val res: Int, val userData: Long)

interface UserspaceRing : CoroutineContext.Element {
    override val key: CoroutineContext.Key<*> get() = Key
    companion object Key : CoroutineContext.Key<UserspaceRing>

    fun prepRead(fd: UserspaceFD, buffer: UserspaceBuffer, offset: Long, userData: Long)
    fun prepWrite(fd: UserspaceFD, buffer: UserspaceBuffer, offset: Long, userData: Long)
    fun prepAccept(fd: UserspaceFD, userData: Long)
    fun prepConnect(fd: UserspaceFD, address: String, port: Int, userData: Long)
    fun prepClose(fd: UserspaceFD, userData: Long)
    fun submit(): Int
    fun wait(minComplete: Int = 1): List<UserspaceIOResult>
    fun peek(): List<UserspaceIOResult>
}

interface UserspaceSPI {
    fun createRing(entries: Int = 256): UserspaceRing
    fun openFile(path: String, readOnly: Boolean = true): UserspaceFD
    fun createSocket(domain: Int, type: Int, protocol: Int): UserspaceFD
    fun wrapBuffer(byteArray: ByteArray): UserspaceBuffer
}

expect val userspaceSPI: UserspaceSPI
