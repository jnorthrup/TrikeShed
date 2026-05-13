package nio.ebpf.engine

import nio.ebpf.types.*

/** Userspace eBPF → ARM64 (AArch64) native code JIT compiler. */
object Arm64Jit {
    private val ARM64_X = intArrayOf(0, 1, 2, 3, 4, 5, 19, 21, 22, 23, 24, 31)
    private fun wi(v: Long): Int = v.toInt()

    fun compile(program: EbpfProgram): JitCode {
        val b = ByteBuf()
        prologue(b)
        for (inst in program.instructions) emitOne(inst, b)
        epilogue(b)
        return b.data()
    }

    private fun prologue(b: ByteBuf) {
        b.pushWord(wi(0xa9bf7bfdL))    // stp fp, lr, [sp, #-16]!
        b.pushWord(wi(0xd18203ffL))    // sub sp, sp, #512
        b.pushWord(wi(0xa9017b93L))    // stp x19, x20, [sp, #16]
        b.pushWord(wi(0xa9027b95L))    // stp x21, x22, [sp, #32]
    }

    private fun epilogue(b: ByteBuf) {
        b.pushWord(wi(0xa9417b93L))
        b.pushWord(wi(0xa9427b95L))
        b.pushWord(wi(0x912003ffL))
        b.pushWord(wi(0xa8c17bfdL))
        b.pushWord(wi(0xd65f03c0L))
    }

    private fun emitOne(inst: EbpfInstruction, b: ByteBuf) {
        when (inst) {
            is EbpfInstruction.Move -> armRR(b, wi(0xaa000000L), inst)
            is EbpfInstruction.Add -> armRR(b, wi(0x0b000000L), inst)
            is EbpfInstruction.Sub -> armRR(b, wi(0x4b000000L), inst)
            is EbpfInstruction.And -> armRR(b, wi(0x8a000000L), inst)
            is EbpfInstruction.Or -> armRR(b, wi(0xaa000000L), inst)
            is EbpfInstruction.Xor -> armRR(b, wi(0xca000000L), inst)
            is EbpfInstruction.MovImm -> mov64(b, ARM64_X[inst.dst.index], inst.immVal!!.toLong())
            is EbpfInstruction.AddImm -> armRI(b, wi(0x91000000L), inst.dst.index, inst.immVal!!)
            is EbpfInstruction.SubImm -> armRI(b, wi(0xd1000000L), inst.dst.index, inst.immVal!!)
            is EbpfInstruction.LdX -> {
                val rt = ARM64_X[inst.dst.index]; val rn = ARM64_X[inst.src.index]
                val off = (inst.offset.toInt() / inst.size.bytes) and 0xfff
                ldr64(b, rt, rn, off)
            }
            is EbpfInstruction.StX -> {
                val rt = ARM64_X[inst.src.index]; val rn = ARM64_X[inst.dst.index]
                val off = (inst.offset.toInt() / inst.size.bytes) and 0xfff
                str64(b, rt, rn, off)
            }
            is EbpfInstruction.LdImm64 -> mov64(b, ARM64_X[inst.dst.index], inst.imm64)
            is EbpfInstruction.JmpExit -> b.pushWord(wi(0x2a1f03e0L))
            else -> b.pushWord(wi(0xd503201fL))
        }
    }

    private fun armRR(b: ByteBuf, base: Int, inst: EbpfInstruction) {
        val dst = ARM64_X[inst.dst?.index ?: 0]; val src = ARM64_X[inst.src?.index ?: 0]
        b.pushWord(base or src or (dst shl 16) or (dst shl 24))
    }

    private fun armRI(b: ByteBuf, base: Int, ebpfIdx: Int, imm: Int) {
        val rd = ARM64_X[ebpfIdx]; val i = imm and 0xfff
        b.pushWord(base or rd or (rd shl 8) or (i shl 10))
    }

    private fun mov64(b: ByteBuf, xReg: Int, imm64: Long) {
        for (shr in listOf(0, 16, 32, 48)) {
            val chunk = ((imm64 shr shr) and 0xFFFF).toInt()
            if (chunk != 0) {
                val op = if (shr == 0) wi(0xd2800000L) else wi(0xf2800000L)
                b.pushWord(op or xReg or (chunk shl 5) or ((shr / 16) shl 21))
            }
        }
    }

    private fun ldr64(b: ByteBuf, rt: Int, rn: Int, off: Int) {
        b.pushWord(wi(0xf9400000L) or rt or (rn shl 5) or (off shl 10))
    }

    private fun str64(b: ByteBuf, rt: Int, rn: Int, off: Int) {
        b.pushWord(wi(0xf9000000L) or rt or (rn shl 5) or (off shl 10))
    }
}
