package borg.trikeshed.userspace.nio.ebpf

import borg.trikeshed.userspace.UringCompletion
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission

enum class UringEbpfPhase(val code: Long) {
    SUBMIT(0),
    COMPLETE(1),
}

data class UringEbpfContext(
    val phase: UringEbpfPhase,
    val submission: UringSubmission,
    val completion: UringCompletion?,
)

object UringEbpfContextLayout {
    const val OPCODE: Int = 0
    const val FD: Int = 8
    const val LEN: Int = 16
    const val OFFSET: Int = 24
    const val FLAGS: Int = 32
    const val USER_DATA: Int = 40
    const val PHASE: Int = 48
    const val COMPLETION_RES: Int = 56
    const val COMPLETION_FLAGS: Int = 64
}

object UringEbpfInsn {
    const val R0: Int = 0
    const val R1: Int = 1
    const val R10: Int = 10

    private const val BPF_W: Int = 0x00
    private const val BPF_DW: Int = 0x18
    private const val BPF_MEM: Int = 0x60
    private const val BPF_LDX: Int = 0x01
    private const val BPF_STX: Int = 0x03

    fun raw(opcode: Int, dst: Int = 0, src: Int = 0, offset: Int = 0, imm: Int = 0): Long {
        require(dst in 0..10) { "bad dst register R$dst" }
        require(src in 0..10) { "bad src register R$src" }
        require(offset >= Short.MIN_VALUE && offset <= Short.MAX_VALUE) { "jump/memory offset out of range: $offset" }
        return (opcode.toLong() and 0xffL) or
            ((dst.toLong() and 0x0fL) shl 8) or
            ((src.toLong() and 0x0fL) shl 12) or
            ((offset.toLong() and 0xffffL) shl 16) or
            ((imm.toLong() and 0xffffffffL) shl 32)
    }

    fun mov64Imm(dst: Int, imm: Int): Long = raw(0xb7, dst = dst, imm = imm)
    fun mov64Reg(dst: Int, src: Int): Long = raw(0xbf, dst = dst, src = src)
    fun add64Imm(dst: Int, imm: Int): Long = raw(0x07, dst = dst, imm = imm)
    fun sub64Imm(dst: Int, imm: Int): Long = raw(0x17, dst = dst, imm = imm)
    fun and64Imm(dst: Int, imm: Int): Long = raw(0x57, dst = dst, imm = imm)
    fun or64Imm(dst: Int, imm: Int): Long = raw(0x47, dst = dst, imm = imm)
    fun xor64Imm(dst: Int, imm: Int): Long = raw(0xa7, dst = dst, imm = imm)
    fun add64Reg(dst: Int, src: Int): Long = raw(0x0f, dst = dst, src = src)
    fun sub64Reg(dst: Int, src: Int): Long = raw(0x1f, dst = dst, src = src)
    fun and64Reg(dst: Int, src: Int): Long = raw(0x5f, dst = dst, src = src)
    fun or64Reg(dst: Int, src: Int): Long = raw(0x4f, dst = dst, src = src)
    fun xor64Reg(dst: Int, src: Int): Long = raw(0xaf, dst = dst, src = src)
    fun ja(offset: Int): Long = raw(0x05, offset = offset)
    fun jeq64Imm(dst: Int, imm: Int, offset: Int): Long = raw(0x15, dst = dst, offset = offset, imm = imm)
    fun jne64Imm(dst: Int, imm: Int, offset: Int): Long = raw(0x55, dst = dst, offset = offset, imm = imm)
    fun jgt64Imm(dst: Int, imm: Int, offset: Int): Long = raw(0x25, dst = dst, offset = offset, imm = imm)
    fun jge64Imm(dst: Int, imm: Int, offset: Int): Long = raw(0x35, dst = dst, offset = offset, imm = imm)
    fun jeq64Reg(dst: Int, src: Int, offset: Int): Long = raw(0x1d, dst = dst, src = src, offset = offset)
    fun jne64Reg(dst: Int, src: Int, offset: Int): Long = raw(0x5d, dst = dst, src = src, offset = offset)
    fun jgt64Reg(dst: Int, src: Int, offset: Int): Long = raw(0x2d, dst = dst, src = src, offset = offset)
    fun jge64Reg(dst: Int, src: Int, offset: Int): Long = raw(0x3d, dst = dst, src = src, offset = offset)
    fun loadCtx32(dst: Int, offset: Int): Long = raw(BPF_LDX or BPF_MEM or BPF_W, dst = dst, src = R1, offset = offset)
    fun loadCtx64(dst: Int, offset: Int): Long = raw(BPF_LDX or BPF_MEM or BPF_DW, dst = dst, src = R1, offset = offset)
    fun loadStack64(dst: Int, offset: Int): Long = raw(BPF_LDX or BPF_MEM or BPF_DW, dst = dst, src = R10, offset = offset)
    fun storeStack64(src: Int, offset: Int): Long = raw(BPF_STX or BPF_MEM or BPF_DW, dst = R10, src = src, offset = offset)
    fun exit(): Long = raw(0x95)
}

