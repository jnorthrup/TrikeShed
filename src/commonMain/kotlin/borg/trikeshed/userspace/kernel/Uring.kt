package borg.trikeshed.userspace.kernel

import borg.trikeshed.context.BitMasked

/**
 * Kernel io_uring interface ported from literbike.
 * High-performance, zero-overhead interface to io_uring.
 */

enum class UringSetupFlags : BitMasked<UInt> {
    IOPOLL,
    SQPOLL,
    SQ_AFF,
    CQSIZE,
    RESV1,
    RESV2,
    RESV3,
    RESV4,
    RESV5,
    RESV6,
    RESV7,
    RESV8,
    SINGLE_ISSUER,
    DEFER_TASKRUN;

    override val mask: UInt get() = 1u shl ordinal

    companion object {
        fun toMask(flags: Iterable<UringSetupFlags>): UInt =
            flags.fold(0u) { acc, flag -> acc or flag.mask }
    }
}

/** io_uring system call numbers */
enum class UringSyscall(val number: Long) {
    SETUP(425L),
    ENTER(426L),
    REGISTER(427L);
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
    SEND(11);

    companion object {
        fun fromCode(code: Byte): OpCode? = entries.find { it.code == code }
    }
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
