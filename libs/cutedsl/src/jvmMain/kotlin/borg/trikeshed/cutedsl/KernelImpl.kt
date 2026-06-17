package borg.trikeshed.cutedsl

/**
 * JVM-specific kernel implementations.
 *
 * These provide the actual GPU kernel launches via JNI/CUDA or
 * reference CPU implementations for testing.
 */
@Suppress("UNUSED_PARAMETER")
object KernelImpl {

    /**
     * Reference CPU GEMM for testing: C = A * B
     * A: [M, K], B: [K, N], C: [M, N]
     */
    fun gemmCpu(
        a: CursorTensor<Double>,
        b: CursorTensor<Double>,
        c: CursorTensor<Double>
    ): CursorTensor<Double> {
        require(a.shape.size == 2 && b.shape.size == 2 && c.shape.size == 2)
        val m = a.shape[0]
        val n = b.shape[1]
        val k = a.shape[1]
        require(b.shape[0] == k) { "K mismatch: ${b.shape[0]} vs $k" }
        require(c.shape[0] == m && c.shape[1] == n) { "C shape mismatch" }

        val outData = DoubleArray(m * n)
        for (i in 0 until m) {
            for (j in 0 until n) {
                var sum = 0.0
                for (kk in 0 until k) {
                    sum += a[i, kk] * b[kk, j]
                }
                outData[i * n + j] = sum
            }
        }

        return CursorTensor(
            shape = intArrayOf(m, n),
            layout = Layout.fromCursorShape(intArrayOf(m, n)),
            data = outData.toTypedArray(),
            provenance = a.provenance?.withTransform("gemm_cpu")
                ?.withMetadata("m", m.toString())
                ?.withMetadata("n", n.toString())
                ?.withMetadata("k", k.toString()),
            columns = listOf("m", "n")
        )
    }

    /**
     * Reference CPU MMA-style tiled GEMM.
     * Partitions into 16x16x16 tiles and accumulates.
     */
    fun mmaGemmCpu(
        a: CursorTensor<Double>,
        b: CursorTensor<Double>,
        c: CursorTensor<Double>,
        tileM: Int = 16,
        tileN: Int = 16,
        tileK: Int = 16
    ): CursorTensor<Double> {
        val m = a.shape[0]
        val n = b.shape[1]
        val k = a.shape[1]

        val outData = DoubleArray(m * n)
        for (i in 0 until m) {
            for (j in 0 until n) {
                outData[i * n + j] = 0.0
            }
        }

        // Tile loops
        for (iTile in 0 until m step tileM) {
            for (jTile in 0 until n step tileN) {
                val iEnd = minOf(iTile + tileM, m)
                val jEnd = minOf(jTile + tileN, n)

                for (kTile in 0 until k step tileK) {
                    val kEnd = minOf(kTile + tileK, k)

                    // Inner tile GEMM
                    for (i in iTile until iEnd) {
                        for (j in jTile until jEnd) {
                            var sum = 0.0
                            for (kk in kTile until kEnd) {
                                sum += a[i, kk] * b[kk, j]
                            }
                            outData[i * n + j] += sum
                        }
                    }
                }
            }
        }

        return CursorTensor(
            shape = intArrayOf(m, n),
            layout = Layout.fromCursorShape(intArrayOf(m, n)),
            data = outData.toTypedArray(),
            provenance = a.provenance?.withTransform("mma_gemm_cpu"),
            columns = listOf("m", "n")
        )
    }

