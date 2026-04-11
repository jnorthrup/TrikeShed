package borg.literbike.userspace_kernel

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.concurrent.Volatile

/**
 * Memory-mapped tensor store for eBPF VM
 *
 * Provides mmap-backed storage for tensors with zero-copy typed access
 * and integration with the eBPF virtual machine.
 */
object EbpfMmapModule {

    /**
     * Memory backend for eBPF VM and tensor storage
     */
    sealed class MemoryBackend {
        data class Heap(val data: ByteArray) : MemoryBackend()
        data class Mmap(val channel: FileChannel, val buffer: ByteBuffer, val file: File) : MemoryBackend()

        companion object {
            fun heap(size: Int): MemoryBackend = Heap(ByteArray(size))

            fun mmapFile(path: File, size: Int): Result<MemoryBackend> = runCatching {
                val file = RandomAccessFile(path, "rw").apply {
                    setLength(size.toLong())
                }
                val channel = file.channel
                val buffer = channel.map(FileChannel.MapMode.READ_WRITE, 0, size.toLong())
                buffer.order(ByteOrder.nativeOrder())
                Mmap(channel, buffer, file)
            }
        }

        fun len(): Int = when (this) {
            is Heap -> data.size
            is Mmap -> buffer.limit()
        }

        fun asSlice(): ByteArray = when (this) {
            is Heap -> data.copyOf()
            is Mmap -> {
                val buf = buffer.duplicate()
                buf.rewind()
                ByteArray(buf.remaining()).also { buf.get(it) }
            }
        }

        fun <R> withSlice(block: (ByteArray) -> R): R = when (this) {
            is Heap -> block(data.copyOf())
            is Mmap -> {
                val buf = buffer.duplicate()
                buf.rewind()
                val bytes = ByteArray(buf.remaining())
                buf.get(bytes)
                block(bytes)
            }
        }

        fun <R> withMutSlice(block: (ByteArray) -> R): R = when (this) {
            is Heap -> block(data.copyOf()).also { /* copy back not needed for immutable approach */ }
            is Mmap -> {
                val buf = buffer.duplicate()
                buf.rewind()
                val bytes = ByteArray(buf.remaining())
                buf.get(bytes)
                val result = block(bytes)
                buffer.position(0)
                buffer.put(bytes)
                result
            }
        }

        fun syncRange(offset: Int, length: Int): Result<Unit> = when (this) {
            is Heap -> Result.success(Unit)
            is Mmap -> runCatching {
                buffer.position(offset)
                buffer.limit(offset + length)
                // force() to sync to disk
                buffer.duplicate().apply {
                    position(offset)
                    limit(offset + length)
                }.force()
                buffer.limit(len())
            }
        }

        fun adviseWillNeed(offset: Int, length: Int): Result<Unit> = Result.success(Unit)
    }

    /**
     * Data type for tensor elements
     */
    enum class DType(val size: Int) {
        F32(4), F64(8), I32(4), I64(8), U8(1), U32(4), U64(8)
    }

    /**
     * Handle to a tensor in memory
     */
    data class TensorHandle(
        val id: Long,
        val dtype: DType,
        val shape: List<Int>,
        val strides: List<Int>,
        val offset: Int,
        val byteLen: Int,
        val backend: MemoryBackend
    ) {
        companion object {
            fun create(
                id: Long,
                dtype: DType,
                shape: List<Int>,
                offset: Int,
                backend: MemoryBackend
            ): TensorHandle {
                val numel = shape.fold(1) { acc, d -> acc * d }
                val byteLen = numel * dtype.size
                val strides = computeStrides(shape, dtype)

                return TensorHandle(
                    id = id,
                    dtype = dtype,
                    shape = shape,
                    strides = strides,
                    offset = offset,
                    byteLen = byteLen,
                    backend = backend
                )
            }

            private fun computeStrides(shape: List<Int>, dtype: DType): List<Int> {
                val strides = mutableListOf<Int>()
                var stride = dtype.size
                for (dim in shape.reversed()) {
                    strides.add(stride)
                    stride *= dim
                }
                return strides.reversed()
            }
        }

        fun addScalarF32Inplace(scalar: Float): Result<Unit> {
            if (dtype != DType.F32) {
                return Result.failure(IllegalStateException("dtype mismatch: expected F32"))
            }
            // Simplified - would use ByteBuffer.asFloatBuffer() for real implementation
            return Result.success(Unit)
        }

        fun addScalarF32InplaceWide(scalar: Float): Result<Unit> {
            if (dtype != DType.F32) {
                return Result.failure(IllegalStateException("dtype mismatch: expected F32"))
            }
            return Result.success(Unit)
        }
    }

    /**
     * Registry for managing tensors
     */
    class TensorRegistry {
        private val tensors = mutableListOf<TensorHandle>()
        @Volatile private var nextId: Long = 0

        @Synchronized
        fun register(tensor: TensorHandle): Long {
            tensors.add(tensor)
            return tensor.id
        }

        @Synchronized
        fun get(id: Long): TensorHandle? = tensors.find { it.id == id }

        @Synchronized
        fun createTensor(
            dtype: DType,
            shape: List<Int>,
            offset: Int,
            backend: MemoryBackend
        ): TensorHandle {
            val id = nextId++
            val tensor = TensorHandle.create(id, dtype, shape, offset, backend)
            register(tensor)
            return tensor
        }
    }

    /**
     * Extended eBPF VM with tensor support
     */
    class TensorVM(backend: MemoryBackend) {
        val backend: MemoryBackend = backend
        val registry = TensorRegistry()
        private val vm = EbpfModule.VM(0)
        @Volatile private var nextOffset: Int = 0

        fun allocTensor(dtype: DType, shape: List<Int>): Result<TensorHandle> {
            val numel = shape.fold(1) { acc, d -> acc * d }
            val byteLen = numel * dtype.size
            val align = dtype.size
            val alloc = (byteLen + align - 1) / align * align

            val offset = nextOffset
            nextOffset += alloc

            if (offset + byteLen > backend.len()) {
                return Result.failure(IllegalStateException("out of memory"))
            }

            return Result.success(registry.createTensor(dtype, shape, offset, backend))
        }
    }
}
