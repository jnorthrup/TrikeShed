@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package borg.trikeshed.platform

import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import platform.posix.CLOCK_MONOTONIC
import platform.posix.clock_gettime
import platform.posix.timespec
import platform.posix.uname
import platform.posix.utsname
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PlatformHostPosixTest {
    @Test
    fun descriptorIdentifiesTheNativeRuntime(): Unit = memScoped {
        val host = loadPlatformHost()
        val system = alloc<utsname>()
        assertEquals(0, uname(system.ptr))
        println("native host: ${host.descriptor}; processors=${host.processors}")
        assertEquals("NATIVE", host.descriptor.runtime.name)
        assertEquals(system.sysname.toKString(), host.descriptor.rawOs)
        assertEquals(system.release.toKString(), host.descriptor.osVersion)
        assertNotNull(host.descriptor.architecture)
        assertEquals("Kotlin/Native", host.descriptor.runtimeName)
    }

    @Test
    fun monotonicNanosecondsFollowTheOperatingSystemClock() = memScoped {
        val clock = loadPlatformHost().clock
        val sample = alloc<timespec>()
        fun sampleNanos(): Long {
            assertEquals(0, clock_gettime(CLOCK_MONOTONIC.convert(), sample.ptr))
            return sample.tv_sec * 1_000_000_000L + sample.tv_nsec
        }
        val before = sampleNanos()
        val observed = clock.monotonicNanos()
        val after = sampleNanos()
        assertTrue(observed in before..after,
            "monotonic nanoseconds $observed are outside the OS clock interval $before..$after")
    }
}
