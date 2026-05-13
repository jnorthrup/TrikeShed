package nio.ebpf.codec

import nio.ebpf.types.*

/** Encoder: EbpfInstruction → wire bytes (native LE). */
object EbpfEncoder {
    fun encode(program: EbpfProgram): ByteArray {
        val result = ByteArray(program.instructions.size * 8)
        val buf = java.nio.ByteBuffer.wrap(result)
        buf.order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (i in program.instructions.indices) buf.putLong(i * 8, program.instructions[i])
        return result
    }

    fun encodeOne(inst: EbpfInstruction): ByteArray {
        val b = ByteArray(8)
        for (i in 0..7) b[i] = (inst.raw shr (i * 8) and 0xFF).toByte()
        return b
    }
}
