package borg.trikeshed.userspace.nio

import borg.trikeshed.userspace.reactor.Interest

/**
 * Minimal stub to unblock compilation.
 * TODO: full PlatformBackend interface
 */
interface PlatformBackend {
    fun register(fd: Int, token: Long, interest: Interest): Result<Unit>
    fun reregister(fd: Int, token: Long, interest: Interest): Result<Unit>
    fun unregister(fd: Int): Result<Unit>
    fun submitRead(fd: Int, buf: ByteArray, userData: Long): Result<Unit>
    fun submitWrite(fd: Int, buf: ByteArray, userData: Long): Result<Unit>
    fun submit(): Result<Long>
    fun wait(min: Int): Result<Long>
    fun pollCompletion(): Result<Completion?>
}

enum class OpType {
    Read, Write
}

/**
 * Minimal stub to unblock compilation.
 * TODO: full Completion with userData, result, opType
 */
data class Completion(
    val userData: Long,
    val result: Result<Int>,
    val opType: OpType,
)
