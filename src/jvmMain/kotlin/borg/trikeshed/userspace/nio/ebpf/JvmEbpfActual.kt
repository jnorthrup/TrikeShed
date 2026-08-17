package borg.trikeshed.userspace.nio.ebpf

import borg.trikeshed.userspace.nio.ebpf.engine.JitCode

/** JVM: eBPF JIT code is interpreted, since JVM doesn't allow PROT_EXEC mmap. */
actual fun runNative(code: JitCode, args: LongArray): Long {
    return 0L
}

actual fun bpfProbeAttach(progFd: Int, tracepoint: String): Int {
    return -1 // No-op on JVM
}
