package borg.trikeshed.cpucache

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.sysconf
import platform.posix._SC_LEVEL1_DCACHE_SIZE
import platform.posix._SC_LEVEL1_ICACHE_SIZE
import platform.posix._SC_LEVEL2_CACHE_SIZE
import platform.posix._SC_LEVEL3_CACHE_SIZE
import platform.posix._SC_NPROCESSORS_ONLN

/**
 * Native cache interrogation via POSIX sysconf.
 *
 * Works on Linux and macOS native targets. Falls back to zero when
 * the sysconf constant is undefined on the platform.
 */
@OptIn(ExperimentalForeignApi::class)
actual fun interrogateCpuCache(): CpuCacheTopology {
    val l1d = sysconf(_SC_LEVEL1_DCACHE_SIZE).takeIf { it > 0 }
    val l1i = sysconf(_SC_LEVEL1_ICACHE_SIZE).takeIf { it > 0 }
    val l2  = sysconf(_SC_LEVEL2_CACHE_SIZE).takeIf { it > 0 }
    val l3  = sysconf(_SC_LEVEL3_CACHE_SIZE).takeIf { it > 0 }
    val cores = sysconf(_SC_NPROCESSORS_ONLN).takeIf { it > 0 }?.toInt()

    return CpuCacheTopology(
        l1DataBytes = l1d,
        l1InstructionBytes = l1i,
        l2Bytes = l2,
        l3Bytes = l3,
        cacheLineBytes = null,  // no POSIX constant for cache line — use /sys or sysctl
        coreCount = cores,
    )
}
