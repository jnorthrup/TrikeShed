package borg.trikeshed.userspace.ebpf

/**
 * eBPF Program Types (matches Linux bpf_prog_type)
 */
object BpfProgType {
    const val BPF_PROG_TYPE_UNSPEC = 0
    const val BPF_PROG_TYPE_SOCKET_FILTER = 1
    const val BPF_PROG_TYPE_KPROBE = 2
    const val BPF_PROG_TYPE_SCHED_CLS = 3
    const val BPF_PROG_TYPE_SCHED_ACT = 4
    const val BPF_PROG_TYPE_TRACEPOINT = 5
    const val BPF_PROG_TYPE_XDP = 6
}

/**
 * eBPF Commands (matches Linux bpf_cmd)
 */
object BpfCmd {
    const val BPF_MAP_CREATE = 0
    const val BPF_MAP_LOOKUP_ELEM = 1
    const val BPF_MAP_UPDATE_ELEM = 2
    const val BPF_MAP_DELETE_ELEM = 3
    const val BPF_MAP_GET_NEXT_KEY = 4
    const val BPF_PROG_LOAD = 5
}

/**
 * Interface to interact directly with the Linux bpf(2) syscall.
 * Abstracted to allow standardizing multiplatform boundaries and test mocks.
 */
interface BpfSyscall {
    /**
     * Executes the bpf(2) syscall.
     * @param cmd The bpf command (e.g. BPF_PROG_LOAD)
     * @param attr A byte array holding the serialized bpf_attr C-union.
     * @param size The size of the bpf_attr being passed.
     * @return The file descriptor (or 0) on success, or -1 on error.
     */
    fun bpf(cmd: Int, attr: ByteArray, size: Int): Int
}

class EbpfKernelLoader(private val syscall: BpfSyscall) {

    /**
     * Loads an EbpfProgram into the host kernel.
     * Translates EbpfProgram instructions into the bpf_attr union.
     */
    fun loadProgram(progType: Int, program: EbpfProgram, license: String = "GPL"): Int {
        // bpf_attr structure for BPF_PROG_LOAD (simplified offset approximation):
        // __u32         prog_type;      /* 0-3 */
        // __u32         insn_cnt;       /* 4-7 */
        // __aligned_u64 insns;          /* 8-15 */
        // __aligned_u64 license;        /* 16-23 */
        // __u32         log_level;      /* 24-27 */
        // __u32         log_size;       /* 28-31 */
        // __aligned_u64 log_buf;        /* 32-39 */

        // This size typically needs to match the exact kernel version struct size.
        // We'll use a conservative 48 bytes which covers the basics for loading.
        val attr = ByteArray(48)

        // prog_type (Little-Endian)
        attr[0] = (progType and 0xFF).toByte()
        attr[1] = ((progType ushr 8) and 0xFF).toByte()
        attr[2] = ((progType ushr 16) and 0xFF).toByte()
        attr[3] = ((progType ushr 24) and 0xFF).toByte()

        val insnCnt = program.instructions.size
        // insn_cnt (Little-Endian)
        attr[4] = (insnCnt and 0xFF).toByte()
        attr[5] = ((insnCnt ushr 8) and 0xFF).toByte()
        attr[6] = ((insnCnt ushr 16) and 0xFF).toByte()
        attr[7] = ((insnCnt ushr 24) and 0xFF).toByte()

        // In a real JNA/cinterop implementation, pointers (insns, license, log_buf)
        // must be extracted from native memory boundaries.
        // Since Kotlin common code cannot fetch raw native pointers directly,
        // we defer pointer injection to the Jvm/Native boundary via the implementation of `BpfSyscall`.
        // The `bpf()` call here expects the implementation to inspect the program context
        // and inject pointers if we pass specific signals or pass the program out of band.

        // For standardizing without platform code leakage, we pass the serialized instructions
        // appended or structured such that `BpfSyscall` can build the native pointers.

        // As a pure architectural step, we serialize the instructions into a payload.
        val payloadSize = 48 + (insnCnt * 8) + license.length + 1
        val payload = ByteArray(payloadSize)
        attr.copyInto(payload)

        var offset = 48
        for (inst in program.instructions) {
            payload[offset++] = (inst and 0xFF).toByte()
            payload[offset++] = ((inst ushr 8) and 0xFF).toByte()
            payload[offset++] = ((inst ushr 16) and 0xFF).toByte()
            payload[offset++] = ((inst ushr 24) and 0xFF).toByte()
            payload[offset++] = ((inst ushr 32) and 0xFF).toByte()
            payload[offset++] = ((inst ushr 40) and 0xFF).toByte()
            payload[offset++] = ((inst ushr 48) and 0xFF).toByte()
            payload[offset++] = ((inst ushr 56) and 0xFF).toByte()
        }

        for (char in license) {
            payload[offset++] = char.code.toByte()
        }
        payload[offset] = 0 // null terminator

        return syscall.bpf(BpfCmd.BPF_PROG_LOAD, payload, payloadSize)
    }
}
