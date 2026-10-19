package borg.trikeshed.narsese

import borg.trikeshed.lib.*
import borg.trikeshed.userspace.nio.platform.spi.MatrixSession

/** Bounded Float32 GEMM over borrowed tensor weights; one weight tile serves every token. */
class JevaMatrix(val session: MatrixSession? = null, val tileRows: Int = 128, val batchRows: Int = 256) {
    init { require(tileRows in 1..128 && batchRows in 1..256) }
    val identity: String get() = session?.identity ?: "kotlin-f32-gemm-1"

    fun read(weight: MetaSeries<Tensor, Float>, offset: Int, dst: FloatArray,
             start: Int = 0, length: Int = dst.size - start, check: () -> Unit = {}) {
        require(offset >= 0 && length >= 0 && offset.toLong() + length <= tensorSize(weight.a))
        require(start >= 0 && start.toLong() + length <= dst.size)
        val reader = weight as? JevaWeight
        if (reader != null) reader.read(offset, dst, start, length, check)
        else for (i in 0 until length) {
            if (i and 4095 == 0) check()
            dst[start + i] = weight.b(tensorIndex(weight.a, offset + i))
        }
    }

    fun linear(weight: MetaSeries<Tensor, Float>, input: FloatArray, rows: Int,
               bias: MetaSeries<Tensor, Float>? = null, check: () -> Unit = {}): FloatArray {
        require(weight.a.size == 2 && rows > 0)
        val output = weight.a[0]; val width = weight.a[1]
        require(output > 0 && width > 0 && rows.toLong() * width == input.size.toLong())
        val result = FloatArray(tensorSize(shapeOf(rows, output)))
        val tile = FloatArray(tensorSize(shapeOf(minOf(tileRows, output), width)))
        val biases = bias?.let { JevaMath.dimensions(it, output); FloatArray(output).also { dst -> read(it, 0, dst, check = check) } }
        var column = 0
        while (column < output) {
            check()
            val count = minOf(tileRows, output - column)
            read(weight, column * width, tile, length = count * width, check = check)
            var row = 0
            while (row < rows) {
                check()
                val batch = minOf(batchRows, rows - row)
                multiply(batch, count, width, input, row * width, width,
                    tile, 0, width, true, result, row * output + column, output, check)
                row += batch
            }
            column += count
        }
        if (biases != null) for (row in 0 until rows) {
            check()
            for (c in 0 until output) result[row * output + c] += biases[c]
        }
        check()
        return result
    }

    fun gemm(m: Int, n: Int, k: Int, a: FloatArray, b: FloatArray,
             transposeB: Boolean = false, check: () -> Unit = {}): FloatArray {
        require(m > 0 && n > 0 && k > 0)
        require(m.toLong() * k == a.size.toLong() && n.toLong() * k == b.size.toLong())
        val result = FloatArray(tensorSize(shapeOf(m, n)))
        var row = 0
        while (row < m) {
            check()
            val batch = minOf(batchRows, m - row)
            multiply(batch, n, k, a, row * k, k, b, 0, if (transposeB) k else n,
                transposeB, result, row * n, n, check)
            row += batch
        }
        check()
        return result
    }

    fun multiply(m: Int, n: Int, k: Int, a: FloatArray, aOffset: Int, lda: Int,
                 b: FloatArray, bOffset: Int, ldb: Int, transposeB: Boolean,
                 c: FloatArray, cOffset: Int, ldc: Int, check: () -> Unit) {
        check()
        if (session != null) session.gemm(m, n, k, a, aOffset, lda, b, bOffset, ldb, transposeB, c, cOffset, ldc)
        else for (row in 0 until m) {
            check()
            for (col in 0 until n) {
                var sum = 0f
                for (inner in 0 until k) {
                    if (inner and 4095 == 0) check()
                    sum += a[aOffset + row * lda + inner] *
                        b[bOffset + if (transposeB) col * ldb + inner else inner * ldb + col]
                }
                c[cOffset + row * ldc + col] = sum
            }
        }
        check()
    }

    fun close() { session?.close() }

    companion object { val reference = JevaMatrix() }
}
