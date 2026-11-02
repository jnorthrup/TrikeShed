package borg.trikeshed.userspace.nio.ebpf

import borg.trikeshed.userspace.nio.ebpf.engine.JitCode

// eBPF is a Linux-kernel feature (bpf(2)). Windows has none here: report failure, do not stub a fake success.
actual fun runNative(code: JitCode, args: LongArray): Long = -1L
actual fun bpfProbeAttach(progFd: Int, tracepoint: String): Int = -1