sealed interface UringEbpfVerifyResult {
    object Success : UringEbpfVerifyResult
    data class Failure(val pc: Int, val reason: String) : UringEbpfVerifyResult
}

class UringEbpfProgram private constructor(
    val name: String,
    val phase: UringEbpfPhase,
    val instructions: LongArray,
) {
    fun run(context: UringEbpfContext, initialR0: Long): Long =
        UringEbpfVm.run(instructions, context, initialR0)

    companion object {
        fun of(name: String, phase: UringEbpfPhase, instructions: LongArray): UringEbpfProgram {
            val copy = instructions.copyOf()
            when (val result = UringEbpfVerifier.verify(copy)) {
                UringEbpfVerifyResult.Success -> return UringEbpfProgram(name, phase, copy)
                is UringEbpfVerifyResult.Failure ->
                    throw IllegalArgumentException("invalid uring eBPF program $name at pc ${result.pc}: ${result.reason}")
            }
        }

        fun allowAll(name: String = "allow-all"): UringEbpfProgram =
            of(name, UringEbpfPhase.SUBMIT, longArrayOf(UringEbpfInsn.mov64Imm(UringEbpfInsn.R0, 0), UringEbpfInsn.exit()))
    }
}

private enum class UringEbpfRegType {
    UNINIT,
    SCALAR,
    CTX,
    STACK,
}

private object UringEbpfVerifier {
    private const val MAX_INSNS = 4096

    fun verify(instructions: LongArray): UringEbpfVerifyResult {
        if (instructions.isEmpty()) return UringEbpfVerifyResult.Failure(0, "empty program")
        if (instructions.size > MAX_INSNS) return UringEbpfVerifyResult.Failure(0, "program too large")

        val regs = Array(11) { UringEbpfRegType.UNINIT }
        regs[UringEbpfInsn.R0] = UringEbpfRegType.SCALAR
        regs[UringEbpfInsn.R1] = UringEbpfRegType.CTX
        regs[UringEbpfInsn.R10] = UringEbpfRegType.STACK
        val stack = BooleanArray(512)

        for (pc in instructions.indices) {
            val raw = instructions[pc]
            val op = opcode(raw)
            val dst = dst(raw)
            val src = src(raw)
            val off = offset(raw)
            val cls = op and 0x07
            val base = op and 0xf0
            when {
                op == 0x95 -> Unit
                op == 0x05 -> {
                    val target = jumpTarget(pc, off, instructions.size)
                        ?: return UringEbpfVerifyResult.Failure(pc, "jump outside program")
                    if (target <= pc) return UringEbpfVerifyResult.Failure(pc, "backward jumps are not accepted")
                }
                cls == 0x05 || cls == 0x06 -> {
                    if (regs[dst] == UringEbpfRegType.UNINIT) return UringEbpfVerifyResult.Failure(pc, "R$dst is uninitialized")
                    val isRegSource = (op and 0x08) != 0
                    if (isRegSource && regs[src] == UringEbpfRegType.UNINIT) {
                        return UringEbpfVerifyResult.Failure(pc, "R$src is uninitialized")
                    }
                    when (base) {
                        0x10, 0x20, 0x30, 0x50 -> Unit
                        else -> return UringEbpfVerifyResult.Failure(pc, "unsupported jump opcode 0x${op.toString(16)}")
                    }
                    jumpTarget(pc, off, instructions.size)
                        ?: return UringEbpfVerifyResult.Failure(pc, "jump outside program")
                    if (pc + 1 >= instructions.size) return UringEbpfVerifyResult.Failure(pc, "conditional jump has no fallthrough")
                }
                cls == 0x07 -> {
                    if (dst == UringEbpfInsn.R1 || dst == UringEbpfInsn.R10) {
                        return UringEbpfVerifyResult.Failure(pc, "R$dst is not writable")
                    }
                    val isRegSource = (op and 0x08) != 0
                    if (isRegSource && regs[src] == UringEbpfRegType.UNINIT) {
                        return UringEbpfVerifyResult.Failure(pc, "R$src is uninitialized")
                    }
                    if (!isRegSource && base !in setOf(0x00, 0x10, 0x40, 0x50, 0xa0, 0xb0)) {
                        return UringEbpfVerifyResult.Failure(pc, "unsupported ALU immediate opcode 0x${op.toString(16)}")
                    }
                    if (isRegSource && base !in setOf(0x00, 0x10, 0x40, 0x50, 0xa0, 0xb0)) {
                        return UringEbpfVerifyResult.Failure(pc, "unsupported ALU register opcode 0x${op.toString(16)}")
                    }
                    regs[dst] = UringEbpfRegType.SCALAR
                }
                op == 0x61 || op == 0x79 -> {
                    if (dst == UringEbpfInsn.R1 || dst == UringEbpfInsn.R10) {
                        return UringEbpfVerifyResult.Failure(pc, "R$dst is not writable")
                    }
                    when (regs[src]) {
                        UringEbpfRegType.CTX -> if (!validContextLoad(off, if (op == 0x79) 8 else 4)) {
                            return UringEbpfVerifyResult.Failure(pc, "bad context load offset $off")
                        }
                        UringEbpfRegType.STACK -> if (!validStackRead(off, if (op == 0x79) 8 else 4, stack)) {
                            return UringEbpfVerifyResult.Failure(pc, "uninitialized stack load at $off")
                        }
                        else -> return UringEbpfVerifyResult.Failure(pc, "load source R$src is not ctx or stack")
                    }
                    regs[dst] = UringEbpfRegType.SCALAR
                }
                op == 0x7b -> {
                    if (regs[dst] != UringEbpfRegType.STACK) return UringEbpfVerifyResult.Failure(pc, "store destination must be R10")
                    if (regs[src] == UringEbpfRegType.UNINIT) return UringEbpfVerifyResult.Failure(pc, "R$src is uninitialized")
                    if (!validStackRange(off, 8)) return UringEbpfVerifyResult.Failure(pc, "bad stack store offset $off")
                    markStack(off, 8, stack)
                }
                else -> return UringEbpfVerifyResult.Failure(pc, "unsupported opcode 0x${op.toString(16)}")
            }
        }

        if (opcode(instructions.last()) != 0x95) {
            return UringEbpfVerifyResult.Failure(instructions.lastIndex, "program must end with exit")
        }
        return UringEbpfVerifyResult.Success
    }

