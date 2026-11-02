@file:OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)

package borg.trikeshed.platform

import kotlinx.cinterop.*
import platform.windows.LARGE_INTEGER
import platform.windows.QueryPerformanceCounter
import platform.windows.QueryPerformanceFrequency
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform
import kotlin.time.Clock.System

/** Ticks per second of QueryPerformanceCounter, fixed at boot. */
val performanceFrequency: Long by lazy {
    memScoped {
        val frequency = alloc<LARGE_INTEGER>()
        check(QueryPerformanceFrequency(frequency.ptr) != 0) { "QueryPerformanceFrequency failed" }
        frequency.QuadPart
    }
}

/** Windows host: the system clock, QueryPerformanceCounter as the monotonic clock. */
actual fun loadPlatformHost(): PlatformHost = object : PlatformHost {
    override val clock: PlatformClock = object : PlatformClock {
        override fun nowMillis(): Long = System.now().toEpochMilliseconds()
        override fun monotonicNanos(): Long = memScoped {
            val counter = alloc<LARGE_INTEGER>()
            check(QueryPerformanceCounter(counter.ptr) != 0) { "QueryPerformanceCounter failed" }
            val ticks = counter.QuadPart
            val frequency = performanceFrequency
            ticks / frequency * 1_000_000_000L + ticks % frequency * 1_000_000_000L / frequency
        }
    }
    override val processors: Int = Platform.getAvailableProcessors()
    override val descriptor: HostDescriptor = HostDescriptor(
        runtime = HostRuntime.NATIVE,
        rawOs = "Windows",
        rawArchitecture = Platform.cpuArchitecture.name,
        runtimeVersion = KotlinVersion.CURRENT.toString(),
        runtimeName = "Kotlin/Native",
    )
}

/** Windows delivers no POSIX signals; handlers are recorded, never installed (the macOS form). */
actual object PosixSignals {
    actual fun handle(signal: String, handler: () -> Unit) {}
}
