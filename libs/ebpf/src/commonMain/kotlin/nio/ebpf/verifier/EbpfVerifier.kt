package nio.ebpf.verifier

import nio.ebpf.algebra.EbpfInstruction
import nio.ebpf.algebra.EbpfProgram
import nio.ebpf.algebra.Reg
import nio.ebpf.algebra.BitWidth

sealed class RegType {
    object NotInitialized : RegType()
    object Scalar : RegType()
    object PtrFramePointer : RegType()
    object PtrStack : RegType()
    object PtrMapValue : RegType()
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
    val stackDepth: Int = 0,
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
    if (program.instructions.isEmpty()) {
        return VerifierResult.Failure(0, "empty program", emptyList())
    }
    if (program.size() > 0xFFFF) {
        return VerifierResult.Failure(0, "program too large: ${program.size()} (max ${0xFFFF})", emptyList())
    }

    val traces = mutableListOf<VerifierTrace>()
    val reachable = BooleanArray(program.size())

    // Phase 1: BFS reachability
    reachable[0] = true
    val queue = ArrayDeque<Int>().apply { add(0) }
    while (queue.isNotEmpty()) {
        val pc = queue.removeFirst()
        if (pc >= program.size()) continue
        val inst = program.instructions[pc]
        for (s in computeSuccessors(inst, pc, program.size())) {
            if (s in 0 until program.size() && !reachable[s]) {
                reachable[s] = true
                queue.add(s)
            }
        }
    }
    for (i in 0 until program.size()) {
        if (!reachable[i]) {
            return VerifierResult.Failure(i, "instruction at pc $i is unreachable", traces)
        }
    }

    // Phase 2: Register state propagation
    var state = VerifierState(
        registers = (0..15).map { i -> if (i in 1..5) RegState(RegType.PtrCtx) else RegState() },
        reachable = true,
    )

    for (pc in 0 until program.size()) {
        val inst = program.instructions[pc]
        val before = state.copy()
        val errors = checkInstruction(inst, state)
        if (errors.isNotEmpty()) {
            return VerifierResult.Failure(pc, errors.joinToString("; "), traces)
        }
        state = stepInstruction(inst, state)
        traces += VerifierTrace(pc, before, state)
    }

    val last = program.instructions.lastOrNull()
    if (last !is EbpfInstruction.JmpExit) {
        return VerifierResult.Failure(
            program.size() - 1,
            "program does not end with exit (ends with ${last?.let { it::class.simpleName }})",
            traces
        )
    }
    return VerifierResult.Success(traces)
}

private fun computeSuccessors(inst: EbpfInstruction, pc: Int, size: Int): List<Int> = when (inst) {
    is EbpfInstruction.Jmp -> listOf(pc + 1 + inst.offset)
    is EbpfInstruction.JmpExit -> emptyList()
    is EbpfInstruction.JmpCall -> listOf(pc + 1)
    is EbpfInstruction.JmpEq, is EbpfInstruction.JmpGt, is EbpfInstruction.JmpGe,
    is EbpfInstruction.JmpNe, is EbpfInstruction.JmpSgt, is EbpfInstruction.JmpSge,
    is EbpfInstruction.JmpSet, is EbpfInstruction.JmpEqImm, is EbpfInstruction.JmpNeImm,
    is EbpfInstruction.JmpSgtImm, is EbpfInstruction.JmpSgeImm,
    is EbpfInstruction.JgtImm, is EbpfInstruction.GeImm -> {
        val off = when (inst) {
            is EbpfInstruction.JmpEq -> inst.offset
            is EbpfInstruction.JmpGt -> inst.offset
            is EbpfInstruction.JmpGe -> inst.offset
            is EbpfInstruction.JmpNe -> inst.offset
            is EbpfInstruction.JmpSgt -> inst.offset
            is EbpfInstruction.JmpSge -> inst.offset
            is EbpfInstruction.JmpSet -> inst.offset
            is EbpfInstruction.JmpEqImm -> inst.offset
            is EbpfInstruction.JmpNeImm -> inst.offset
            is EbpfInstruction.JmpSgtImm -> inst.offset
            is EbpfInstruction.JmpSgeImm -> inst.offset
            is EbpfInstruction.JgtImm -> inst.offset
            is EbpfInstruction.GeImm -> inst.offset
            else -> 0
        }
        val fall = pc + 1
        val target = pc + 1 + off
        listOfNotNull(fall.takeIf { it < size }, target.takeIf { it < size })
    }
    else -> listOf(pc + 1)
}

