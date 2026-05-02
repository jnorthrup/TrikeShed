package borg.trikeshed.cpucache

/**
 * MLIR dialect encoding for CPU cache topology.
 *
 * Dialect: cpu_cache
 * Operations:
 *   - cpu_cache.topology: Root operation describing full cache hierarchy
 *   - cpu_cache.level: Nested op for each cache level
 *   - cpu_cache.properties: Attribute bag for cache properties
 */
object CpuCacheMlir {

    /**
     * MLIR attribute kinds for cache encoding.
     */
    enum class CacheLevel(val mlirName: String) {
        L1("L1"),
        L2("L2"),
        L3("L3");

        fun toMlirAttribute(): String = "#cpu_cache.level<$mlirName>"
    }

    enum class CacheType(val mlirName: String) {
        DATA("Data"),
        INSTRUCTION("Instruction"),
        UNIFIED("Unified");

        fun toMlirAttribute(): String = "#cpu_cache.type<$mlirName>"
    }

    /**
     * Encode CpuCacheTopology as MLIR assembly format.
     */
    fun toMlirAssembly(topology: CpuCacheTopology): String {
        val sb = StringBuilder()
        sb.appendLine("cpu_cache.topology {")
        
        topology.l1DataBytes?.let { size ->
            sb.appendLine("  cpu_cache.level<L1, Data> { size: $size : i64 }")
        }
        
        topology.l1InstructionBytes?.let { size ->
            sb.appendLine("  cpu_cache.level<L1, Instruction> { size: $size : i64 }")
        }
        
        topology.l2Bytes?.let { size ->
            sb.appendLine("  cpu_cache.level<L2, Unified> { size: $size : i64 }")
        }
        
        topology.l3Bytes?.let { size ->
            sb.appendLine("  cpu_cache.level<L3, Unified> { size: $size : i64 }")
        }
        
        topology.coreCount?.let { cores ->
            sb.appendLine("  cpu_cache.properties { core_count: $cores : i32 }")
        }
        
        sb.append("}")
        return sb.toString()
    }

    // Platform pattern types used by tests
    sealed class PlatformPattern(val name: String) {
        class LinuxSys : PlatformPattern("linux-sys")
        class MacOSSysctl : PlatformPattern("macos-sysctl")
        class GenericSysconf : PlatformPattern("posix-sysconf")
    }

}

/**
 * Extension property: convert CpuCacheTopology to MLIR assembly format.
 */
val CpuCacheTopology.asMlir: String 
    get() = CpuCacheMlir.toMlirAssembly(this)