    /**
     * Reference CPU Flash Attention: Q * K^T -> S, then softmax(S) * V -> O
     * Q: [seq_len, head_dim], K: [seq_len, head_dim], V: [seq_len, head_dim]
     * S: [seq_len, seq_len], O: [seq_len, head_dim]
     */
    fun flashAttentionCpu(
        q: CursorTensor<Double>,
        k: CursorTensor<Double>,
        v: CursorTensor<Double>,
        causal: Boolean = true
    ): CursorTensor<Double> {
        require(q.shape.size == 2 && k.shape.size == 2 && v.shape.size == 2)
        val seqLen = q.shape[0]
        val headDim = q.shape[1]
        require(k.shape[0] == seqLen && k.shape[1] == headDim)
        require(v.shape[0] == seqLen && v.shape[1] == headDim)

        // Step 1: S = Q * K^T
        val sData = DoubleArray(seqLen * seqLen)
        for (i in 0 until seqLen) {
            for (j in 0 until seqLen) {
                if (causal && j > i) {
                    sData[i * seqLen + j] = Double.NEGATIVE_INFINITY
                } else {
                    var sum = 0.0
                    for (d in 0 until headDim) {
                        sum += q[i, d] * k[j, d]
                    }
                    sData[i * seqLen + j] = sum / Math.sqrt(headDim.toDouble())
                }
            }
        }

        // Step 2: Softmax(S)
        for (i in 0 until seqLen) {
            // Find max for numerical stability
            var maxVal = Double.NEGATIVE_INFINITY
            for (j in 0 until seqLen) {
                val v = sData[i * seqLen + j]
                if (v > maxVal) maxVal = v
            }

            // Exponentiate and sum
            var sumExp = 0.0
            for (j in 0 until seqLen) {
                val expVal = if (sData[i * seqLen + j].isInfinite()) 0.0 else Math.exp(sData[i * seqLen + j] - maxVal)
                sData[i * seqLen + j] = expVal
                sumExp += expVal
            }

            // Normalize
            for (j in 0 until seqLen) {
                sData[i * seqLen + j] /= sumExp
            }
        }

        // Step 3: O = S * V
        val oData = DoubleArray(seqLen * headDim)
        for (i in 0 until seqLen) {
            for (d in 0 until headDim) {
                var sum = 0.0
                for (j in 0 until seqLen) {
                    sum += sData[i * seqLen + j] * v[j, d]
                }
                oData[i * headDim + d] = sum
            }
        }

        return CursorTensor(
            shape = intArrayOf(seqLen, headDim),
            layout = Layout.fromCursorShape(intArrayOf(seqLen, headDim)),
            data = oData.toTypedArray(),
            provenance = q.provenance?.withTransform("flash_attention_cpu")
                ?.withMetadata("seq_len", seqLen.toString())
                ?.withMetadata("head_dim", headDim.toString())
                ?.withMetadata("causal", causal.toString()),
            columns = listOf("seq", "dim")
        )
    }

    /**
     * Pipelined GEMM simulation (K-stage).
     * This simulates the async copy/compute overlap on CPU.
     */
    fun pipelinedGemmCpu(
        a: CursorTensor<Double>,
        b: CursorTensor<Double>,
        c: CursorTensor<Double>,
        stages: Int = 4
    ): CursorTensor<Double> {
        // For CPU reference, just delegate to tiled GEMM
        // Real GPU impl would use TMA + MMA pipeline
        return mmaGemmCpu(a, b, c)
    }

    /**
     * Autotuning helper: try multiple tile sizes and pick fastest.
     */
    fun autotuneGemm(
        a: CursorTensor<Double>,
        b: CursorTensor<Double>,
        tileSizes: List<IntArray> = listOf(
            intArrayOf(16, 16, 16),
            intArrayOf(32, 32, 16),
            intArrayOf(64, 64, 32),
            intArrayOf(128, 128, 32)
        )
    ): Pair<IntArray, Double> { // Returns (bestTile, timeMs)
        var bestTile = tileSizes[0]
        var bestTime = Double.MAX_VALUE

        for (tile in tileSizes) {
            val (tm, tn, tk) = tile.toTuple3()
            val start = System.nanoTime()
            mmaGemmCpu(a, b, a.gemm(b), tm, tn, tk)
            val elapsed = (System.nanoTime() - start) / 1_000_000.0
            if (elapsed < bestTime) {
                bestTime = elapsed
                bestTile = tile
            }
        }

        return bestTile to bestTime
    }
}

private fun IntArray.toTuple3(): Triple<Int, Int, Int> = Triple(this[0], this[1], this[2])