package borg.trikeshed.cpucache

/**
 * Windows native target fallback for CpuCacheTopology.
 * Real native implementation requires calling GetLogicalProcessorInformation,
 * which is too complex for this stub without cinterop bindings.
 */
actual fun interrogateCpuCache(): CpuCacheTopology =
    CpuCacheTopology(null, null, null, null, null, null)