private fun checkInstruction(inst: EbpfInstruction, state: VerifierState): List<String> {
    val errors = mutableListOf<String>()
    val regs = inst.regs()
    for (reg in regs) {
        val type = state.registers[reg.index].type
        if (type is RegType.NotInitialized) {
            errors += "register R${reg.index} used uninitialized at pc ${inst.pc}"
        }
    }
    val maybeDivisor = when (inst) {
        is EbpfInstruction.Div -> inst.src
        is EbpfInstruction.Mod -> inst.src
        else -> null
    }
    maybeDivisor?.let { src ->
        val srcType = state.registers[src.index].type
        if (srcType is RegType.Const && srcType.value == 0L) {
            errors += "division by const 0 at pc ${inst.pc}"
        }
    }
    val size = when (inst) {
        is EbpfInstruction.LdX -> inst.size
        is EbpfInstruction.St -> inst.size
        is EbpfInstruction.StX -> inst.size
        else -> null
    }
    size?.let { sw -> checkMemoryAccess(inst, state, sw, errors) }
    return errors
}

private fun checkMemoryAccess(inst: EbpfInstruction, state: VerifierState, sw: BitWidth, errors: MutableList<String>) {
    val offset = when (inst) {
        is EbpfInstruction.LdX -> inst.offset.toInt()
        is EbpfInstruction.St -> inst.offset.toInt()
        is EbpfInstruction.StX -> inst.offset.toInt()
        else -> inst.offsetVal ?: 0
    }
    if (inst is EbpfInstruction.LdX && inst.src == Reg.R10) {
        if (offset > 0) errors += "stack read above frame pointer at pc ${inst.pc}"
        if (-offset < sw.bytes) errors += "stack read extends past frame pointer"
        if (-offset > 512) errors += "stack read exceeds 512-byte stack"
    }
}

private fun stepInstruction(inst: EbpfInstruction, state: VerifierState): VerifierState {
    val regs = state.registers.toMutableList()
    val srcReg = inst.src
    val dstReg = inst.dst

    when (inst) {
        is EbpfInstruction.Move -> if (dstReg != null) regs[dstReg.index] = RegState(type = RegType.Scalar)
        is EbpfInstruction.Add, is EbpfInstruction.Sub, is EbpfInstruction.Mul,
        is EbpfInstruction.Or, is EbpfInstruction.And, is EbpfInstruction.LShift,
        is EbpfInstruction.RShift, is EbpfInstruction.Xor, is EbpfInstruction.Mod,
        is EbpfInstruction.ArithRShift, is EbpfInstruction.Div,
        is EbpfInstruction.LShiftImm, is EbpfInstruction.RShiftImm,
        is EbpfInstruction.ModImm, is EbpfInstruction.DivImm, is EbpfInstruction.MulImm,
        is EbpfInstruction.ArithRShiftImm -> if (dstReg != null) regs[dstReg.index] = RegState(type = RegType.Scalar)
        is EbpfInstruction.AddImm, is EbpfInstruction.SubImm -> {
            if (dstReg == null) return state.copy(registers = regs)
            val dstType = regs[dstReg.index].type
            val value = inst.immVal!!.toLong()
            regs[dstReg.index] = if (dstType is RegType.Const) {
                val result = if (inst is EbpfInstruction.AddImm) dstType.value + value else dstType.value - value
                RegState(RegType.Const(result))
            } else RegState(RegType.Scalar)
        }
        is EbpfInstruction.MovImm -> {
            if (dstReg == null) return state.copy(registers = regs)
            regs[dstReg.index] = RegState(RegType.Const(inst.immVal!!.toLong()))
        }
        is EbpfInstruction.AndImm, is EbpfInstruction.OrImm -> {
            if (dstReg == null) return state.copy(registers = regs)
            val dstType = regs[dstReg.index].type
            val immVal = inst.immVal!!.toLong()
            regs[dstReg.index] = if (dstType is RegType.Const) {
                val result = if (inst is EbpfInstruction.AndImm) dstType.value and immVal else dstType.value or immVal
                RegState(RegType.Const(result))
            } else RegState(RegType.Scalar)
        }
        is EbpfInstruction.XorImm -> if (dstReg != null) regs[dstReg.index] = RegState(RegType.Scalar)
        is EbpfInstruction.Neg -> {
            if (dstReg == null) return state.copy(registers = regs)
            val dstType = regs[dstReg.index].type
            regs[dstReg.index] = if (dstType is RegType.Const) RegState(RegType.Const(-dstType.value)) else RegState(RegType.Scalar)
        }
        is EbpfInstruction.JmpCall -> regs[0] = RegState(RegType.Scalar)
        is EbpfInstruction.LdImm64 -> {
            if (dstReg == null) return state.copy(registers = regs)
            regs[dstReg.index] = RegState(RegType.Const(inst.imm64))
        }
        is EbpfInstruction.LdX -> if (dstReg != null) regs[dstReg.index] = RegState(RegType.Scalar)
        is EbpfInstruction.Endian -> Unit
        else -> Unit
    }
    return state.copy(registers = regs)
}