    private fun validContextLoad(offset: Int, width: Int): Boolean =
        when (offset) {
            UringEbpfContextLayout.OPCODE,
            UringEbpfContextLayout.FD,
            UringEbpfContextLayout.LEN,
            UringEbpfContextLayout.FLAGS,
            UringEbpfContextLayout.COMPLETION_RES,
            UringEbpfContextLayout.COMPLETION_FLAGS -> width == 4 || width == 8
            UringEbpfContextLayout.OFFSET,
            UringEbpfContextLayout.USER_DATA,
            UringEbpfContextLayout.PHASE -> width == 8
            else -> false
        }

    private fun jumpTarget(pc: Int, offset: Int, size: Int): Int? {
        val target = pc + 1 + offset
        return if (target in 0 until size) target else null
    }

    private fun validStackRead(offset: Int, width: Int, stack: BooleanArray): Boolean {
        if (!validStackRange(offset, width)) return false
        val begin = 512 + offset
        return (begin until begin + width).all { stack[it] }
    }

    private fun validStackRange(offset: Int, width: Int): Boolean =
        offset < 0 && width > 0 && offset + width <= 0 && 512 + offset >= 0

    private fun markStack(offset: Int, width: Int, stack: BooleanArray) {
        val begin = 512 + offset
        for (i in begin until begin + width) stack[i] = true
    }
}

private object UringEbpfVm {
    private const val MAX_STEPS = 4096

