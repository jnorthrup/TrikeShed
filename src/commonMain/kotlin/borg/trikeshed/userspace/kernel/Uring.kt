package borg.trikeshed.userspace.kernel

/**
 * Kernel io_uring interface ported from literbike.
 * High-performance, zero-overhead interface to io_uring.
 */

object UringConstants {
    const val IORING_SETUP_IOPOLL = 1u shl 0
    const val IORING_SETUP_SQPOLL = 1u shl 1
    const val IORING_SETUP_SQ_AFF = 1u shl 2
    const val IORING_SETUP_CQSIZE = 1u shl 3
    const val IORING_SETUP_SINGLE_ISSUER = 1u shl 12
    const val IORING_SETUP_DEFER_TASKRUN = 1u shl 13

    const val SYS_IO_URING_SETUP = 425L
    const val SYS_IO_URING_ENTER = 426L
    const val SYS_IO_URING_REGISTER = 427L
}

enum class OpCode(val code: Byte) {
    NOP(0),
    READV(1),
    WRITEV(2),
    READ_FIXED(4),
    WRITE_FIXED(5),
    POLL_ADD(6),
    POLL_REMOVE(7),
    RECV(10),
    SEND(11)
}

/**
 * Kernel Submission Queue Entry
 */
class KernelSQE(
    var opcode: Byte = 0,
    var flags: Byte = 0,
    var ioprio: Short = 0,
    var fd: Int = 0,
    var offAddr2: Long = 0,
    var addr: Long = 0,
    var len: Int = 0,
    var rwFlags: Int = 0,
    var userData: Long = 0,
    var bufIndex: Short = 0,
    var personality: Short = 0,
    var spliceFdIn: Int = 0,
    var addr3: Long = 0,
    var resv: Long = 0
)

/**
 * Kernel Completion Queue Entry
 */
class KernelCQE(
    var userData: Long = 0,
    var res: Int = 0,
    var flags: Int = 0
)

/**
 * Abstraction for a ported KernelUring.
 * Implementation will vary by platform.
 */
interface KernelUring {
    fun fd(): Int
    fun submitDirect(sqe: KernelSQE): Result<Unit>
    fun submitBulk(sqes: List<KernelSQE>): Result<Int>
    fun reapCompletions(): List<KernelCQE>
}
