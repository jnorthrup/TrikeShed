package borg.trikeshed.cpucache

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

/**
 * MLIR encoding tests for CpuCacheTopology.
 */
class CpuCacheMlirTest {

    @Test
    fun testToMlirAssemblyFullTopology() {
        val topology = CpuCacheTopology(
            l1DataBytes = 32768,
            l1InstructionBytes = 32768,
            l2Bytes = 262144,
            l3Bytes = 8388608,
            cacheLineBytes = 64,
            coreCount = 8
        )
        
        val mlir = topology.asMlir
        
        // Verify MLIR structure
        assertTrue(mlir.startsWith("cpu_cache.topology {"), "Should start with cpu_cache.topology")
        assertTrue(mlir.endsWith("}"), "Should end with closing brace")
        
        // Verify L1 Data cache
        assertTrue(mlir.contains("cpu_cache.level<L1, Data>"), "Should have L1 Data cache")
        assertTrue(mlir.contains("size: 32768 : i64"), "Should have L1 Data size")
        
        // Verify L1 Instruction cache
        assertTrue(mlir.contains("cpu_cache.level<L1, Instruction>"), "Should have L1 Instruction cache")
        assertTrue(mlir.contains("size: 32768 : i64"), "Should have L1 Instruction size")
        
        // Verify L2 cache
        assertTrue(mlir.contains("cpu_cache.level<L2, Unified>"), "Should have L2 Unified cache")
        assertTrue(mlir.contains("size: 262144 : i64"), "Should have L2 size")
        
        // Verify L3 cache
        assertTrue(mlir.contains("cpu_cache.level<L3, Unified>"), "Should have L3 Unified cache")
        assertTrue(mlir.contains("size: 8388608 : i64"), "Should have L3 size")
        
        // Verify core count
        assertTrue(mlir.contains("cpu_cache.properties"), "Should have properties")
        assertTrue(mlir.contains("core_count: 8 : i32"), "Should have core count")
    }

    @Test
    fun testToMlirAssemblyWithNulls() {
        val topology = CpuCacheTopology(
            l1DataBytes = null,
            l1InstructionBytes = null,
            l2Bytes = null,
            l3Bytes = null,
            cacheLineBytes = null,
            coreCount = null
        )
        
        val mlir = topology.asMlir
        
        // Should still have valid MLIR structure
        assertTrue(mlir.startsWith("cpu_cache.topology {"))
        assertTrue(mlir.endsWith("}"))
        
        // Should not contain any cache levels
        assertTrue(!mlir.contains("cpu_cache.level<"), "Should have no cache levels when all null")
        assertTrue(!mlir.contains("cpu_cache.properties"), "Should have no properties when all null")
    }

    @Test
    fun testToMlirAssemblyPartialTopology() {
        val topology = CpuCacheTopology(
            l1DataBytes = 32768,
            l1InstructionBytes = null,
            l2Bytes = 262144,
            l3Bytes = null,
            cacheLineBytes = 64,
            coreCount = 4
        )
        
        val mlir = topology.asMlir
        
        // Should have L1 Data
        assertTrue(mlir.contains("cpu_cache.level<L1, Data>"))
        
        // Should NOT have L1 Instruction
        assertTrue(!mlir.contains("cpu_cache.level<L1, Instruction>"))
        
        // Should have L2
        assertTrue(mlir.contains("cpu_cache.level<L2, Unified>"))
        
        // Should NOT have L3
        assertTrue(!mlir.contains("cpu_cache.level<L3, Unified>"))
        
        // Should have properties with core count
        assertTrue(mlir.contains("cpu_cache.properties"))
        assertTrue(mlir.contains("core_count: 4 : i32"))
    }

    @Test
    fun testCacheLevelAttributes() {
        val l1 = CpuCacheMlir.CacheLevel.L1
        val l2 = CpuCacheMlir.CacheLevel.L2
        val l3 = CpuCacheMlir.CacheLevel.L3
        
        assertTrue(l1.toMlirAttribute().contains("L1"))
        assertTrue(l2.toMlirAttribute().contains("L2"))
        assertTrue(l3.toMlirAttribute().contains("L3"))
    }

    @Test
    fun testCacheTypeAttributes() {
        val data = CpuCacheMlir.CacheType.DATA
        val instruction = CpuCacheMlir.CacheType.INSTRUCTION
        val unified = CpuCacheMlir.CacheType.UNIFIED
        
        assertTrue(data.toMlirAttribute().contains("Data"))
        assertTrue(instruction.toMlirAttribute().contains("Instruction"))
        assertTrue(unified.toMlirAttribute().contains("Unified"))
    }

