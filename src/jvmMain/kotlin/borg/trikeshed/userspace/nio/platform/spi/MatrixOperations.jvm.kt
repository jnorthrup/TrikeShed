package borg.trikeshed.userspace.nio.platform.spi

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

/** Synchronous CPU BLAS; the calling inference lane owns each session and its native storage. */
class JvmMatrixOperations(val maxBytes: Long = MAX_BYTES) : MatrixOperations {
    init { require(maxBytes in 1..MAX_BYTES) { "Matrix native storage exceeds its 64 MiB bound" } }

    override fun open(): JvmMatrixSession = JvmMatrixSession(Accelerate.gemm, Accelerate.identity, maxBytes)

    companion object {
        const val MAX_BYTES = 64L * 1024 * 1024

        /** Unsupported hosts have no provider; a broken supported binding remains a visible failure. */
        fun available(maxBytes: Long = MAX_BYTES): JvmMatrixOperations? {
            if (!System.getProperty("os.name").lowercase().contains("mac")) return null
            Accelerate.gemm
            return JvmMatrixOperations(maxBytes)
        }
    }

    object Accelerate {
        const val LIBRARY = "/System/Library/Frameworks/Accelerate.framework/Accelerate"
        const val SYMBOL = "cblas_sgemm\$NEWLAPACK"
        val identity = "accelerate:$SYMBOL:lp64:float32:${System.getProperty("os.version")}:${System.getProperty("os.arch")}"
        val gemm: MethodHandle by lazy {
            val lookup = SymbolLookup.libraryLookup(LIBRARY, Arena.global())
            val address = lookup.find(SYMBOL).orElseThrow { UnsupportedOperationException("Accelerate $SYMBOL is unavailable") }
            val int = ValueLayout.JAVA_INT
            val float = ValueLayout.JAVA_FLOAT
            val pointer = ValueLayout.ADDRESS
            Linker.nativeLinker().downcallHandle(address, FunctionDescriptor.ofVoid(
                int, int, int, int, int, int, float, pointer, int, pointer, int, float, pointer, int))
        }
    }
}

/** Three reusable native spans; shared arenas permit sequential calls from different JVM threads. */
class JvmMatrixSession internal constructor(
    val function: MethodHandle,
    override val identity: String,
    val maxBytes: Long,
) : MatrixSession {
    init { require(maxBytes in 1..JvmMatrixOperations.MAX_BYTES) }
    class Buffer(val arena: Arena, val segment: MemorySegment)
    private val buffers = arrayOfNulls<Buffer>(3)
    var closed = false
        private set
    val allocatedBytes: Long get() = buffers.sumOf { it?.segment?.byteSize() ?: 0L }

    @Synchronized
    override fun gemm(m: Int, n: Int, k: Int, a: FloatArray, aOffset: Int, lda: Int,
                      b: FloatArray, bOffset: Int, ldb: Int, transposeB: Boolean,
                      c: FloatArray, cOffset: Int, ldc: Int) {
        check(!closed) { "Matrix session is closed" }
        require(m >= 0 && n >= 0 && k >= 0) { "Negative matrix extent" }
        val aBytes = span(a, aOffset, m, k, lda)
        val bBytes = span(b, bOffset, if (transposeB) n else k, if (transposeB) k else n, ldb)
        val cBytes = span(c, cOffset, m, n, ldc)
        require(aBytes + bBytes + cBytes <= maxBytes) { "Matrix native storage exceeds $maxBytes bytes" }
        if (m == 0 || n == 0) return
        if (k == 0) {
            repeat(m) { row -> c.fill(0f, cOffset + row * ldc, cOffset + row * ldc + n) }
            return
        }
        reserve(longArrayOf(aBytes, bBytes, cBytes))
        val left = requireNotNull(buffers[0]).segment
        val right = requireNotNull(buffers[1]).segment
        val result = requireNotNull(buffers[2]).segment
        MemorySegment.copy(MemorySegment.ofArray(a), aOffset.toLong() * 4, left, 0, aBytes)
        MemorySegment.copy(MemorySegment.ofArray(b), bOffset.toLong() * 4, right, 0, bBytes)
        function.invokeExact(101, 111, if (transposeB) 112 else 111, m, n, k,
            1f, left, lda, right, ldb, 0f, result, ldc)
        val output = MemorySegment.ofArray(c)
        repeat(m) { row ->
            MemorySegment.copy(result, row.toLong() * ldc * 4, output,
                (cOffset.toLong() + row.toLong() * ldc) * 4, n.toLong() * 4)
        }
    }

    private fun span(values: FloatArray, offset: Int, rows: Int, columns: Int, stride: Int): Long {
        require(offset >= 0 && offset <= values.size && stride >= maxOf(1, columns)) { "Invalid matrix offset or stride" }
        val count = if (rows == 0 || columns == 0) 0L else (rows.toLong() - 1) * stride + columns
        require(count <= values.size.toLong() - offset) { "Matrix span exceeds its array" }
        return count * 4
    }

    private fun reserve(required: LongArray) {
        val retained = LongArray(3) { maxOf(required[it], buffers[it]?.segment?.byteSize() ?: 0L) }
        val sizes = if (retained.sum() <= maxBytes) retained else required
        // Free every changed allocation before growing any, including transient storage in the bound.
        for (i in buffers.indices) if (buffers[i]?.segment?.byteSize() != sizes[i]) {
            buffers[i]?.arena?.close()
            buffers[i] = null
        }
        for (i in buffers.indices) if (buffers[i] == null && sizes[i] > 0) {
            val arena = Arena.ofShared()
            try { buffers[i] = Buffer(arena, arena.allocate(sizes[i], 64)) }
            catch (failure: Throwable) { arena.close(); throw failure }
        }
    }

    @Synchronized
    override fun close() {
        closed = true
        var failure: Throwable? = null
        for (i in buffers.indices) {
            try { buffers[i]?.arena?.close(); buffers[i] = null }
            catch (error: Throwable) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }
}
