package nio.ebpf.algebra

/**
 * eBPF bytecode binary encoder. Produces the wire-format 8-byte encoding
 * from the algebraic instruction types.
 */
object EbpfEncoder {
    fun encode(program: EbpfProgram): ByteArray {
        val buf = mutableListOf<Byte>()
        for (inst in program.instructions) {
            buf += encodeOne(inst)
        }
        return buf.toByteArray()
    }

    private fun encodeOne(inst: EbpfInstruction): List<Byte> = when (inst) {
        is EbpfInstruction.MovImm -> encode8(0xb7 or inst.dst.index, 0, 0, inst.imm)
        is EbpfInstruction.Add -> encode8(0x0f or inst.dst.index or (inst.src.index shl 4), 0, 0, 0)
        is EbpfInstruction.Sub -> encode8(0x1f or inst.dst.index or (inst.src.index shl 4), 0, 0, 0)
        is EbpfInstruction.Mul -> encode8(0x2f or inst.dst.index or (inst.src.index shl 4), 0, 0, 0)
        is EbpfInstruction.Div -> encode8(0x3f or inst.dst.index or (inst.src.index shl 4), 0, 0, 0)
        is EbpfInstruction.Or -> encode8(0xaf or inst.dst.index or (inst.src.index shl 4), 0, 0, 0)
        is EbpfInstruction.And -> encode8(0x4f or inst.dst.index or (inst.src.index shl 4), 0, 0, 0)
        is EbpfInstruction.LShift -> encode8(0x5f or inst.dst.index or (inst.src.index shl 4), 0, 0, 0)
        is EbpfInstruction.RShift -> encode8(0x6f or inst.dst.index or (inst.src.index shl 4), 0, 0, 0)
        is EbpfInstruction.Xor -> encode8(0x8f or inst.dst.index or (inst.src.index shl 4), 0, 0, 0)
        is EbpfInstruction.Mod -> encode8(0x9f or inst.dst.index or (inst.src.index shl 4), 0, 0, 0)
        is EbpfInstruction.Move -> encode8(0xbf or inst.dst.index or (inst.src.index shl 4), 0, 0, 0)
        is EbpfInstruction.Neg -> encode8(0x84 or inst.dst.index, 0, 0, 0)
        // Loads
        is EbpfInstruction.LdX -> encode8(0x61 or inst.dst.index or (inst.src.index shl 4), inst.offset.toInt(), 0, 0)
        // Stores
        is EbpfInstruction.StX -> encode8(0x73 or inst.dst.index or (inst.src.index shl 4), inst.offset.toInt(), 0, 0)
        // Jumps
        is EbpfInstruction.Jmp -> encode8(0x05, inst.offset, 0, 0)
        is EbpfInstruction.JmpExit -> encode8(0x95, 0, 0, 0)
        is EbpfInstruction.JmpCall -> encode8(0x85, 0, 0, inst.helper.id)
        is EbpfInstruction.LdImm64 -> {
            val lo = (inst.imm64 and 0xFFFFFFFFL).toInt()
            val hi = ((inst.imm64 shr 32) and 0xFFFFFFFFL).toInt()
            encode8(0x18 or inst.dst.index, 0, 0, lo) + encode8(0, 0, 0, hi)
        }
        else -> encode8(0xb7 or (inst.dst?.index ?: 0), 0, 0, 0)
    }

    private fun encode8(opcode: Int, offset: Int, junk: Int, imm: Int): List<Byte> = listOf(
        opcode.toByte(),
        (0).toByte(),
        offset.toByte(),
        (offset shr 8).toByte(),
        imm.toByte(),
        (imm shr 8).toByte(),
        (imm shr 16).toByte(),
        (imm shr 24).toByte(),
    )
}
