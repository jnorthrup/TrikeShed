package borg.trikeshed.userspace.nio.ebpf

import borg.trikeshed.userspace.nio.ebpf.engine.JitCode

expect fun runNative(code: JitCode, args: LongArray): Long
