package borg.trikeshed.userspace.nio.ebpf.verifier

import borg.trikeshed.userspace.nio.ebpf.types.*

sealed class RegType {
    object NotInitialized : RegType()
    object Scalar : RegType()
    object PtrCtx : RegType()
    data class Const(val value: Long) : RegType()
}

data class RegState(
    val type: RegType = RegType.NotInitialized,
    val maxValue: Long = Long.MAX_VALUE,
    val minValue: Long = Long.MIN_VALUE,
)

data class VerifierState(
    val registers: List<RegState> = (0..15).map { RegState() },
    val reachable: Boolean = false,
)

data class VerifierTrace(
    val pc: Int,
    val stateBefore: VerifierState,
    val stateAfter: VerifierState,
    val errors: List<String> = emptyList(),
)

sealed interface VerifierResult {
    data class Success(val traces: List<VerifierTrace>) : VerifierResult
    data class Failure(val pc: Int, val reason: String, val traces: List<VerifierTrace>) : VerifierResult
}

fun verifyProgram(program: EbpfProgram): VerifierResult {
    if (program.size() == 0) return VerifierResult.Failure(0, "empty program", emptyList())
    if (program.size() > 0xFFFF) return VerifierResult.Failure(0, "program too large", emptyList())

    val traces = mutableListOf<VerifierTrace>()
    val reachable = BooleanArray(program.size())
    reachable[0] = true
    val queue = ArrayDeque<Int>().apply { add(0) }
    while (queue.isNotEmpty()) {
        val pc = queue.removeFirst()
        if (pc >= program.size()) continue
        val raw = program.instructions[pc]
        val inst = EbpfInstruction(raw)
        val op = inst.opcode()
        for (s in successors(pc, op, inst, program.size())) {
            if (s in 0 until program.size() && !reachable[s]) { reachable[s] = true; queue.add(s) }
        }
    }
    for (i in 0 until program.size())
        if (!reachable[i]) return VerifierResult.Failure(i, "unreachable instr at pc $i", traces)

    var state = VerifierState(
        registers = (0..15).map { i -> if (i in 1..5) RegState(RegType.PtrCtx) else RegState() },
        reachable = true,
    )
    for (pc in 0 until program.size()) {
        val inst = EbpfInstruction(program.instructions[pc])
        val before = state.copy()
        val errors = checkInst(inst, state)
        if (errors.isNotEmpty()) return VerifierResult.Failure(pc, errors.joinToString("; "), traces)
        state = stepInst(inst, state)
        traces += VerifierTrace(pc, before, state)
    }

    val last = EbpfInstruction(program.instructions[program.size() - 1])
    if (!last.isExit()) return VerifierResult.Failure(program.size() - 1, "no exit at end", traces)
    return VerifierResult.Success(traces)
}

private fun successors(pc: Int, opcode: Int, inst: EbpfInstruction, size: Int): List<Int> {
    val cls = opcode and 0x07
    return when {
        opcode == 0x95 -> emptyList()
        opcode == 0x85 -> listOf(pc + 1)
        cls == 0x05 -> listOf(pc + 1 + inst.offset())
        cls == 0x06 || cls == 0x07 -> {
            val off = inst.offset(); val fall = pc + 1; val target = pc + 1 + off
            listOfNotNull(fall.takeIf { it < size }, target.takeIf { it < size })
        }
        else -> listOf(pc + 1)
    }
}

private fun checkInst(inst: EbpfInstruction, state: VerifierState): List<String> {
    val errors = mutableListOf<String>()
    val dr = inst.dstReg(); val sr = inst.srcReg()
    if (state.registers[dr].type is RegType.NotInitialized) errors += "R$dr used uninitialized"
    if (sr != 0 && state.registers[sr].type is RegType.NotInitialized) errors += "R$sr used uninitialized"
    // Div-by-zero for reg source
    if ((inst.opcode() and 0x07) == 0x04 && (inst.opcode() and 0xe0) == 0x00) {
        val srcType = state.registers[sr].type
        if (srcType is RegType.Const && srcType.value == 0L) errors += "div by const 0"
    }
    return errors
}

