package borg.trikeshed.userspace.nio.platform.spi

import kotlin.coroutines.CoroutineContext

/** Synchronous CPU matrix operations, called by an existing serial work owner. */
interface MatrixOperations : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<MatrixOperations>
    override val key: CoroutineContext.Key<*> get() = Key
    fun open(): MatrixSession
}

/** Owned native packing storage. GEMM is computation, not an I/O completion. */
interface MatrixSession {
    val identity: String
    /** Row-major C = A B, or A Bᵀ. Offsets/strides are measured in Float elements. */
    fun gemm(m: Int, n: Int, k: Int,
             a: FloatArray, aOffset: Int, lda: Int,
             b: FloatArray, bOffset: Int, ldb: Int, transposeB: Boolean,
             c: FloatArray, cOffset: Int, ldc: Int)
    fun close()
}