    fun run(instructions: LongArray, context: UringEbpfContext, initialR0: Long): Long {
        val regs = LongArray(11)
        val stack = ByteArray(512)
        regs[UringEbpfInsn.R0] = initialR0
        regs[UringEbpfInsn.R1] = 1L
        regs[UringEbpfInsn.R10] = 512L

        var pc = 0
        var steps = 0
        while (pc in instructions.indices) {
            if (++steps > MAX_STEPS) throw IllegalStateException("uring eBPF program exceeded step budget")
            val raw = instructions[pc]
            val op = opcode(raw)
            val dst = dst(raw)
            val src = src(raw)
            val off = offset(raw)
            val imm = imm(raw).toLong()
            when (op) {
                0x95 -> return regs[UringEbpfInsn.R0]
                0x05 -> {
                    pc += 1 + off
                    continue
                }
                0xb7 -> regs[dst] = imm
                0xbf -> regs[dst] = regs[src]
                0x07 -> regs[dst] += imm
                0x17 -> regs[dst] -= imm
                0x47 -> regs[dst] = regs[dst] or imm
                0x57 -> regs[dst] = regs[dst] and imm
                0xa7 -> regs[dst] = regs[dst] xor imm
                0x0f -> regs[dst] += regs[src]
                0x1f -> regs[dst] -= regs[src]
                0x4f -> regs[dst] = regs[dst] or regs[src]
                0x5f -> regs[dst] = regs[dst] and regs[src]
                0xaf -> regs[dst] = regs[dst] xor regs[src]
                0x15 -> if (regs[dst] == imm) { pc += 1 + off; continue }
                0x55 -> if (regs[dst] != imm) { pc += 1 + off; continue }
                0x25 -> if (compareUnsigned(regs[dst], imm) > 0) { pc += 1 + off; continue }
                0x35 -> if (compareUnsigned(regs[dst], imm) >= 0) { pc += 1 + off; continue }
                0x1d -> if (regs[dst] == regs[src]) { pc += 1 + off; continue }
                0x5d -> if (regs[dst] != regs[src]) { pc += 1 + off; continue }
                0x2d -> if (compareUnsigned(regs[dst], regs[src]) > 0) { pc += 1 + off; continue }
                0x3d -> if (compareUnsigned(regs[dst], regs[src]) >= 0) { pc += 1 + off; continue }
                0x61 -> regs[dst] = loadWord(src, off, stack, context) and 0xffffffffL
                0x79 -> regs[dst] = loadLong(src, off, stack, context)
                0x7b -> storeLong(off, regs[src], stack)
                else -> throw IllegalStateException("verified uring eBPF opcode missing executor: 0x${op.toString(16)}")
            }
            pc++
        }
        throw IllegalStateException("uring eBPF program fell through without exit")
    }

    private fun loadWord(src: Int, offset: Int, stack: ByteArray, context: UringEbpfContext): Long =
        if (src == UringEbpfInsn.R1) contextValue(offset, context)
        else loadStack(offset, 4, stack)

    private fun loadLong(src: Int, offset: Int, stack: ByteArray, context: UringEbpfContext): Long =
        if (src == UringEbpfInsn.R1) contextValue(offset, context)
        else loadStack(offset, 8, stack)

    private fun contextValue(offset: Int, context: UringEbpfContext): Long =
        when (offset) {
            UringEbpfContextLayout.OPCODE -> context.submission.opcode.code.toLong()
            UringEbpfContextLayout.FD -> context.submission.fd.toLong()
            UringEbpfContextLayout.LEN -> context.submission.len.toLong()
            UringEbpfContextLayout.OFFSET -> context.submission.offset
            UringEbpfContextLayout.FLAGS -> context.submission.flags.toLong()
            UringEbpfContextLayout.USER_DATA -> context.submission.userData
            UringEbpfContextLayout.PHASE -> context.phase.code
            UringEbpfContextLayout.COMPLETION_RES -> context.completion?.res?.toLong() ?: 0L
            UringEbpfContextLayout.COMPLETION_FLAGS -> context.completion?.flags?.toLong() ?: 0L
            else -> 0L
        }

    private fun loadStack(offset: Int, width: Int, stack: ByteArray): Long {
        var value = 0L
        val begin = 512 + offset
        for (i in 0 until width) {
            value = value or ((stack[begin + i].toLong() and 0xffL) shl (i * 8))
        }
        return value
    }

    private fun storeLong(offset: Int, value: Long, stack: ByteArray) {
        val begin = 512 + offset
        for (i in 0 until 8) {
            stack[begin + i] = ((value ushr (i * 8)) and 0xffL).toByte()
        }
    }
}

private fun opcode(raw: Long): Int = (raw and 0xffL).toInt()
private fun dst(raw: Long): Int = ((raw ushr 8) and 0x0fL).toInt()
private fun src(raw: Long): Int = ((raw ushr 12) and 0x0fL).toInt()
private fun offset(raw: Long): Int = ((raw ushr 16) and 0xffffL).toShort().toInt()
private fun imm(raw: Long): Int = (raw ushr 32).toInt()
private fun compareUnsigned(left: Long, right: Long): Int =
    (left xor Long.MIN_VALUE).compareTo(right xor Long.MIN_VALUE)
