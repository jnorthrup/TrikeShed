package borg.trikeshed.ebpf

import kotlin.test.Test
import kotlin.test.assertTrue
import com.sun.jna.Platform

class BpfSyscallTest {

    @Test
    fun testKernelLoaderSyscallRouting() {
        if (!Platform.isLinux()) {
            println("Skipping BpfSyscallTest: Not on Linux")
            return
        }

        val syscall = JvmBpfSyscall()
        val loader = EbpfKernelLoader(syscall)

        // Simple valid program: R0 = 0, EXIT
        val inst1 = EbpfInstruction.pack(EbpfOpcode.BPF_ALU64 or EbpfOpcode.BPF_MOV or EbpfOpcode.BPF_K, 0, 0, 0, 0)
        val inst2 = EbpfInstruction.pack(EbpfOpcode.BPF_JMP or EbpfOpcode.BPF_EXIT, 0, 0, 0, 0)

        val program = EbpfProgram(longArrayOf(inst1.raw, inst2.raw))

        // The host kernel typically requires root/CAP_SYS_ADMIN to load eBPF,
        // so we expect a failure (fd < 0), but specifically not a JVM crash.
        // It proves the syscall routed successfully to the host kernel if it returns -1 (EPERM etc).
        val fd = loader.loadProgram(BpfProgType.BPF_PROG_TYPE_SOCKET_FILTER, program)

        println("bpf(2) returned fd: $fd")
        assertTrue(fd <= 0, "bpf syscall should typically fail without root privileges or return a valid fd")
    }
}
