package borg.trikeshed.cpucache

import kotlin.test.Test
import kotlin.test.assertEquals

class CpuCacheTopologyTest {
    @Test
    fun testToConfix() {
        val topology = CpuCacheTopology(
            l1DataBytes = 32768,
            l1InstructionBytes = 32768,
            l2Bytes = 262144,
            l3Bytes = 8388608,
            cacheLineBytes = 64,
            coreCount = 8
        )
        val confix = topology.toConfix()

        val expected = """
            {
              "l1DataBytes": 32768,
              "l1InstructionBytes": 32768,
              "l2Bytes": 262144,
              "l3Bytes": 8388608,
              "cacheLineBytes": 64,
              "coreCount": 8
            }
        """.trimIndent()
        assertEquals(expected, confix)
    }

    @Test
    fun testToConfixWithNulls() {
        val topology = CpuCacheTopology(
            l1DataBytes = null,
            l1InstructionBytes = null,
            l2Bytes = null,
            l3Bytes = null,
            cacheLineBytes = null,
            coreCount = null
        )
        val confix = topology.toConfix()

        val expected = """
            {
              "l1DataBytes": null,
              "l1InstructionBytes": null,
              "l2Bytes": null,
              "l3Bytes": null,
              "cacheLineBytes": null,
              "coreCount": null
            }
        """.trimIndent()
        assertEquals(expected, confix)
    }

    @Test
    fun testEquality() {
        val t1 = CpuCacheTopology(32768, null, null, null, 64, 4)
        val t2 = CpuCacheTopology(32768, null, null, null, 64, 4)
        assertEquals(t1, t2)
    }
}
