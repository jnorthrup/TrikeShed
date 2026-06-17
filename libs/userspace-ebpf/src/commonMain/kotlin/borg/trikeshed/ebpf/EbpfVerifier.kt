package borg.trikeshed.ebpf

class EbpfVerifierError(message: String) : Exception(message)

/**
 * Basic eBPF Verifier that ensures execution safety.
 * Validates control flow graph (no backward edges/cycles) and basic instruction constraints.
 */
class EbpfVerifier(val program: EbpfProgram) {

    private val maxInstructions = 4096 // Same initial limit as older kernels

    fun verify() {
        if (program.instructions.isEmpty()) {
            throw EbpfVerifierError("Program is empty")
        }
        if (program.instructions.size > maxInstructions) {
            throw EbpfVerifierError("Program exceeds maximum instruction count ($maxInstructions)")
        }

        verifyControlFlowGraph()
        verifyInstructions()
    }

    private fun verifyControlFlowGraph() {
        val n = program.instructions.size
        val visited = BooleanArray(n)
        val stack = BooleanArray(n)

        // CFG roots: 0 and any targets of unvisited code.
        // eBPF only starts at 0.
        if (hasCycle(0, visited, stack)) {
            throw EbpfVerifierError("Program contains backward edge or infinite loop")
        }

        // Ensure all instructions end in an EXIT or valid state
        // If there's dead code, older eBPF verifiers might complain. We just ensure no loops.
    }

    private fun hasCycle(v: Int, visited: BooleanArray, stack: BooleanArray): Boolean {
        if (v < 0 || v >= program.instructions.size) {
            // Out of bounds jump is an error, but checked separately in verifyInstructions
            return false
        }
        if (stack[v]) return true // Cycle detected
        if (visited[v]) return false // Already checked this path

        visited[v] = true
        stack[v] = true

        val inst = EbpfInstruction(program.instructions[v])
        val classOp = inst.opcode and EbpfOpcode.BPF_CLASS_MASK
        val jmpOp = inst.opcode and 0xF0

        if (classOp == EbpfOpcode.BPF_JMP) {
            if (jmpOp == EbpfOpcode.BPF_EXIT) {
                // End of path
            } else if (jmpOp == EbpfOpcode.BPF_JA) {
                // Unconditional jump
                val target = v + 1 + inst.offset
                if (hasCycle(target, visited, stack)) return true
            } else if (jmpOp != EbpfOpcode.BPF_CALL) {
                // Conditional jump: branch 1 (fallthrough), branch 2 (target)
                val target = v + 1 + inst.offset
                if (hasCycle(v + 1, visited, stack)) return true
                if (hasCycle(target, visited, stack)) return true
            } else {
                // Call falls through
                if (hasCycle(v + 1, visited, stack)) return true
            }
        } else {
            // Fallthrough
            if (hasCycle(v + 1, visited, stack)) return true
        }

        stack[v] = false
        return false
    }

    private fun verifyInstructions() {
        for (i in program.instructions.indices) {
            val inst = EbpfInstruction(program.instructions[i])
            val opcode = inst.opcode
            val classOp = opcode and EbpfOpcode.BPF_CLASS_MASK

            // Basic register bound checks
            if (inst.dstReg < 0 || inst.dstReg > 10) throw EbpfVerifierError("Invalid dst register ${inst.dstReg} at pc $i")
            if (inst.srcReg < 0 || inst.srcReg > 10) throw EbpfVerifierError("Invalid src register ${inst.srcReg} at pc $i")

            if (classOp == EbpfOpcode.BPF_JMP) {
                val jmpOp = opcode and 0xF0
                if (jmpOp != EbpfOpcode.BPF_EXIT && jmpOp != EbpfOpcode.BPF_CALL) {
                    val target = i + 1 + inst.offset
                    if (target < 0 || target >= program.instructions.size) {
                        throw EbpfVerifierError("Jump out of bounds at pc $i: target $target, limit ${program.instructions.size}")
                    }
                }
            }
        }

        // Final instruction must be EXIT
        val lastInst = EbpfInstruction(program.instructions.last())
        val lastOp = lastInst.opcode
        if ((lastOp and EbpfOpcode.BPF_CLASS_MASK) != EbpfOpcode.BPF_JMP || (lastOp and 0xF0) != EbpfOpcode.BPF_EXIT) {
            // Not strictly true if there are multiple exit points and the last physical instruction is unreachable,
            // but typical for simple BPF structures.
            throw EbpfVerifierError("Final physical instruction is not EXIT")
        }
    }
}
