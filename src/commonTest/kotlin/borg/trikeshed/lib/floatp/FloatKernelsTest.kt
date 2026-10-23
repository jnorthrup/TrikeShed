package borg.trikeshed.lib.floatp

import kotlin.test.*

class FloatKernelsTest {
    val flat = floatArrayOf(1f, 0f, 0f, 1f, 1f, 1f, 0f, 0f)
    val probe = floatArrayOf(1f, 0f)

    @Test fun dotAndL2() {
        val out = FloatArray(4)
        dot(flat, 4, 2, probe, out)
        assertEquals(listOf(1f, 0f, 1f, 0f), out.toList())
        l2Squared(flat, 4, 2, probe, out)
        assertEquals(listOf(0f, 2f, 1f, 1f), out.toList())
    }

    @Test fun cosineHandlesZeroNorms() {
        val out = FloatArray(4)
        cosine(flat, 4, 2, probe, out)
        assertEquals(1f, out[0])
        assertEquals(0f, out[1])
        assertEquals(0.70710677f, out[2], 1e-6f)
        assertEquals(0f, out[3], "zero row")
        cosine(flat, 4, 2, floatArrayOf(0f, 0f), out)
        assertTrue(out.all { it == 0f }, "zero probe")
    }

    @Test fun topKByScore() {
        val scores = floatArrayOf(0.1f, 0.9f, 0.5f, 0.9f)
        assertEquals(listOf(1, 3, 2), topK(scores, 4, 3, descending = true).toList())
    }
}
