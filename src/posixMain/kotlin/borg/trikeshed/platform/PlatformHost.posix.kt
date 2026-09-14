@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package borg.trikeshed.platform

import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import platform.posix.CLOCK_MONOTONIC
import platform.posix.clock_gettime
import platform.posix.errno
import platform.posix.timespec
import platform.posix.uname
import platform.posix.utsname
import kotlin.native.Platform
import kotlin.time.Clock.System

actual fun loadPlatformHost(): PlatformHost = object : PlatformHost {
    override val clock: PlatformClock = object : PlatformClock {
        override fun nowMillis(): Long = System.now().toEpochMilliseconds()
        override fun monotonicNanos(): Long = memScoped {
            val sample = alloc<timespec>()
            check(clock_gettime(CLOCK_MONOTONIC.convert(), sample.ptr) == 0) {
                "clock_gettime(CLOCK_MONOTONIC) failed: $errno"
            }
            sample.tv_sec * 1_000_000_000L + sample.tv_nsec
        }
    }
    override val processors: Int = Platform.getAvailableProcessors()
    override val descriptor: HostDescriptor = memScoped {
        val system = alloc<utsname>()
        check(uname(system.ptr) == 0) { "uname failed: $errno" }
        HostDescriptor(
            runtime = HostRuntime.NATIVE,
            rawOs = system.sysname.toKString(),
            rawArchitecture = Platform.cpuArchitecture.name,
            osVersion = system.release.toKString(),
            runtimeVersion = KotlinVersion.CURRENT.toString(),
            runtimeName = "Kotlin/Native",
        )
    }
    override val resources: Any? get() = PosixResourceSource
}
