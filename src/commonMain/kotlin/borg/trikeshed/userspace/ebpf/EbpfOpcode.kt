package borg.trikeshed.userspace.ebpf

object EbpfOpcode {
    const val BPF_CLASS_MASK = 0x07
    const val BPF_ALU = 0x04
    const val BPF_ALU64 = 0x07
    const val BPF_JMP = 0x05
    const val BPF_JMP32 = 0x06
    const val BPF_LD = 0x00
    const val BPF_LDX = 0x01
    const val BPF_ST = 0x02
    const val BPF_STX = 0x03

    // ALU operations
    const val BPF_ADD = 0x00
    const val BPF_SUB = 0x10
    const val BPF_MUL = 0x20
    const val BPF_DIV = 0x30
    const val BPF_OR = 0x40
    const val BPF_AND = 0x50
    const val BPF_LSH = 0x60
    const val BPF_RSH = 0x70
    const val BPF_NEG = 0x80
    const val BPF_MOD = 0x90
    const val BPF_XOR = 0xA0
    const val BPF_MOV = 0xB0
    const val BPF_ARSH = 0xC0
    const val BPF_END = 0xD0

    // Source operands
    const val BPF_K = 0x00
    const val BPF_X = 0x08

    // Jump operations
    const val BPF_JA = 0x00
    const val BPF_JEQ = 0x10
    const val BPF_JGT = 0x20
    const val BPF_JGE = 0x30
    const val BPF_JSET = 0x40
    const val BPF_JNE = 0x50
    const val BPF_JSGT = 0x60
    const val BPF_JSGE = 0x70
    const val BPF_CALL = 0x80
    const val BPF_EXIT = 0x90
    const val BPF_JLT = 0xA0
    const val BPF_JLE = 0xB0
    const val BPF_JSLT = 0xC0
    const val BPF_JSLE = 0xD0

    // Memory operations modifiers
    const val BPF_W = 0x00 // 32-bit
    const val BPF_H = 0x08 // 16-bit
    const val BPF_B = 0x10 // 8-bit
    const val BPF_DW = 0x18 // 64-bit

    const val BPF_IMM = 0x00
    const val BPF_ABS = 0x20
    const val BPF_IND = 0x40
    const val BPF_MEM = 0x60
    const val BPF_LEN = 0x80
    const val BPF_MSH = 0xa0
    const val BPF_ATOMIC = 0xc0
}
