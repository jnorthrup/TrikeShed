package borg.trikeshed.blackboard

import platform.posix.clock_gettime
import platform.posix.CLOCK_REALTIME

actual fun currentTimeMillis(): Long {
    val timespec = platform.posix.timespec()
    clock_gettime(CLOCK_REALTIME, timespec.ptr)
    return timespec.tv_sec * 1000L + timespec.tv_nsec / 1_000_000L
}
