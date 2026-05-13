package nio.ebpf

import nio.ebpf.engine.JitCode

expect fun runNative(code: JitCode, args: LongArray): Long
