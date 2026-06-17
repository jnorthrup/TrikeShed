package borg.trikeshed.cutedsl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * TDD Red: GEMM and Attention Kernels
 *
 * These tests define the expected behavior for core LLM kernels
 * (GEMM, flash attention, etc.) using CuTe DSL abstractions.
 * They should FAIL initially (RED phase).
 */
class GemmAttentionRedTest {

    @Test
    fun `GEMM kernel launch with M=64, N=64, K=32 tiles`() {
        // A: [M, K], B: [K, N], C: [M, N]
        val m = 64
        val n = 64
        val k = 32

        val aLayout = Layout.fromCursorShape(intArrayOf(m, k))
        val bLayout = Layout.fromCursorShape(intArrayOf(k, n))
        val cLayout = Layout.fromCursorShape(intArrayOf(m, n))

        val aData = DoubleArray(m * k) { (it % k).toDouble() }
        val bData = DoubleArray(k * n) { (it % n).toDouble() }
        val cData = DoubleArray(m * n)

        val aTile = Tile(aLayout, aData)
        val bTile = Tile(bLayout, bData)
        val cTile = Tile(cLayout, cData)

        // This should launch a tiled GEMM using MMA instructions
        gemm(aTile, bTile, cTile)

        // Verify C = A * B (simple check)
        assertTrue(cData[0] > 0.0)
    }

    @Test
    fun `GEMM kernel with MMA tile partitioning`() {
        // Standard MMA shape: 16x16x16 (M, N, K)
        val mmaM = 16
        val mmaN = 16
        val mmaK = 16

        val aLayout = Layout.fromCursorShape(intArrayOf(mmaM, mmaK))
        val bLayout = Layout.fromCursorShape(intArrayOf(mmaK, mmaN))
        val cLayout = Layout.fromCursorShape(intArrayOf(mmaM, mmaN))

        val aData = DoubleArray(mmaM * mmaK) { 1.0 }
        val bData = DoubleArray(mmaK * mmaN) { 1.0 }
        val cData = DoubleArray(mmaM * mmaN)

        val aTile = Tile(aLayout, aData)
        val bTile = Tile(bLayout, bData)
        val cTile = Tile(cLayout, cData)

        // Should use MMA tensor cores for 16x16x16
        mmaGemm(aTile, bTile, cTile)

        // C[i,j] = sum_k A[i,k] * B[k,j] = 16 * 1 * 1 = 16
        for (i in 0 until mmaM) {
            for (j in 0 until mmaN) {
                assertEquals(16.0, cTile[i, j], 1e-6, "C[$i,$j] should be 16.0")
            }
        }
    }

    @Test
    fun `Flash Attention kernel - Q*K^T`() {
        // Q: [seq_len, head_dim], K: [seq_len, head_dim]
        // S = Q * K^T : [seq_len, seq_len]
        val seqLen = 128
        val headDim = 64

        val qLayout = Layout.fromCursorShape(intArrayOf(seqLen, headDim))
        val kLayout = Layout.fromCursorShape(intArrayOf(seqLen, headDim))
        val sLayout = Layout.fromCursorShape(intArrayOf(seqLen, seqLen))

        val qData = DoubleArray(seqLen * headDim) { Math.random() }
        val kData = DoubleArray(seqLen * headDim) { Math.random() }
        val sData = DoubleArray(seqLen * seqLen)

        val qTile = Tile(qLayout, qData)
        val kTile = Tile(kLayout, kData)
        val sTile = Tile(sLayout, sData)

        // Flash attention Q*K^T with causal masking
        flashAttentionQKT(qTile, kTile, sTile, causal = true)

        // Verify causal: S[i,j] = 0 for j > i
        for (i in 0 until seqLen) {
            for (j in i + 1 until seqLen) {
                assertEquals(0.0, sTile[i, j], 1e-6, "Causal mask violated at [$i,$j]")
            }
        }
    }

    @Test
    fun `Flash Attention kernel - softmax(S) * V`() {
        val seqLen = 64
        val headDim = 64

        val sLayout = Layout.fromCursorShape(intArrayOf(seqLen, seqLen))
        val vLayout = Layout.fromCursorShape(intArrayOf(seqLen, headDim))
        val oLayout = Layout.fromCursorShape(intArrayOf(seqLen, headDim))

        val sData = DoubleArray(seqLen * seqLen) { Math.random() }
        val vData = DoubleArray(seqLen * headDim) { Math.random() }
        val oData = DoubleArray(seqLen * headDim)

        val sTile = Tile(sLayout, sData)
        val vTile = Tile(vLayout, vData)
        val oTile = Tile(oLayout, oData)

        // Softmax + V multiplication
        flashAttentionSV(sTile, vTile, oTile)

        // Output should be non-zero
        var sum = 0.0
        for (i in 0 until seqLen * headDim) {
            sum += oData[i]
        }
        assertTrue(sum != 0.0)
    }

