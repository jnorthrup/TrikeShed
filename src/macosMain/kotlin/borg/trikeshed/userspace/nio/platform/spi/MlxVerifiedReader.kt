@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package borg.trikeshed.userspace.nio.platform.spi

import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.parse.reifyStrict
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.channels.FileChannel
import borg.trikeshed.userspace.nio.file.StandardOpenOption
import borg.trikeshed.util.toLowerHex
import kotlinx.cinterop.*
import mlxc.*
import platform.CoreCrypto.CC_SHA256
import platform.posix.*
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference

/** Native SHA-256 for a bounded reader block. */
fun mlxBlockDigest(bytes: ByteArray): String {
    require(bytes.isNotEmpty())
    val digest = ByteArray(32)
    bytes.usePinned { source -> digest.usePinned { result ->
        checkNotNull(CC_SHA256(source.addressOf(0), bytes.size.toUInt(), result.addressOf(0).reinterpret()))
    } }
    return digest.toLowerHex()
}

/** The digest describes the exact blocks admitted to the lazy MLX reader. */
class MlxVerifiedReader internal constructor(
    path: String, admit: (Long) -> Unit, private val retire: (Long) -> Unit,
    private val closeFile: (FileChannel) -> Unit, loadCheck: () -> Unit,
) {
    constructor(path: String, loadCheck: () -> Unit = {}) : this(path, {}, {}, { it.close() }, loadCheck)
    companion object {
        const val BLOCK_BYTES = 1 shl 20
    }

    private val mutex = nativeHeap.alloc<pthread_mutex_t>()
    private lateinit var channel: FileChannel
    private lateinit var label: CPointer<ByteVar>
    private val failure = AtomicReference<String?>(null)
    private val closeFailure = AtomicReference<Throwable?>(null)
    private val released = AtomicBoolean(false)
    private var cursor = 0L
    private var size = 0L
    private val blocks = ArrayList<String>()
    private var headerBytes: ByteArray? = null
    val identity: String

    init {
        var initialized = false
        var opened: FileChannel? = null
        var text: CPointer<ByteVar>? = null
        try {
            loadCheck()
            check(pthread_mutex_init(mutex.ptr, null) == 0) { "reader mutex initialization failed" }
            initialized = true
            channel = FileChannel.open(path, StandardOpenOption.READ).also { opened = it }
            label = (strdup("verified safetensors") ?: error("reader label allocation failed")).also { text = it }
            size = channel.size()
            require(size in 8..34_359_738_368L) { "Invalid safetensors size" }
            admit(size)
            var offset = 0L
            while (offset < size) {
                loadCheck()
                val bytes = block(offset)
                blocks.add(mlxBlockDigest(bytes))
                offset += bytes.size
            }
            loadCheck()
            identity = ContentId.of(jsonOf(_m["kind" j "safetensors-block-sha256-v1",
                "block_bytes" j BLOCK_BYTES, "bytes" j size, "blocks" j blocks]).encodeToByteArray()).hex
        } catch (error: Throwable) {
            try { opened?.close() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
            finally {
                text?.let { free(it) }
                if (initialized) pthread_mutex_destroy(mutex.ptr)
                nativeHeap.free(mutex)
            }
            throw error
        }
    }

    private fun <T> locked(block: () -> T): T {
        check(pthread_mutex_lock(mutex.ptr) == 0) { "reader mutex lock failed" }
        try { return block() } finally { pthread_mutex_unlock(mutex.ptr) }
    }

    private fun block(offset: Long): ByteArray {
        val bytes = ByteArray(minOf(BLOCK_BYTES.toLong(), size - offset).toInt())
        val buffer = ByteBuffer(bytes)
        while (buffer.hasRemaining()) {
            val n = channel.read(buffer, offset + buffer.position())
            check(n > 0) { "Safetensors file ended while reading" }
        }
        return bytes
    }

    private fun fail(error: Throwable) { failure.compareAndSet(null, error.message ?: "Safetensors reader failed") }
    fun check() { val message = failure.load(); check(message == null) { message ?: "Safetensors reader failed" } }
    internal fun checkClose() { closeFailure.load()?.let { throw it } }
    fun good(): Boolean = failure.load() == null

    /** Validate the exact verified bytes before the native parser or named lookup sees them. */
    internal fun header(names: Array<out String>, loadCheck: () -> Unit = {}) {
        fun bytes(offset: Long, length: Int): ByteArray = ByteArray(length).also { result ->
            if (length > 0) result.usePinned { read(it.addressOf(0), length.toULong(), offset.toULong()) }
            check()
            loadCheck()
        }
        fun number(value: Any?, maximum: Long): Long {
            val n = (value as? Number)?.toDouble()
            require(n != null && n.isFinite() && n >= 0 && n <= maximum.toDouble() && n % 1.0 == 0.0) {
                "Invalid safetensors integer"
            }
            return n.toLong()
        }
        fun fields(value: Any?): Map<*, *> {
            require(value is Map<*, *> && value.keys.all { it is String } && value.keys.toSet().size == value.size) {
                "Invalid or duplicate safetensors fields"
            }
            return value
        }
        val prefix = bytes(0, 8)
        var length = 0L
        for (i in 0..7) {
            val byte = prefix[i].toLong() and 255
            require(i < 7 || byte < 128) { "Safetensors header length exceeds signed range" }
            length = length or (byte shl (i * 8))
        }
        require(length in 2..16_777_216L && length <= size - 8) { "Invalid safetensors header length" }
        val header = bytes(8, length.toInt())
        val entries = fields(reifyStrict(header.decodeToString(throwOnInvalidSequence = true)))
        val dataSize = size - 8 - length
        val ranges = mutableListOf<Join<Long, Long>>()
        for ((rawName, value) in entries) {
            loadCheck()
            val name = rawName as String
            if (name == "__metadata__") {
                val metadata = fields(value)
                require(metadata.values.all { it is String }) { "Invalid safetensors metadata" }
                continue
            }
            require(name.isNotBlank() && name.length <= 1024) { "Invalid safetensors tensor name" }
            val descriptor = fields(value)
            require(descriptor.keys == _s["dtype", "shape", "data_offsets"]) { "Invalid safetensors tensor descriptor" }
            val width = when (descriptor["dtype"]) {
                "BOOL", "U8", "I8" -> 1L
                "U16", "I16", "F16", "BF16" -> 2L
                "U32", "I32", "F32" -> 4L
                "U64", "I64", "C64" -> 8L
                else -> error("Unsupported safetensors dtype for $name")
            }
            val shape = descriptor["shape"] as? List<*> ?: error("Invalid safetensors shape for $name")
            require(shape.size <= 8) { "Safetensors tensor rank exceeds eight" }
            val extents = shape.map { number(it, Int.MAX_VALUE.toLong()) }
            var elements = if (extents.any { it == 0L }) 0L else 1L
            for (extent in extents) {
                require(extent == 0L || elements <= dataSize / width / extent) { "Safetensors tensor exceeds file size" }
                elements *= extent
            }
            val offsets = descriptor["data_offsets"] as? List<*> ?: error("Invalid safetensors offsets for $name")
            require(offsets.size == 2) { "Invalid safetensors offsets for $name" }
            val from = number(offsets[0], dataSize)
            val to = number(offsets[1], dataSize)
            require(to >= from && to - from == elements * width) { "Safetensors shape and byte range differ for $name" }
            ranges.add(from j to)
        }
        for (name in names) require(name in entries && name != "__metadata__") { "Missing safetensors tensor: $name" }
        var end = 0L
        for (range in ranges.sortedWith(compareBy({ it.a }, { it.b }))) {
            require(range.a == end) { "Safetensors data ranges overlap or leave a hole" }
            end = range.b
        }
        require(end == dataSize) { "Safetensors data length differs from header" }
        // Native parsing must see the admitted header even if the file changes after preflight.
        headerBytes = prefix + header
    }

    fun tell(): ULong = try { locked { cursor.toULong() } } catch (error: Throwable) { fail(error); 0uL }
    fun seek(offset: Long, whence: Int) {
        try {
            locked {
                val base = when (whence) { SEEK_SET -> 0L; SEEK_CUR -> cursor; SEEK_END -> size; else -> error("Invalid seek") }
                require((offset >= 0 && base <= Long.MAX_VALUE - offset) || (offset < 0 && offset >= -base))
                cursor = (base + offset).also { require(it in 0..size) }
            }
        } catch (error: Throwable) { fail(error) }
    }

    /** No exception may cross the C ABI. A failed read is latched and its output is zeroed. */
    fun read(destination: CPointer<ByteVar>?, count: ULong, offset: ULong?) {
        if (count == 0uL) return
        try {
            requireNotNull(destination)
            locked {
                check()
                val start = offset?.also { require(it <= Long.MAX_VALUE.toULong()) }?.toLong() ?: cursor
                require(count <= Long.MAX_VALUE.toULong() && start >= 0 && start <= size && count.toLong() <= size - start)
                var source = start
                var copied = 0L
                val end = start + count.toLong()
                while (source < end) {
                    val header = headerBytes
                    if (header != null && source < header.size) {
                        val inside = source.toInt()
                        val n = minOf(header.size - inside, (end - source).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                        header.usePinned { memcpy(destination + copied, it.addressOf(inside), n.toULong()) }
                        source += n; copied += n
                        continue
                    }
                    val blockStart = source / BLOCK_BYTES * BLOCK_BYTES
                    val bytes = block(blockStart)
                    check(mlxBlockDigest(bytes) == blocks[(blockStart / BLOCK_BYTES).toInt()]) { "Safetensors content changed" }
                    val inside = (source - blockStart).toInt()
                    val n = minOf(bytes.size - inside, (end - source).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                    bytes.usePinned { memcpy(destination + copied, it.addressOf(inside), n.toULong()) }
                    source += n; copied += n
                }
                if (offset == null) cursor = end
            }
        } catch (error: Throwable) {
            fail(error)
            if (destination != null) memset(destination, 0, count)
        }
    }

    fun label(): CPointer<ByteVar> = label

    fun close() {
        if (released.compareAndSet(false, true)) {
            var problem: Throwable? = null
            try { closeFile(channel) } catch (caught: Throwable) { problem = caught }
            try { free(label); pthread_mutex_destroy(mutex.ptr); nativeHeap.free(mutex) } catch (caught: Throwable) {
                if (problem == null) problem = caught else if (problem !== caught) problem.addSuppressed(caught)
            }
            // UringFileChannel.close revokes the channel even on failure. The accounting is
            // for usable verified readers, not for an OS descriptor or resident memory.
            if (!channel.isOpen()) try { retire(size) } catch (caught: Throwable) {
                if (problem == null) problem = caught else if (problem !== caught) problem.addSuppressed(caught)
            }
            problem?.let {
                closeFailure.compareAndSet(null, it)
                fail(it)
                throw it
            }
        }
    }

    fun closed(): Boolean = released.load()
}

private fun COpaquePointer.reader() = asStableRef<MlxVerifiedReader>().get()

/** MLX retains the reader behind lazy arrays and calls free only after its final reference. */
fun MlxVerifiedReader.handle(): CValue<mlx_io_reader_> {
    val ref = StableRef.create(this)
    val vtable = cValue<mlx_io_vtable_> {
        is_open = staticCFunction { pointer: COpaquePointer? -> pointer != null }
        good = staticCFunction { pointer: COpaquePointer? -> try { pointer?.reader()?.good() ?: false } catch (_: Throwable) { false } }
        tell = staticCFunction { pointer: COpaquePointer? -> try { pointer?.reader()?.tell() ?: 0uL } catch (_: Throwable) { 0uL } }
        seek = staticCFunction { pointer: COpaquePointer?, offset: Long, whence: Int -> try { pointer?.reader()?.seek(offset, whence) } catch (_: Throwable) {} }
        read = staticCFunction { pointer: COpaquePointer?, data: CPointer<ByteVar>?, count: ULong ->
            try { if (pointer == null) { if (data != null) memset(data, 0, count) } else pointer.reader().read(data, count, null) }
            catch (_: Throwable) { if (data != null) memset(data, 0, count) }
        }
        read_at_offset = staticCFunction { pointer: COpaquePointer?, data: CPointer<ByteVar>?, count: ULong, offset: ULong ->
            try { if (pointer == null) { if (data != null) memset(data, 0, count) } else pointer.reader().read(data, count, offset) }
            catch (_: Throwable) { if (data != null) memset(data, 0, count) }
        }
        write = null
        label = staticCFunction { pointer: COpaquePointer? -> try { pointer?.reader()?.label() } catch (_: Throwable) { null } }
        free = staticCFunction { pointer: COpaquePointer? ->
            if (pointer != null) {
                val owned = pointer.asStableRef<MlxVerifiedReader>()
                try { owned.get().close() } catch (_: Throwable) {} finally { owned.dispose() }
            }
        }
    }
    val handle = try { mlx_io_reader_new(ref.asCPointer(), vtable) } catch (error: Throwable) {
        if (!closed()) try { close() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) } finally { ref.dispose() }
        throw error
    }
    if (handle.useContents { ctx } == null) {
        if (!closed()) try { close() } finally { ref.dispose() }
        error("mlx-c reader creation failed")
    }
    return handle
}
