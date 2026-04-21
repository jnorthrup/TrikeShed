package borg.trikeshed.userspace.nio

/**
 * Cross-platform NIO backend abstractions ported from literbike.
 */

enum class OpType {
    Read, Write, Accept, Connect, PollAdd, PollRemove, Nop
}

data class Completion(
    val userData: Long,
    val result: Result<Int>,
    val opType: OpType
)

interface NioObject {
    fun asRawFd(): Int?
    fun isOpen(): Boolean
}

interface NioBuffer : NioObject {
    val size: Int
    fun clear()
}

interface PlatformBackend {
    fun register(fd: Int, token: Long, interest: borg.trikeshed.userspace.reactor.Interest): Result<Unit>
    fun reregister(fd: Int, token: Long, interest: borg.trikeshed.userspace.reactor.Interest): Result<Unit>
    fun unregister(fd: Int): Result<Unit>
    fun submitRead(fd: Int, buf: ByteArray, userData: Long): Result<Unit>
    fun submitWrite(fd: Int, buf: ByteArray, userData: Long): Result<Unit>
    fun submit(): Result<Long>
    fun wait(min: Int): Result<Long>
    fun pollCompletion(): Result<Completion?>
}

data class BackendConfig(
    val entries: Int = 256,
    val sqpoll: Boolean = false,
    val iopoll: Boolean = false
)
