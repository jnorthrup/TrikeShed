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
}
