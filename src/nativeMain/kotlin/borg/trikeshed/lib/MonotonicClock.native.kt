package borg.trikeshed.lib

import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.CLOCK_MONOTONIC
import platform.posix.clock_gettime
import platform.posix.timespec

actual fun monotonicNowMillis(): Long = memScoped {
    val ts = alloc<timespec>()
    clock_gettime(CLOCK_MONOTONIC.convert(), ts.ptr)
    (ts.tv_sec.toLong() * 1000L) + (ts.tv_nsec.toLong() / 1_000_000L)
}