    @Test
    fun `Pipelined GEMM with K-stage prefetch`() {
        // Multi-stage GEMM with async copy (TMA/CPAsync style)
        val m = 128
        val n = 128
        val k = 64
        val stages = 4 // K-stage pipelining

        val aLayout = Layout.fromCursorShape(intArrayOf(m, k))
        val bLayout = Layout.fromCursorShape(intArrayOf(k, n))
        val cLayout = Layout.fromCursorShape(intArrayOf(m, n))

        val aData = DoubleArray(m * k) { 1.0 }
        val bData = DoubleArray(k * n) { 1.0 }
        val cData = DoubleArray(m * n)

        val aTile = Tile(aLayout, aData)
        val bTile = Tile(bLayout, bData)
        val cTile = Tile(cLayout, cData)

        // Pipelined GEMM with explicit stages
        pipelinedGemm(aTile, bTile, cTile, stages)

        // Verify result
        for (i in 0 until m) {
            for (j in 0 until n) {
                assertEquals(k.toDouble(), cTile[i, j], 1e-6, "C[$i,$j] should be K=$k")
            }
        }
    }

    @Test
    fun `GEMM kernel composition with algebraic DSL (Join/Series)`() {
        // Integration test: GEMM as algebraic operation
        val m = 32
        val n = 32
        val k = 32

        val aLayout = Layout.fromCursorShape(intArrayOf(m, k))
        val bLayout = Layout.fromCursorShape(intArrayOf(k, n))
        val cLayout = Layout.fromCursorShape(intArrayOf(m, n))

        val aData = DoubleArray(m * k) { 2.0 }
        val bData = DoubleArray(k * n) { 3.0 }
        val cData = DoubleArray(m * n)

        val aTile = Tile(aLayout, aData)
        val bTile = Tile(bLayout, bData)
        val cTile = Tile(cLayout, cData)

        // GEMM as a composable algebraic transform
        val gemmOp = GemmTransform(m, n, k)
        val result = gemmOp.transform(aTile, bTile, cTile)

        assertNotNull(result)
        // 2 * 3 * 32 = 192
        for (i in 0 until m) {
            for (j in 0 until n) {
                assertEquals(192.0, result[i, j], 1e-6)
            }
        }
    }

    @Test
    fun `Kernel autotuning - multiple tile sizes`() {
        // Test that we can parameterize tile sizes for autotuning
        val tileSizes = listOf(
            intArrayOf(16, 16, 16),
            intArrayOf(32, 32, 32),
            intArrayOf(64, 64, 32),
            intArrayOf(128, 128, 32)
        )

        for (tileSize in tileSizes) {
            val (m, n, k) = tileSize.toTuple3()
            val layout = Layout.fromCursorShape(intArrayOf(m, n))
            assertEquals(m * n, layout.size)
        }
    }
}

/**
 * Placeholder kernel functions for RED phase
 */
fun gemm(a: Tile<Double>, b: Tile<Double>, c: Tile<Double>) {
    throw UnsupportedOperationException("gemm not implemented")
}

fun mmaGemm(a: Tile<Double>, b: Tile<Double>, c: Tile<Double>) {
    throw UnsupportedOperationException("mmaGemm not implemented")
}

fun flashAttentionQKT(q: Tile<Double>, k: Tile<Double>, s: Tile<Double>, causal: Boolean) {
    throw UnsupportedOperationException("flashAttentionQKT not implemented")
}

fun flashAttentionSV(s: Tile<Double>, v: Tile<Double>, o: Tile<Double>) {
    throw UnsupportedOperationException("flashAttentionSV not implemented")
}

fun pipelinedGemm(a: Tile<Double>, b: Tile<Double>, c: Tile<Double>, stages: Int) {
    throw UnsupportedOperationException("pipelinedGemm not implemented")
}

class GemmTransform(
    val m: Int, val n: Int, val k: Int
) {
    fun transform(a: Tile<Double>, b: Tile<Double>, c: Tile<Double>): Tile<Double> {
        throw UnsupportedOperationException("GemmTransform not implemented")
    }
}

private fun IntArray.toTuple3(): Triple<Int, Int, Int> = Triple(this[0], this[1], this[2])