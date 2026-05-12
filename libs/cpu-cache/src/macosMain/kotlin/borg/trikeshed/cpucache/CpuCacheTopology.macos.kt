package borg.trikeshed.cpucache

import kotlinx.cinterop.*
import platform.darwin.sysctlbyname
import platform.posix._SC_NPROCESSORS_ONLN
import platform.posix.size_tVar
import platform.posix.sysconf

/**
 * macOS native cache interrogation via POSIX sysctlbyname.
 */
@OptIn(ExperimentalForeignApi::class)
actual fun interrogateCpuCache(): CpuCacheTopology {
    val cores = sysconf(_SC_NPROCESSORS_ONLN).takeIf { it > 0 }?.toInt()

    return memScoped {
        fun sysctlLong(name: CharSequence): Long? {
            val size = alloc<size_tVar>()
            val value = alloc<platform.posix.int64_tVar>()
            size.value = 8u
            return if (sysctlbyname(name.toString(), value.ptr, size.ptr, null, 0u) == 0) {
                value.value.takeIf { it > 0 }
            } else null
        }
        fun sysctlInt(name: CharSequence): Int? {
            val size = alloc<size_tVar>()
            val value = alloc<platform.posix.int32_tVar>()
            size.value = 4u
            return if (sysctlbyname(name.toString(), value.ptr, size.ptr, null, 0u) == 0) {
                value.value.takeIf { it > 0 }
            } else null
        }

        CpuCacheTopology(
            l1DataBytes = sysctlLong("hw.l1dcachesize"),
            l1InstructionBytes = sysctlLong("hw.l1icachesize"),
            l2Bytes = sysctlLong("hw.l2cachesize"),
            l3Bytes = sysctlLong("hw.l3cachesize"),
            cacheLineBytes = sysctlInt("hw.cachelinesize"),
            coreCount = sysctlInt("hw.ncpu") ?: cores,
        )
    }
}
