package borg.trikeshed.userspace.nio.ebpf.engine

import borg.trikeshed.userspace.nio.ebpf.types.*

/** Userspace eBPF → ARM64 (AArch64) native code JIT compiler. */
object Arm64Jit {
    private val ARM64_X = intArrayOf(0, 1, 2, 3, 4, 5, 19, 21, 22, 23, 24, 31)

    fun compile(program: EbpfProgram): JitCode {
        val b = ByteBuf()
        prologue(b)
        for (i in program.instructions.indices) {
            emitOne(EbpfInstruction(program.instructions[i]), b)
        }
        epilogue(b)
        return b.data()
    }

    private fun prologue(b: ByteBuf) {
        b.pushWord(0xa9bf7bfd.toInt())
        b.pushWord(0xd18203ff.toInt())
        b.pushWord(0xa9017b93.toInt())
        b.pushWord(0xa9027b95.toInt())
    }

    private fun epilogue(b: ByteBuf) {
        b.pushWord(0xa9417b93.toInt())
        b.pushWord(0xa9427b95.toInt())
        b.pushWord(0x912003ff.toInt())
        b.pushWord(0xa8c17bfd.toInt())
        b.pushWord(0xd65f03c0.toInt())
    }

    private fun emitOne(inst: EbpfInstruction, b: ByteBuf) {
        val op = inst.opcode()
        val cls = op and 0x07
        when {
            op == 0x95 -> b.pushWord(0x2a1f03e0)
            op == 0x85 -> b.pushWord(0xd503201f.toInt())
            cls == 0x07 || cls == 0x04 -> {
                val opBase = op and 0xF0
                val dr = inst.dstReg(); val sr = inst.srcReg()
                val srcIsImm = (op and 0x08) != 0
                val base = when (opBase) {
                    0x00 -> 0x0b000000
                    0x10 -> 0x4b000000
                    0x20 -> 0x1b000000
                    0x40 -> 0x0a000000
                    0xa0 -> 0x2a000000
                    0xb0 -> 0xaa000000.toInt()
                    else -> 0x2a000000
                }
                if (srcIsImm) {
                    armRI(b, 0x11000000, dr, inst.imm())
                } else {
                    armRR(b, base, dr, sr)
                }
            }
            op == 0xb7 -> mov64(b, ARM64_X[inst.dstReg()], inst.imm().toLong())
            op == 0x18 -> mov64(b, ARM64_X[inst.dstReg()], inst.imm().toLong() and 0xFFFFFFFF)
            cls == 0x05 -> b.pushWord(0x14000000)
            else -> b.pushWord(0xd503201f.toInt())
        }
    }

    private fun armRR(b: ByteBuf, base: Int, dst: Int, src: Int) {
        val d = ARM64_X[dst]; val s = ARM64_X[src]
        b.pushWord(base or s or (d shl 16) or (d shl 24))
    }

    private fun armRI(b: ByteBuf, base: Int, ebpfIdx: Int, imm: Int) {
        val rd = ARM64_X[ebpfIdx]; val i = imm and 0xfff
        b.pushWord(base or rd or (rd shl 8) or (i shl 10))
    }

    private fun mov64(b: ByteBuf, xReg: Int, imm64: Long) {
        for (shr in listOf(0, 16, 32, 48)) {
            val chunk = ((imm64 shr shr) and 0xFFFF).toInt()
            if (chunk != 0) {
                val op = if (shr == 0) 0xd2800000.toInt() else 0xf2800000.toInt()
                b.pushWord(op or xReg or (chunk shl 5) or ((shr / 16) shl 21))
            }
        }
    }
}
