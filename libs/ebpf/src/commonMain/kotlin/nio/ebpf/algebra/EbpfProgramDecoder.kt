package nio.ebpf.algebra

import nio.ebpf.algebra.EbpfResult.Sealed as SealedEbpfResult

/**
 * Raw eBPF bytecode decoder.
 */
fun decodeProgram(name: String, raw: ByteArray, programType: EbpfProgramType = EbpfProgramType.Unspec): SealedEbpfResult {
    if (raw.size % 8 != 0) {
        return SealedEbpfResult.Error(EbpfError.VerificationFailed("raw bytecode length ${raw.size} not multiple of 8"))
    }
    val insts = mutableListOf<EbpfInstruction>()
    var pc = 0
    var i = 0
    while (i < raw.size) {
        val decoded = decodeInstruction(raw, i, pc)
        insts += decoded.first
        val step = decoded.second
        i += step
        pc++
        if (decoded.first is EbpfInstruction.LdImm64) {
            pc++
        }
    }
    return SealedEbpfResult.Success(EbpfProgram(name, insts, programType))
}

private fun decodeInstruction(raw: ByteArray, offset: Int, pc: Int): Pair<EbpfInstruction, Int> {
    val opCode = raw[offset].toUByte().toInt()
    val dst = raw[offset + 1].toUByte().toInt() and 0x0F
    val src = (raw[offset + 1].toUByte().toInt() shr 4) and 0x0F
    val off = (raw[offset + 2].toUByte().toInt() or (raw[offset + 3].toUByte().toInt() shl 8)).toShort()
    val imm = raw[offset + 4].toInt() or
        (raw[offset + 5].toInt() shl 8) or
        (raw[offset + 6].toInt() shl 16) or
        (raw[offset + 7].toInt() shl 24)

    return when (opCode) {
        0xb7 -> EbpfInstruction.MovImm(pc, imm, Reg(dst)) to 8
        0x07, 0x0f -> EbpfInstruction.Add(pc, Reg(src), Reg(dst)) to 8
        0x95 -> EbpfInstruction.JmpExit(pc) to 8
        0x9d -> EbpfInstruction.JmpExit(pc) to 8
        0x05 -> EbpfInstruction.Jmp(pc, imm) to 8
        0x18 -> {
            val hi = raw[offset + 8].toInt() or
                (raw[offset + 9].toInt() shl 8) or
                (raw[offset + 10].toInt() shl 16) or
                (raw[offset + 11].toInt() shl 24)
            val imm64 = (imm.toLong() and 0xFFFFFFFFL) or (hi.toLong() shl 32)
            EbpfInstruction.LdImm64(pc, imm64, Reg(dst)) to 16
        }
        0x61 -> EbpfInstruction.LdX(pc, Reg(src), Reg(dst), off, BitWidth.B32) to 8
        0x85 -> EbpfInstruction.JmpCall(pc, EbpfHelper.Unspec) to 8
        else -> EbpfInstruction.MovImm(pc, imm, Reg(dst)) to 8
    }
}

private val EBPF_HELPER_CALLS = mapOf<Int, EbpfHelper>()
