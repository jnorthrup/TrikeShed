package nio.ebpf.codec

import nio.ebpf.types.*
import nio.ebpf.types.EbpfResult.Sealed

/** Decoder: wire bytes → EbpfInstruction raw Longs. */
fun decodeProgram(name: String, raw: ByteArray, programType: EbpfProgramType = EbpfProgramType.Unspec): Sealed {
    if (raw.size % 8 != 0) return Sealed.Error(EbpfError.VerificationFailed("bytecode length ${raw.size} not multiple of 8"))
    val count = raw.size / 8
    val buf = LongArray(count)
    for (i in 0 until count) {
        val off = i * 8
        buf[i] = (raw[off].toLong() and 0xFF) or
                 ((raw[off + 1].toLong() and 0xFF) shl 8) or
                 ((raw[off + 2].toLong() and 0xFF) shl 16) or
                 ((raw[off + 3].toLong() and 0xFF) shl 24) or
                 ((raw[off + 4].toLong() and 0xFF) shl 32) or
                 ((raw[off + 5].toLong() and 0xFF) shl 40) or
                 ((raw[off + 6].toLong() and 0xFF) shl 48) or
                 ((raw[off + 7].toLong() and 0xFF) shl 56)
    }
    return Sealed.Success(EbpfProgram(name, buf, programType))
}
