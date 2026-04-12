package borg.trikeshed.platform.nio

/** Operation types for NIO backends */
enum class OpType { Read, Write, Accept, Connect, PollAdd, PollRemove, Nop }

/** A completion event from the NIO backend */
data class Completion(
    val userData: Long,
    val result: Result<Int>,
    val opType: OpType,
)

/** Registration token for a monitored file descriptor */
typealias Token = Long

/** Interest flags for file descriptor monitoring */
data class Interest(
    val readable: Boolean,
    val writable: Boolean,
) {
    companion object {
        val READABLE = Interest(readable = true, writable = false)
        val WRITABLE = Interest(readable = false, writable = true)
        val READ_WRITE = Interest(readable = true, writable = true)
    }
}

/** Backend configuration */
data class BackendConfig(
    var entries: Int = 256,
    var sqpoll: Boolean = false,
    var iopoll: Boolean = false,
)

/** Platform-agnostic NIO backend trait */
interface PlatformBackend {
    fun register(fd: Int, token: Token, interest: Interest)
    fun reregister(fd: Int, token: Token, interest: Interest)
    fun unregister(fd: Int)
    fun submitRead(fd: Int, buf: ByteArray, userData: Long)
    fun submitWrite(fd: Int, buf: ByteArray, userData: Long)
    fun submitReadAt(fd: Int, offset: Long, buf: ByteArray, userData: Long)
    fun submitWriteAt(fd: Int, offset: Long, buf: ByteArray, userData: Long)
    fun submitPoll(fd: Int, interest: Interest, userData: Long)
    fun submitNop(userData: Long)
    fun submit(): Result<Long>
    fun wait(min: Int): Result<Long>
    fun peek(): Result<Long>
    fun pollCompletion(): Result<Completion?>
    fun pollCompletions(completions: Array<Completion?>): Result<Int>
    fun asRawFd(): Int? = null
}

/** Detect the best available backend for the current platform */
expect fun detectBackend(config: BackendConfig): PlatformBackend
