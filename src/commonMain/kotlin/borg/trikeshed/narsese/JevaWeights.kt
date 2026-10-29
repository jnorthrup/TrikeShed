package borg.trikeshed.narsese

import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import borg.trikeshed.parse.*
import borg.trikeshed.userspace.MemoryMapping
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.channels.FileChannel
import borg.trikeshed.userspace.nio.channels.FileChannel.MapMode
import borg.trikeshed.userspace.nio.file.StandardOpenOption
import borg.trikeshed.userspace.nio.file.spi.FileOperations

/** One inference owner retains channels and bounded mmap windows; tensor values are decoded on access. */
class JevaWeights private constructor(
    val files: FileOperations,
    val directory: String,
    val channels: (String) -> FileChannel,
    val maxCacheBytes: Long,
) : JevaTensors {
    enum class Dtype(val bytes: Int) { F32(4), BF16(2), F16(2) }
    data class Parameter(val channel: FileChannel, val shape: Tensor, val dtype: Dtype, val offset: Long, val bytes: Int)
    data class Window(val channel: FileChannel, val offset: Long)

    private val opened = LinkedHashMap<String, FileChannel>()
    private val parameters = LinkedHashMap<String, Parameter>()
    private val cache = LinkedHashMap<Window, MemoryMapping>()
    private val scalar = ByteArray(4)
    private var buffer = ByteArray(0)
    private val receipts = mutableListOf<Map<String, Any?>>()
    private val texts = LinkedHashMap<String, Map<String, Any>>()
    private var content: String? = null
    private var closed = false
    /** Logical mapped extent, not an RSS guarantee; the operating system manages page residency. */
    final override var mappedBytes: Long = 0
        private set
    val names: Set<String> get() { checkOpen(); return parameters.keys.toSet() }
    /** Header/index provenance is intentionally distinct from a digest of every weight byte. */
    val provenance: Map<String, Any?> get() { checkOpen(); return mapOf("kind" to "safetensors-metadata-v1", "files" to receipts.toList()) }
    val metadataIdentity: String get() = ContentId.of(jsonOf(provenance).encodeToByteArray()).hex
    val identity: String get() = contentIdentity()

    /** Ordered block-content identity, not a standard whole-file SHA256; at most 1 MiB read storage per block. */
    fun contentIdentity(check: () -> Unit = {}): String {
        check()
        checkOpen()
        content?.let { return it }
        val manifest = mutableListOf<Map<String, Any>>()
        for ((relative, channel) in opened.entries.sortedBy { it.key }) {
            val size = channel.size()
            require(size in 8..SHARD_BYTES) { "Jeva shard size changed outside its bounds" }
            val blocks = mutableListOf<String>()
            var offset = 0L
            while (offset < size) {
                check()
                val bytes = read(channel, offset, ByteArray(minOf(BLOCK_BYTES.toLong(), size - offset).toInt()), check)
                blocks += ContentId.of(bytes).hex
                offset += bytes.size
            }
            manifest += mapOf("path" to relative, "bytes" to size, "blocks" to blocks)
        }
        val value = mapOf("kind" to "safetensors-block-sha256-v1", "block_bytes" to BLOCK_BYTES,
            "weights" to manifest, "texts" to texts.entries.sortedBy { it.key }.associate { it.key to it.value })
        return ContentId.of(jsonOf(value).encodeToByteArray()).hex.also { check(); content = it }
    }

    override operator fun get(name: String): MetaSeries<Tensor, Float> {
        checkOpen()
        val parameter = requireNotNull(parameters[name]) { "Missing Jeva tensor: $name" }
        return Weight(name, parameter)
    }

    private inner class Weight(val name: String, val parameter: Parameter) : JevaWeight {
        override val a: Tensor get() = parameter.shape
        override val b: (Tensor) -> Float = ::invoke
        override val size: Int = parameter.bytes / parameter.dtype.bytes

        operator fun invoke(index: Tensor): Float {
            checkOpen()
            val at = tensorOffset(parameter.shape, index) * parameter.dtype.bytes
            val from = at / windowBytes * windowBytes
            mapping(parameter, from).read((at - from).toLong(), scalar, 0, parameter.dtype.bytes)
            return value(parameter.dtype, scalar, 0).also {
                require(it.isFinite()) { "Non-finite Jeva tensor $name value" }
            }
        }

        override fun read(offset: Int, dst: FloatArray, start: Int, length: Int, check: () -> Unit) {
            checkOpen()
            require(offset >= 0 && offset <= size && length >= 0 && length <= size - offset) {
                "Jeva weight range exceeds tensor $name"
            }
            require(start >= 0 && start <= dst.size && length <= dst.size - start) {
                "Jeva weight range exceeds destination"
            }
            check()
            if (length == 0) return
            val dtype = parameter.dtype
            // Shape validation bounds the multiplication; bytes and decode storage never span the full model.
            val requested = minOf(length * dtype.bytes, BUFFER_BYTES, windowBytes)
            if (buffer.size < requested) {
                var capacity = 4
                while (capacity < requested) capacity *= 2
                buffer = ByteArray(minOf(capacity, BUFFER_BYTES))
            }
            var done = 0
            while (done < length) {
                check()
                checkOpen()
                val at = (offset + done) * dtype.bytes
                val from = at / windowBytes * windowBytes
                val mapped = mapping(parameter, from)
                val within = at - from
                val count = minOf(length - done, buffer.size / dtype.bytes,
                    (mapped.length.toInt() - within) / dtype.bytes)
                mapped.read(within.toLong(), buffer, 0, count * dtype.bytes)
                for (i in 0 until count) {
                    if (i and 4095 == 0) check()
                    val decoded = value(dtype, buffer, i * dtype.bytes)
                    require(decoded.isFinite()) { "Non-finite Jeva tensor $name value" }
                    dst[start + done + i] = decoded
                }
                done += count
            }
            check()
        }
    }

    private val windowBytes: Int get() = (minOf(WINDOW_BYTES, maxCacheBytes) / 4 * 4).toInt()

    private fun mapping(parameter: Parameter, from: Int): MemoryMapping {
        val window = Window(parameter.channel, parameter.offset + from)
        return cache.remove(window)?.also { cache[window] = it } ?: run {
            val length = minOf(windowBytes, parameter.bytes - from).toLong()
            while (mappedBytes + length > maxCacheBytes && cache.isNotEmpty()) {
                val first = cache.entries.first()
                first.value.close()
                mappedBytes -= first.value.length
                cache.remove(first.key)
            }
            parameter.channel.map(MapMode.READ_ONLY, window.offset, length).also {
                cache[window] = it
                mappedBytes += it.length
            }
        }
    }

    /** Bounded configuration/tokenizer read through a temporary positioned channel. */
    fun text(relative: String, maxBytes: Int = HEADER_BYTES, check: () -> Unit = {}): String {
        check()
        checkOpen()
        require(maxBytes > 0)
        val channel = channels(path(relative))
        try {
            val size = channel.size()
            require(size in 1..maxBytes.toLong()) { "Jeva text $relative exceeds its byte bound" }
            val bytes = read(channel, 0, ByteArray(size.toInt()), check)
            val text = bytes.decodeToString(throwOnInvalidSequence = true)
            val receipt = mapOf("bytes" to size, "sha256" to ContentId.of(bytes).hex)
            if (texts.put(relative, receipt) != receipt) content = null
            check()
            return text
        } finally { channel.close() }
    }

    override fun close() {
        closed = true
        parameters.clear(); texts.clear(); content = null
        buffer = ByteArray(0)
        var failure: Throwable? = null
        val mappings = cache.entries.iterator()
        while (mappings.hasNext()) {
            val mapping = mappings.next().value
            try {
                mapping.close()
                mappedBytes -= mapping.length
                mappings.remove()
            } catch (error: Throwable) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
        }
        val descriptors = opened.entries.iterator()
        while (descriptors.hasNext()) {
            val channel = descriptors.next().value
            try {
                channel.close()
                descriptors.remove()
            } catch (error: Throwable) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }

    private fun checkOpen() { check(!closed) { "Jeva weights are closed" } }

    private fun path(relative: String): String {
        require(relative.isNotBlank() && relative.length <= 4096 && '\\' !in relative && ':' !in relative &&
            relative.split('/').all { it.isNotEmpty() && it != "." && it != ".." }) { "Invalid Jeva relative path: $relative" }
        return files.resolvePath(directory, relative)
    }

    private fun shard(relative: String, check: () -> Unit): Map<String, Parameter> {
        check()
        val filename = path(relative)
        val channel = channels(filename)
        opened[relative] = channel
        val size = channel.size()
        require(size in 8..SHARD_BYTES) { "Invalid Jeva safetensors size: $relative ($size)" }
        val length = read(channel, 0, ByteArray(8), check)
        var headerSize = 0L
        for (i in 0..7) {
            val byte = length[i].toLong() and 255
            require(i < 7 || byte < 128) { "Jeva header length exceeds signed range" }
            headerSize = headerSize or (byte shl (i * 8))
        }
        require(headerSize in 2..HEADER_BYTES.toLong() && headerSize <= size - 8) { "Invalid Jeva safetensors header length" }
        val header = read(channel, 8, ByteArray(headerSize.toInt()), check)
        val fields = objectMap(reifyStrict(header.decodeToString(throwOnInvalidSequence = true)), "Jeva header")
        val dataAt = 8 + headerSize
        val entries = LinkedHashMap<String, Parameter>()
        for ((name, value) in fields) {
            check()
            if (name == "__metadata__") {
                val metadata = objectMap(value, "Jeva metadata")
                require(metadata.values.all { it is String }) { "Jeva metadata values must be strings" }
                continue
            }
            require(name.isNotBlank()) { "Empty Jeva tensor name" }
            val descriptor = objectMap(value, "Jeva tensor $name")
            require(descriptor.keys.toSet() == setOf("dtype", "shape", "data_offsets")) { "Invalid Jeva tensor $name descriptor fields" }
            val dtype = Dtype.entries.firstOrNull { it.name == descriptor["dtype"] }
                ?: error("Unsupported Jeva tensor $name dtype: ${descriptor["dtype"]}")
            val dims = descriptor["shape"] as? List<*> ?: error("Missing Jeva tensor $name shape")
            require(dims.size <= 8) { "Jeva tensor $name rank exceeds eight" }
            val shape = IntArray(dims.size) { integer(dims[it], "Jeva tensor extent", Int.MAX_VALUE.toLong()).toInt() }.toSeries()
            val bytes = tensorSize(shape).toLong() * dtype.bytes
            require(bytes <= Int.MAX_VALUE) { "Jeva tensor $name exceeds the byte-buffer bound" }
            val offsets = descriptor["data_offsets"] as? List<*> ?: error("Missing Jeva tensor $name offsets")
            require(offsets.size == 2) { "Invalid Jeva tensor $name offsets" }
            val from = integer(offsets[0], "Jeva tensor offset", size - dataAt)
            val to = integer(offsets[1], "Jeva tensor offset", size - dataAt)
            require(to >= from && to - from == bytes) { "Jeva tensor $name offsets disagree with its shape" }
            entries[name] = Parameter(channel, shape, dtype, dataAt + from, bytes.toInt())
        }
        var end = dataAt
        for ((name, parameter) in entries.entries.sortedWith(compareBy({ it.value.offset }, { it.value.bytes }))) {
            require(parameter.offset == end) { "Jeva tensor $name overlaps or leaves a data hole" }
            end += parameter.bytes
        }
        require(end == size) { "Jeva safetensors data length disagrees with its header" }
        receipts += mapOf("path" to filename, "bytes" to size, "modified" to files.lastModified(filename),
            "header_sha256" to ContentId.of(header).hex)
        return entries
    }

    companion object {
        const val HEADER_BYTES = 16_777_216
        const val SHARD_BYTES = 34_359_738_368L
        const val BLOCK_BYTES = 1_048_576
        const val WINDOW_BYTES = 67_108_864L
        const val BUFFER_BYTES = 4_194_304

        fun open(files: FileOperations, directory: String,
                 channels: (String) -> FileChannel = { FileChannel.open(it, StandardOpenOption.READ) },
                 maxCacheBytes: Long = 536_870_912L, check: () -> Unit = {}): JevaWeights {
            check()
            require(maxCacheBytes >= 4) { "Jeva mapped window budget must hold at least one F32 value" }
            val weights = JevaWeights(files, directory, channels, maxCacheBytes)
            try {
                val indexText = weights.text("backbone_full/model.safetensors.index.json", check = check)
                val index = objectMap(reifyStrict(indexText), "Jeva shard index")
                val mapping = objectMap(index["weight_map"], "Jeva weight_map")
                require(mapping.isNotEmpty()) { "Empty Jeva weight_map" }
                val shards = LinkedHashMap<String, MutableSet<String>>()
                for ((name, value) in mapping) {
                    check()
                    require(name.isNotBlank() && value is String && '/' !in value && value.endsWith(".safetensors")) { "Invalid Jeva shard mapping" }
                    shards.getOrPut(value) { mutableSetOf() }.add(name)
                }
                weights.receipts += mapOf("path" to weights.path("backbone_full/model.safetensors.index.json"),
                    "index_sha256" to ContentId.of(indexText.encodeToByteArray()).hex)
                for ((shard, expected) in shards) {
                    val parameters = weights.shard("backbone_full/$shard", check)
                    require(parameters.keys == expected) { "Jeva shard $shard tensors differ from weight_map" }
                    for ((name, parameter) in parameters) if (name.startsWith("language_model.")) {
                        require(weights.parameters.put(name, parameter) == null) { "Duplicate Jeva tensor: $name" }
                    }
                }
                require(weights.parameters.isNotEmpty()) { "Jeva checkpoint has no language_model tensors" }
                val head = weights.shard("head.safetensors", check)
                require(head.isNotEmpty()) { "Jeva head.safetensors contains no parameters" }
                for ((name, parameter) in head) {
                    require(weights.parameters.put(name, parameter) == null) { "Duplicate Jeva head tensor: $name" }
                }
                check()
                return weights
            } catch (failure: Throwable) {
                try { weights.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                throw failure
            }
        }

        private fun objectMap(value: Any?, name: String): Map<String, Any?> {
            require(value is Map<*, *> && value.keys.all { it is String } && value.keys.toSet().size == value.size) { "$name must be an object without duplicate keys" }
            @Suppress("UNCHECKED_CAST")
            return value as Map<String, Any?>
        }

        private fun integer(value: Any?, name: String, maximum: Long): Long {
            val number = (value as? Number)?.toDouble() ?: error("Missing or non-numeric $name")
            require(number.isFinite() && number >= 0 && number <= maximum.toDouble() && number == number.toLong().toDouble()) { "Invalid $name" }
            return number.toLong()
        }

        private fun read(channel: FileChannel, offset: Long, bytes: ByteArray, check: () -> Unit): ByteArray {
            var done = 0
            while (done < bytes.size) {
                val count = minOf(BLOCK_BYTES, bytes.size - done)
                val buffer = ByteBuffer(bytes, done, count)
                while (buffer.hasRemaining()) {
                    check()
                    val position = buffer.position()
                    val n = channel.read(buffer, offset + done + position)
                    require(n > 0 && n <= count - position && buffer.position() == position + n) { "Truncated Jeva file at ${offset + done + position}" }
                }
                done += count
            }
            check()
            return bytes
        }

        private fun value(dtype: Dtype, bytes: ByteArray, at: Int): Float {
            val bits = (bytes[at].toInt() and 255) or ((bytes[at + 1].toInt() and 255) shl 8)
            return when (dtype) {
                Dtype.F32 -> Float.fromBits(bits or ((bytes[at + 2].toInt() and 255) shl 16) or
                    ((bytes[at + 3].toInt() and 255) shl 24))
                Dtype.BF16 -> Float.fromBits(bits shl 16)
                Dtype.F16 -> half(bits)
            }
        }

        private fun half(bits: Int): Float {
            val sign = (bits and 0x8000) shl 16
            val exponent = bits ushr 10 and 31
            var mantissa = bits and 1023
            if (exponent == 0) {
                if (mantissa == 0) return Float.fromBits(sign)
                var shift = 0
                while (mantissa and 1024 == 0) { mantissa = mantissa shl 1; shift++ }
                return Float.fromBits(sign or ((113 - shift) shl 23) or ((mantissa and 1023) shl 13))
            }
            return Float.fromBits(sign or ((if (exponent == 31) 255 else exponent + 112) shl 23) or (mantissa shl 13))
        }
    }
}
