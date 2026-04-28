package borg.trikeshed.cpucache

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix._SC_NPROCESSORS_ONLN
import platform.posix.sysconf

/**
 * Native cache interrogation via POSIX sysconf.
 *
 * Works on Linux and macOS native targets. Falls back to zero when
 * the sysconf constant is undefined on the platform.
 */
@OptIn(ExperimentalForeignApi::class)
actual fun interrogateCpuCache(): CpuCacheTopology {
    // _SC_LEVEL* constants are Linux-only; macOS has no portable sysconf names for cache sizes.
    // Fall back to null (unknown) rather than hardcoding guess values.
    val cores = sysconf(_SC_NPROCESSORS_ONLN).takeIf { it > 0 }?.toInt()

    return CpuCacheTopology(
        l1DataBytes = null,
        l1InstructionBytes = null,
        l2Bytes = null,
        l3Bytes = null,
        cacheLineBytes = null,  // no POSIX constant for cache line — use /sys or sysctl
        coreCount = cores,
    )
}
