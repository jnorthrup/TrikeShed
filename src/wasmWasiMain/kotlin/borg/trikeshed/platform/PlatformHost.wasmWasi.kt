package borg.trikeshed.platform

import borg.trikeshed.userspace.wasi.Wasi

/** WASI host: clock_time_get clocks, one processor (WASI preview 1 has no threads). */
actual fun loadPlatformHost(): PlatformHost = object : PlatformHost {
    override val clock: PlatformClock = object : PlatformClock {
        override fun nowMillis(): Long = Wasi.realtimeNanos() / 1_000_000L
        override fun monotonicNanos(): Long = Wasi.monotonicNanos()
    }
    override val processors: Int = 1
    override val descriptor: HostDescriptor = HostDescriptor(
        runtime = HostRuntime.WASI,
        rawOs = "wasi",
        rawArchitecture = "wasm32",
        runtimeName = "wasi_snapshot_preview1",
    )
}