    @Test
    fun testSysconfConstants() {
        // Verify POSIX sysconf constants match <unistd.h>
        assertTrue(CpuCacheMlir.SysconfConstants.LEVEL1_DCACHE_SIZE == 188)
        assertTrue(CpuCacheMlir.SysconfConstants.LEVEL1_ICACHE_SIZE == 189)
        assertTrue(CpuCacheMlir.SysconfConstants.LEVEL1_DCACHE_LINESIZE == 190)
        assertTrue(CpuCacheMlir.SysconfConstants.LEVEL2_CACHE_SIZE == 191)
        assertTrue(CpuCacheMlir.SysconfConstants.LEVEL3_CACHE_SIZE == 194)
        assertTrue(CpuCacheMlir.SysconfConstants.NPROCESSORS_ONLN == 84)
    }

    @Test
    fun testToLlvmDialectModule() {
        val topology = CpuCacheTopology(
            l1DataBytes = 32768,
            l1InstructionBytes = 32768,
            l2Bytes = 262144,
            l3Bytes = 8388608,
            cacheLineBytes = 64,
            coreCount = 8
        )
        
        val llvmMlir = CpuCacheMlir.toLlvmDialrectModule(topology)
        
        // Verify module structure
        assertTrue(llvmMlir.startsWith("module {"), "Should start with module declaration")
        assertTrue(llvmMlir.endsWith("}"), "Should end with closing brace")
        
        // Verify sysconf declarations
        assertTrue(llvmMlir.contains("llvm.func @sysconf(i32) -> i64"), "Should declare sysconf")
        assertTrue(llvmMlir.contains("llvm.func @printf(!llvm.ptr, ...) -> i32"), "Should declare printf")
        
        // Verify sysconf constants match POSIX values
        assertTrue(llvmMlir.contains("188 : i32"), "Should use _SC_LEVEL1_DCACHE_SIZE constant")
        assertTrue(llvmMlir.contains("189 : i32"), "Should use _SC_LEVEL1_ICACHE_SIZE constant")
        assertTrue(llvmMlir.contains("190 : i32"), "Should use _SC_LEVEL1_DCACHE_LINESIZE constant")
        assertTrue(llvmMlir.contains("191 : i32"), "Should use _SC_LEVEL2_CACHE_SIZE constant")
        assertTrue(llvmMlir.contains("194 : i32"), "Should use _SC_LEVEL3_CACHE_SIZE constant")
        assertTrue(llvmMlir.contains("84 : i32"), "Should use _SC_NPROCESSORS_ONLN constant")
        
        // Verify LLVM dialect call patterns
        assertTrue(llvmMlir.contains("llvm.call @sysconf"), "Should call sysconf")
        assertTrue(llvmMlir.contains("llvm.call @printf"), "Should call printf")
        assertTrue(llvmMlir.contains("vararg(!llvm.func<i32 (!llvm.ptr, ...)>)"), "Should use variadic printf")
        
        // Verify global string constants
        assertTrue(llvmMlir.contains("llvm.mlir.global"), "Should define global string constants")
        assertTrue(llvmMlir.contains("!llvm.array<"), "Should use typed array literals for strings")
        
        // Verify opaque pointers (LLVM 15+ style)
        assertTrue(llvmMlir.contains("!llvm.ptr"), "Should use opaque pointers")
        assertTrue(llvmMlir.contains("llvm.mlir.addressof"), "Should use addressof for globals")
    }

    @Test
    fun testToLlvmDialectModuleWithNulls() {
        // Even with null values, LLVM module should be generated
        // (it's a self-contained probe, not dependent on topology values)
        val topology = CpuCacheTopology(
            l1DataBytes = null,
            l1InstructionBytes = null,
            l2Bytes = null,
            l3Bytes = null,
            cacheLineBytes = null,
            coreCount = null
        )
        
        val llvmMlir = CpuCacheMlir.toLlvmDialrectModule(topology)
        
        // Should still generate complete module
        assertTrue(llvmMlir.contains("llvm.func @sysconf"))
        assertTrue(llvmMlir.contains("llvm.func @printf"))
        assertTrue(llvmMlir.contains("llvm.func @main"))
    }
}