private const val BPF_ALU: Int = 0x04
private const val BPF_ALU64: Int = 0x07
private const val BPF_JMP: Int = 0x05
private const val BPF_JMP32: Int = 0x06
private const val BPF_LD: Int = 0x60
private const val BPF_ST: Int = 0x62
private const val BPF_STX: Int = 0x63
private const val BPF_K: Int = 0x00
private const val BPF_SRC: Int = 0x08
private const val BPF_ADD = 0x00
private const val BPF_SUB = 0x10
private const val BPF_MUL = 0x20
private const val BPF_DIV = 0x30
private const val BPF_OR = 0x40
private const val BPF_AND = 0x50
private const val BPF_LSH = 0x60
private const val BPF_RSH = 0x70
private const val BPF_NEG = 0x80
private const val BPF_MOD = 0x90
private const val BPF_XOR = 0xa0
private const val BPF_MOV = 0xb0
private const val BPF_ARSH = 0xc0
private const val BPF_END = 0xd0
private const val BPF_JA = 0x00
private const val BPF_JEQ = 0x10
private const val BPF_JGT = 0x20
private const val BPF_JGE = 0x30
private const val BPF_JNE = 0x50
private const val BPF_JSGT = 0x60
private const val BPF_JSGE = 0x70
private const val BPF_CALL = 0x80
private const val BPF_EXIT: Int = 0x95
private const val BPF_CLASS: Int = 0x07
private const val BPF_MODE: Int = 0xe0

private fun stepInst(inst: EbpfInstruction, state: VerifierState): VerifierState {
    val regs = state.registers.toMutableList()
    val op = inst.opcode() and 0xFF
    val cls = op and BPF_CLASS
    val dr = inst.dstReg(); val sr = inst.srcReg()
    val srcType = state.registers[sr].type
    val dstType = state.registers[dr].type

    if (cls == BPF_ALU64 || cls == BPF_ALU) {
        val srcIsImm = (op and BPF_SRC) != 0x00
        val opBase = op and 0xF0
        if (srcIsImm) {
            // Immediate ALU
            val imm = inst.imm().toLong()
            val result: RegState = when (opBase) {
                BPF_MOV -> RegState(RegType.Const(imm))
                BPF_ADD -> regOp(dstType) { a -> if (a is RegType.Const) RegType.Const(a.value + imm) else RegType.Scalar }
                BPF_SUB -> regOp(dstType) { a -> if (a is RegType.Const) RegType.Const(a.value - imm) else RegType.Scalar }
                BPF_AND -> regOp(dstType) { a -> if (a is RegType.Const) RegType.Const(a.value and imm) else RegType.Scalar }
                BPF_OR  -> regOp(dstType) { a -> if (a is RegType.Const) RegType.Const(a.value or imm) else RegType.Scalar }
                BPF_XOR -> regOp(dstType) { a -> if (a is RegType.Const) RegType.Const(a.value xor imm) else RegType.Scalar }
                BPF_NEG -> regOp(dstType) { a -> if (a is RegType.Const) RegType.Const(-a.value) else RegType.Scalar }
                else    -> RegState(RegType.Scalar)
            }
            regs[dr] = result
        } else {
            // Register ALU
            val result: RegState = when (opBase) {
                BPF_MOV -> RegState(srcType)
                BPF_ADD, BPF_SUB, BPF_MUL, BPF_DIV, BPF_MOD -> regBinOp(dstType, srcType) { a, b ->
                    if (a is RegType.Const && b is RegType.Const) {
                        RegType.Const(when (opBase) {
                            BPF_ADD -> a.value + b.value; BPF_SUB -> a.value - b.value
                            BPF_MUL -> a.value * b.value; BPF_DIV -> a.value / b.value
                            BPF_MOD -> a.value % b.value; else -> 0L
                        })
                    } else RegType.Scalar
                }
                BPF_AND, BPF_OR, BPF_XOR -> regBinOp(dstType, srcType) { a, b ->
                    if (a is RegType.Const && b is RegType.Const) {
                        RegType.Const(when (opBase) {
                            BPF_AND -> a.value and b.value; BPF_OR -> a.value or b.value
                            BPF_XOR -> a.value xor b.value; else -> 0L
                        })
                    } else RegType.Scalar
                }
                BPF_NEG -> regOp(dstType) { a -> if (a is RegType.Const) RegType.Const(-a.value) else RegType.Scalar }
                else -> RegState(RegType.Scalar)
            }
            regs[dr] = result
        }
    }
    if (cls == BPF_LD) {
        regs[dr] = RegState(RegType.Scalar)
    }
    if (op == 0x85) {
        // Call — return value in R0
        regs[0] = RegState(RegType.Scalar)
    }

    return state.copy(registers = regs)
}

private inline fun regOp(a: RegType, crossinline fn: (RegType) -> RegType): RegState =
    if (a is RegType.Const || a !is RegType.NotInitialized) RegState(fn(a)) else RegState(RegType.Scalar)

private inline fun regBinOp(a: RegType, b: RegType, crossinline fn: (RegType, RegType) -> RegType): RegState =
    if (a !is RegType.NotInitialized && b !is RegType.NotInitialized) RegState(fn(a, b)) else RegState(RegType.Scalar)
