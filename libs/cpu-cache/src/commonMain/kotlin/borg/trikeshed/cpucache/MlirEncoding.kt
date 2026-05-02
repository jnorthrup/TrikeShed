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

    /**
     * POSIX sysconf constants (from <unistd.h>)
     */
    object SysconfConstants {
        const val LEVEL1_DCACHE_SIZE = 188
        const val LEVEL1_ICACHE_SIZE = 189
        const val LEVEL1_DCACHE_LINESIZE = 190
        const val LEVEL2_CACHE_SIZE = 191
        const val LEVEL3_CACHE_SIZE = 194
        const val NPROCESSORS_ONLN = 84
    }

    /**
     * Generate LLVM dialect MLIR module that calls sysconf() directly.
     * This follows the pattern from cache_geometry.mlir:
     * - Uses llvm.func to declare sysconf and printf
     * - Calls sysconf with hardcoded constant arguments
     * - Formats results via printf
     * 
     * The generated module can be compiled with:
     *   mlir-translate --mlir-to-llvmir output.mlir | clang -x ir - -o cache_probe
     */
    fun toLlvmDialrectModule(topology: CpuCacheTopology): String {
        return """
module {
  // -----------------------------------------------------------------------
  // Global string array constants for printing
  // -----------------------------------------------------------------------
  llvm.mlir.global internal constant @fmt_l1_line("L1 D-Cache Line Size: %ld bytes\0A\00") : !llvm.array<33 x i8>
  llvm.mlir.global internal constant @fmt_l1_size("L1 D-Cache Size: %ld bytes\0A\00") : !llvm.array<28 x i8>
  llvm.mlir.global internal constant @fmt_l1i_size("L1 I-Cache Size: %ld bytes\0A\00") : !llvm.array<29 x i8>
  llvm.mlir.global internal constant @fmt_l2_size("L2 Cache Size: %ld bytes\0A\00") : !llvm.array<26 x i8>
  llvm.mlir.global internal constant @fmt_l3_size("L3 Cache Size: %ld bytes\0A\00") : !llvm.array<26 x i8>
  llvm.mlir.global internal constant @fmt_cores("Core Count: %d\0A\00") : !llvm.array<17 x i8>

  // -----------------------------------------------------------------------
  // External POSIX and C Standard Library declarations
  // -----------------------------------------------------------------------
  llvm.func @sysconf(i32) -> i64
  llvm.func @printf(!llvm.ptr, ...) -> i32

  // -----------------------------------------------------------------------
  // Main Entry Point
  // -----------------------------------------------------------------------
  llvm.func @main() -> i32 {
    // Standard POSIX / glibc sysconf constants
    %c_l1_size = llvm.mlir.constant(${SysconfConstants.LEVEL1_DCACHE_SIZE} : i32) : i32
    %c_l1i_size = llvm.mlir.constant(${SysconfConstants.LEVEL1_ICACHE_SIZE} : i32) : i32
    %c_l1_line = llvm.mlir.constant(${SysconfConstants.LEVEL1_DCACHE_LINESIZE} : i32) : i32
    %c_l2_size = llvm.mlir.constant(${SysconfConstants.LEVEL2_CACHE_SIZE} : i32) : i32
    %c_l3_size = llvm.mlir.constant(${SysconfConstants.LEVEL3_CACHE_SIZE} : i32) : i32
    %c_cores = llvm.mlir.constant(${SysconfConstants.NPROCESSORS_ONLN} : i32) : i32

    // Execute sysconf() to retrieve the geometry in bytes
    %l1_size = llvm.call @sysconf(%c_l1_size) : (i32) -> i64
    %l1i_size = llvm.call @sysconf(%c_l1i_size) : (i32) -> i64
    %l1_line = llvm.call @sysconf(%c_l1_line) : (i32) -> i64
    %l2_size = llvm.call @sysconf(%c_l2_size) : (i32) -> i64
    %l3_size = llvm.call @sysconf(%c_l3_size) : (i32) -> i64
    %cores = llvm.call @sysconf(%c_cores) : (i32) -> i64

    // Retrieve opaque pointers to our string constants
    %ptr_l1_line = llvm.mlir.addressof @fmt_l1_line : !llvm.ptr
    %ptr_l1_size = llvm.mlir.addressof @fmt_l1_size : !llvm.ptr
    %ptr_l1i_size = llvm.mlir.addressof @fmt_l1i_size : !llvm.ptr
    %ptr_l2_size = llvm.mlir.addressof @fmt_l2_size : !llvm.ptr
    %ptr_l3_size = llvm.mlir.addressof @fmt_l3_size : !llvm.ptr
    %ptr_cores = llvm.mlir.addressof @fmt_cores : !llvm.ptr

    // Print the extracted geometries
    llvm.call @printf(%ptr_l1_line, %l1_line) vararg(!llvm.func<i32 (!llvm.ptr, ...)>) : (!llvm.ptr, i64) -> i32
    llvm.call @printf(%ptr_l1_size, %l1_size) vararg(!llvm.func<i32 (!llvm.ptr, ...)>) : (!llvm.ptr, i64) -> i32
    llvm.call @printf(%ptr_l1i_size, %l1i_size) vararg(!llvm.func<i32 (!llvm.ptr, ...)>) : (!llvm.ptr, i64) -> i32
    llvm.call @printf(%ptr_l2_size, %l2_size) vararg(!llvm.func<i32 (!llvm.ptr, ...)>) : (!llvm.ptr, i64) -> i32
    llvm.call @printf(%ptr_l3_size, %l3_size) vararg(!llvm.func<i32 (!llvm.ptr, ...)>) : (!llvm.ptr, i64) -> i32
    llvm.call @printf(%ptr_cores, %cores) vararg(!llvm.func<i32 (!llvm.ptr, ...)>) : (!llvm.ptr, i64) -> i32

    // return 0
    %ret = llvm.mlir.constant(0 : i32) : i32
    llvm.return %ret : i32
  }
}
""".trimIndent()
    }

}

/**
 * Extension property: convert CpuCacheTopology to MLIR assembly format.
 */
val CpuCacheTopology.asMlir: String 
    get() = CpuCacheMlir.toMlirAssembly(this)
