package borg.trikeshed.cpucache

/**
 * JS/WASM: no portable cache interrogation.
 */
actual fun interrogateCpuCache(): CpuCacheTopology =
    CpuCacheTopology(null, null, null, null, null, null)
