package nio.ebpf

import nio.ebpf.jit.JitCode

expect fun runNative(code: JitCode, args: LongArray): Long
