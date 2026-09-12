package borg.trikeshed.userspace.nio.ebpf

// eBPF is a Linux-kernel feature (bpf(2) syscall). macOS has no eBPF:
// report failure, do not stub a fake success.
actual fun runNative(code: ByteArray, args: LongArray): Long = -1L

actual fun bpfProbeAttach(progFd: Int, tracepoint: String): Int = -1
