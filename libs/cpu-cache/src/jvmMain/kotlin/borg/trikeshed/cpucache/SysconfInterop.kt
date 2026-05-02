package borg.trikeshed.cpucache

/**
 * JNI interop for POSIX sysconf() calls.
 * 
 * Direct mapping to sysconf constants from <unistd.h>:
 * - _SC_LEVEL1_DCACHE_SIZE = 188
 * - _SC_LEVEL1_ICACHE_SIZE = 189
 * - _SC_LEVEL1_DCACHE_LINESIZE = 190
 * - _SC_LEVEL2_CACHE_SIZE = 191
 * - _SC_LEVEL3_CACHE_SIZE = 194
 * - _SC_NPROCESSORS_ONLN = 84
 */
object SysconfInterop {
    
    // POSIX sysconf constant names (from unistd.h)
    const val _SC_LEVEL1_DCACHE_SIZE = 188
    const val _SC_LEVEL1_ICACHE_SIZE = 189
    const val _SC_LEVEL1_DCACHE_LINESIZE = 190
    const val _SC_LEVEL2_CACHE_SIZE = 191
    const val _SC_LEVEL3_CACHE_SIZE = 194
    const val _SC_NPROCESSORS_ONLN = 84
    
    /**
     * Call sysconf(2) via JNI. Returns -1 on error or if the value is indeterminate.
     * Native implementation in SysconfInterop.c
     */
    external fun sysconf(name: Int): Long
    
    init {
        System.loadLibrary("trikeshed_cpucache")
    }
    
    /**
     * Interrogate cache using POSIX sysconf().
     * Returns null for any value that sysconf cannot determine.
     */
    fun interrogateSysconf(): CpuCacheTopology {
        val l1d = sysconf(_SC_LEVEL1_DCACHE_SIZE).takeIf { it > 0 }
        val l1i = sysconf(_SC_LEVEL1_ICACHE_SIZE).takeIf { it > 0 }
        val line = sysconf(_SC_LEVEL1_DCACHE_LINESIZE).takeIf { it > 0 }?.toInt()
        val l2 = sysconf(_SC_LEVEL2_CACHE_SIZE).takeIf { it > 0 }
        val l3 = sysconf(_SC_LEVEL3_CACHE_SIZE).takeIf { it > 0 }
        val cores = sysconf(_SC_NPROCESSORS_ONLN).takeIf { it > 0 }?.toInt()
        
        return CpuCacheTopology(l1d, l1i, l2, l3, line, cores)
    }
}
