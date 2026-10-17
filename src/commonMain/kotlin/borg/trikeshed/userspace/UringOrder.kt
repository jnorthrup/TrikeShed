package borg.trikeshed.userspace

import borg.trikeshed.userspace.UringOp.Companion.UringSubmission

/** Whether this op is one of [ops], a mask of [UringOp.mask] bits. */
internal infix fun UringOp.of(ops: Long): Boolean = mask and ops != 0L

// `when` over the enum: an ordinal switch on every target (a Long mask test allocates on Kotlin/JS).
private fun UringOp.barrier(): Boolean = when (this) {
    UringOp.CLOSE, UringOp.FSYNC, UringOp.FTRUNCATE, UringOp.STATX -> true
    else -> false
}

private fun UringOp.positioned(): Boolean = when (this) {
    UringOp.READ, UringOp.WRITE, UringOp.READ_FIXED, UringOp.WRITE_FIXED -> true
    else -> false
}

private fun UringOp.reads(): Boolean = when (this) {
    UringOp.READ, UringOp.READ_FIXED, UringOp.RECV -> true
    else -> false
}

private fun UringOp.writes(): Boolean = when (this) {
    UringOp.WRITE, UringOp.WRITE_FIXED, UringOp.SEND -> true
    else -> false
}

/**
 * Whether [next] has to wait for [prior]'s completion rather than share a kernel submission with it: a kernel ring runs
 * the SQEs of one io_uring_enter in any order unless they are linked. They depend when they touch the same heap window
 * or native range, or order on one descriptor: a barrier (CLOSE, FSYNC, FTRUNCATE, STATX), a transfer at the descriptor
 * position, positioned ranges that overlap with a write among them, or two stream reads or two stream writes.
 */
internal fun dependsOn(next: UringSubmission, prior: UringSubmission): Boolean {
    val nextBuffer = next.buffer
    val priorBuffer = prior.buffer
    if (nextBuffer != null && priorBuffer != null) {
        if (nextBuffer === priorBuffer) return true
        if (nextBuffer.array() === priorBuffer.array()) {
            val start = nextBuffer.arrayOffset() + nextBuffer.position()
            val previous = priorBuffer.arrayOffset() + priorBuffer.position()
            if (start.toLong() < previous.toLong() + prior.len && previous.toLong() < start.toLong() + next.len) return true
        }
    }
    if (next.addr != 0L && prior.addr != 0L && next.len > 0 && prior.len > 0 &&
        (next.opcode.positioned() || next.opcode == UringOp.MADVISE) &&
        (prior.opcode.positioned() || prior.opcode == UringOp.MADVISE) &&
        next.addr.toULong() < prior.addr.toULong() + prior.len.toULong() &&
        prior.addr.toULong() < next.addr.toULong() + next.len.toULong()) return true
    if (next.fd != prior.fd || next.opcode == UringOp.NOP || prior.opcode == UringOp.NOP ||
        next.opcode == UringOp.MADVISE || prior.opcode == UringOp.MADVISE) return false
    if (next.opcode.barrier() || prior.opcode.barrier()) return true
    if (next.opcode == UringOp.OPENAT || prior.opcode == UringOp.OPENAT) return false
    if (next.opcode.positioned() && prior.opcode.positioned()) {
        if (next.offset == -1L || prior.offset == -1L) return true
        if (next.opcode.reads() && prior.opcode.reads()) return false
        return if (next.offset <= prior.offset) prior.offset - next.offset < next.len
            else next.offset - prior.offset < prior.len
    }
    return (next.opcode.reads() && prior.opcode.reads()) || (next.opcode.writes() && prior.opcode.writes())
}
