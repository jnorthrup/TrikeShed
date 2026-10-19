package borg.trikeshed.narsese

import borg.trikeshed.lib.*
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.userspace.MemoryMapping
import borg.trikeshed.userspace.MemoryMappingAccess
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.channels.*
import borg.trikeshed.userspace.nio.file.spi.InMemoryFileOperations
import kotlin.test.*

class JevaWeightsTest {
    private fun descriptor(dtype: String, shape: List<Int>, from: Long, to: Long): Map<String, Any> =
        mapOf("dtype" to dtype, "shape" to shape, "data_offsets" to listOf(from, to))

    private fun pack(header: Map<String, Any?>, data: ByteArray): ByteArray = pack(jsonOf(header), data)
    private fun pack(json: String, data: ByteArray): ByteArray {
        val header = json.encodeToByteArray()
        return ByteArray(8 + header.size + data.size).also { bytes ->
            for (i in 0..7) bytes[i] = (header.size.toLong() ushr (8 * i)).toByte()
            header.copyInto(bytes, 8)
            data.copyInto(bytes, 8 + header.size)
        }
    }

    private fun floats(vararg values: Float): ByteArray = ByteArray(values.size * 4).also { bytes ->
        for (i in values.indices) for (j in 0..3) bytes[i * 4 + j] = (values[i].toBits() ushr (j * 8)).toByte()
    }

    private fun fixture(backbone: ByteArray = pack(mapOf(
        "language_model.weight" to descriptor("F32", listOf(2), 0, 8)), floats(1f, 2f)),
                        index: String = jsonOf(mapOf("weight_map" to mapOf("language_model.weight" to "a.safetensors")))): Fixture =
        Fixture(linkedMapOf(
            "/weights/backbone_full/model.safetensors.index.json" to index.encodeToByteArray(),
            "/weights/backbone_full/a.safetensors" to backbone,
            "/weights/head.safetensors" to pack(mapOf("context_proj.weight" to descriptor("F32", listOf(1), 0, 4)), floats(3f)),
        ))

    @Test
    fun mapsOnlyAccessedValuesAndDecodesF32Bf16AndF16() {
        val body = floats(1.25f, -0.0f) + byteArrayOf(0x80.toByte(), 0x3f, 0x20, 0xc0.toByte()) +
            byteArrayOf(0, 0x3c, 0, 0xc0.toByte(), 1, 0, 0, 0x80.toByte())
        val header = linkedMapOf(
            "language_model.f32" to descriptor("F32", listOf(2), 0, 8),
            "language_model.bf16" to descriptor("BF16", listOf(2), 8, 12),
            "language_model.f16" to descriptor("F16", listOf(4), 12, 20),
        )
        val index = jsonOf(mapOf("weight_map" to header.keys.associateWith { "a.safetensors" }))
        val fixture = fixture(pack(header, body), index)
        val weights = fixture.open()
        val shard = fixture.opened.first { it.first.endsWith("a.safetensors") }.second
        val reads = shard.transfers.size
        val f32 = weights["language_model.f32"]
        assertTrue(shard.mappings.isEmpty())
        assertEquals(1.25f, f32.b(shapeOf(0)))
        assertEquals((-0.0f).toBits(), f32.b(shapeOf(1)).toBits())
        assertEquals(1f, weights["language_model.bf16"].b(shapeOf(0)))
        assertEquals(-2.5f, weights["language_model.bf16"].b(shapeOf(1)))
        val f16 = weights["language_model.f16"]
        assertEquals(1f, f16.b(shapeOf(0)))
        assertEquals(-2f, f16.b(shapeOf(1)))
        assertEquals(Float.fromBits(0x33800000), f16.b(shapeOf(2)))
        assertEquals((-0.0f).toBits(), f16.b(shapeOf(3)).toBits())
        assertEquals(reads, shard.transfers.size, "numeric values must come from mappings, not read copies")
        assertEquals(20L, weights.mappedBytes)
        weights.close()
        assertTrue(shard.mappings.all { it.closed })
        assertTrue(fixture.opened.all { !it.second.isOpen() })
        assertFailsWith<IllegalStateException> { f32.b(shapeOf(0)) }
        assertFailsWith<IllegalStateException> { weights["language_model.f32"] }
        weights.close()
    }

    @Test
    fun mappedWindowBudgetEvictsAndRetainedOracleRemaps() {
        val fixture = fixture()
        val weights = fixture.open(4)
        val shard = fixture.opened.first { it.first.endsWith("a.safetensors") }.second
        val tensor = weights["language_model.weight"]
        assertEquals(1f, tensor.b(shapeOf(0)))
        assertEquals(4L, weights.mappedBytes)
        assertEquals(2f, tensor.b(shapeOf(1)))
        assertTrue(shard.mappings[0].closed)
        assertEquals(1f, tensor.b(shapeOf(0)))
        assertEquals(3, shard.mappings.size)
        assertTrue(shard.mappings[1].closed)
        assertEquals(4L, weights.mappedBytes)
        weights.close()
        assertEquals(0L, weights.mappedBytes)
        assertTrue(shard.mappings.all { it.closed })
    }

    @Test
    fun bulkRangesMatchScalarBitsAcrossMappedWindowsWithoutTouchingDestinationMargins() {
        val f32 = intArrayOf(0, Int.MIN_VALUE, 1, 0x007fffff, 0x00800000, 0x3fa00000, 0xc0200000.toInt(), 0x7f7fffff)
        val bf16 = intArrayOf(0, 0x8000, 1, 0x007f, 0x0080, 0x3f80, 0xc020, 0x7f7f)
        val f16 = intArrayOf(0, 0x8000, 1, 0x03ff, 0x0400, 0x3c00, 0xc000, 0x7bff)
        val halfBits = intArrayOf(0, Int.MIN_VALUE, 0x33800000, 0x387fc000, 0x38800000, 0x3f800000, 0xc0000000.toInt(), 0x477fe000)
        for ((dtype, bits, expected) in listOf(Triple("F32", f32, f32),
            Triple("BF16", bf16, IntArray(bf16.size) { bf16[it] shl 16 }), Triple("F16", f16, halfBits))) {
            val width = if (dtype == "F32") 4 else 2
            val bytes = ByteArray(bits.size * width) { (bits[it / width] ushr (it % width * 8)).toByte() }
            val fixture = fixture(pack(mapOf("language_model.weight" to descriptor(dtype, listOf(2, 4), 0, bytes.size.toLong())), bytes))
            val weights = fixture.open(12)
            val tensor = weights["language_model.weight"]
            val reader = assertIs<JevaWeight>(tensor)
            val shard = fixture.opened.first { it.first.endsWith("a.safetensors") }.second
            assertEquals(8, reader.size)
            for (i in expected.indices) assertEquals(expected[i], tensor.b(shapeOf(i / 4, i % 4)).toRawBits())
            val calls = shard.mappings.sumOf { it.reads.size }
            val transfers = shard.transfers.size
            val dst = FloatArray(10) { -99f }
            reader.read(1, dst, 2, 6)
            for (i in 0 until 6) assertEquals(expected[i + 1], dst[i + 2].toRawBits())
            for (i in intArrayOf(0, 1, 8, 9)) assertEquals(-99f, dst[i])
            assertEquals((width + 6 * width + 11) / 12, shard.mappings.sumOf { it.reads.size } - calls)
            assertEquals(transfers, shard.transfers.size, "bulk values must come from mmap, not positioned channel reads")
            assertTrue(weights.mappedBytes <= 12)
            val beforeEmpty = shard.mappings.sumOf { it.reads.size }
            reader.read(8, dst, dst.size, 0)
            assertEquals(beforeEmpty, shard.mappings.sumOf { it.reads.size })
            assertFailsWith<IllegalArgumentException> { reader.read(-1, dst, 0, 1) }
            assertFailsWith<IllegalArgumentException> { reader.read(9, dst, 0, 0) }
            assertFailsWith<IllegalArgumentException> { reader.read(7, dst, 0, Int.MAX_VALUE) }
            assertFailsWith<IllegalArgumentException> { reader.read(0, dst, -1, 1) }
            assertFailsWith<IllegalArgumentException> { reader.read(0, dst, Int.MAX_VALUE, 1) }
            assertFailsWith<IllegalArgumentException> { reader.read(0, dst, 0, -1) }
            weights.close()
            assertEquals(0L, weights.mappedBytes)
            assertTrue(shard.mappings.all { it.closed })
            assertFailsWith<IllegalStateException> { reader.read(0, dst, 0, 0) }
        }
    }

    @Test
    fun bulkReadSplitsAtScratchAndSixtyFourMiBMappingBoundaries() {
        val byteCount = JevaWeights.WINDOW_BYTES + JevaWeights.BUFFER_BYTES
        val header = mapOf("language_model.weight" to descriptor("F32", listOf((byteCount / 4).toInt()), 0, byteCount))
        val prefix = pack(header, ByteArray(0))
        val fixture = fixture()
        val shard = BytesChannel(prefix.size + byteCount) { position ->
            if (position < prefix.size) prefix[position.toInt()] else {
                val at = position - prefix.size
                val bits = 0x3f000000 or ((at / 4).toInt() and 0x007fffff)
                (bits ushr (at.toInt() % 4 * 8)).toByte()
            }
        }
        fixture.replacements["/weights/backbone_full/a.safetensors"] = shard
        val weights = fixture.open(JevaWeights.WINDOW_BYTES)
        val reader = assertIs<JevaWeight>(weights["language_model.weight"])
        val offset = ((JevaWeights.WINDOW_BYTES - JevaWeights.BUFFER_BYTES * 3L / 2) / 4).toInt()
        val dst = FloatArray((JevaWeights.BUFFER_BYTES * 2 + 32) / 4)
        val transfers = shard.transfers.size
        reader.read(offset, dst)
        for (i in dst.indices) assertEquals(0x3f000000 or ((offset + i) and 0x007fffff), dst[i].toRawBits())
        assertEquals(2, shard.mappings.size)
        assertEquals(3, shard.mappings.sumOf { it.reads.size })
        assertTrue(shard.mappings.flatMap { it.reads }.all { it.second <= JevaWeights.BUFFER_BYTES })
        assertTrue(shard.mappings.all { it.maxReadBuffer <= JevaWeights.BUFFER_BYTES })
        assertEquals(JevaWeights.BUFFER_BYTES, shard.mappings[0].reads[0].second)
        assertEquals(prefix.size.toLong() + JevaWeights.WINDOW_BYTES, shard.mappings[1].offset)
        assertTrue(shard.mappings[0].closed)
        assertEquals(JevaWeights.BUFFER_BYTES.toLong(), weights.mappedBytes)
        assertEquals(transfers, shard.transfers.size)
        weights.close()
        assertEquals(0L, weights.mappedBytes)
        assertTrue(shard.mappings.all { it.closed })
    }

    @Test
    fun failedBulkReadAndDeadlineRetainMappingOwnershipUntilCloseSucceeds() {
        val fixture = fixture()
        val weights = fixture.open(16)
        val tensor = weights["language_model.weight"]
        val reader = assertIs<JevaWeight>(tensor)
        assertEquals(1f, tensor.b(shapeOf(0)))
        val shard = fixture.opened.first { it.first.endsWith("a.safetensors") }.second
        val region = shard.mappings.single()
        val dst = FloatArray(2)
        region.readFailures = 1
        assertFailsWith<IllegalStateException> { reader.read(0, dst) }
        assertFalse(region.closed)
        assertEquals(8L, weights.mappedBytes)
        reader.read(0, dst)
        assertContentEquals(floatArrayOf(1f, 2f), dst)
        val expired = IllegalStateException("deadline fixture")
        var checks = 0
        assertSame(expired, assertFails { reader.read(0, dst, check = { if (++checks == 3) throw expired }) })
        assertFalse(region.closed)
        region.closeFailures = 1
        assertFailsWith<IllegalStateException> { weights.close() }
        assertEquals(8L, weights.mappedBytes)
        assertFailsWith<IllegalStateException> { reader.read(0, dst) }
        weights.close()
        assertTrue(region.closed)
        assertEquals(0L, weights.mappedBytes)
    }

    @Test
    fun bulkDecodingRejectsNonFiniteValuesForEveryStoredDtype() {
        for ((dtype, bytes) in listOf("F32" to floats(Float.NaN),
            "BF16" to byteArrayOf(0x80.toByte(), 0x7f), "F16" to byteArrayOf(1, 0x7c))) {
            val fixture = fixture(pack(mapOf("language_model.weight" to descriptor(dtype, listOf(1), 0, bytes.size.toLong())), bytes))
            val weights = fixture.open()
            val reader = assertIs<JevaWeight>(weights["language_model.weight"])
            assertFailsWith<IllegalArgumentException> { reader.read(0, FloatArray(1)) }
            assertTrue(weights.mappedBytes > 0)
            weights.close()
            assertEquals(0L, weights.mappedBytes)
        }
    }

    @Test
    fun failedUnmapAndChannelCloseRetainOwnershipForRetry() {
        val fixture = fixture()
        val weights = fixture.open(16)
        val tensor = weights["language_model.weight"]
        assertEquals(1f, tensor.b(shapeOf(0)))
        assertEquals(3f, weights["context_proj.weight"].b(shapeOf(0)))
        val shard = fixture.opened.first { it.first.endsWith("a.safetensors") }.second
        val head = fixture.opened.first { it.first.endsWith("head.safetensors") }.second
        val failedMapping = shard.mappings.single()
        val releasedMapping = head.mappings.single()
        failedMapping.closeFailures = 1
        shard.closeFailures = 1
        val failure = assertFailsWith<IllegalStateException> { weights.close() }
        assertEquals(1, failure.suppressedExceptions.size)
        assertFalse(failedMapping.closed)
        assertTrue(releasedMapping.closed)
        assertTrue(shard.isOpen())
        assertFalse(head.isOpen())
        assertEquals(8L, weights.mappedBytes)
        assertFailsWith<IllegalStateException> { tensor.b(shapeOf(0)) }
        assertFailsWith<IllegalStateException> { weights["language_model.weight"] }
        weights.close()
        assertTrue(failedMapping.closed)
        assertFalse(shard.isOpen())
        assertEquals(0L, weights.mappedBytes)
        assertEquals(2, failedMapping.closeAttempts)
        assertEquals(2, shard.closeAttempts)
        assertEquals(1, releasedMapping.closeAttempts)
        assertEquals(1, head.closeAttempts)
        weights.close()
        assertEquals(2, failedMapping.closeAttempts)
        assertEquals(2, shard.closeAttempts)
    }

    @Test
    fun longOffsetsPastFourGiBAreNotNarrowedOrMaterialized() {
        val header = linkedMapOf<String, Any>(
            "vision.a" to descriptor("F16", listOf(750_000_000), 0, 1_500_000_000),
            "vision.b" to descriptor("F16", listOf(750_000_000), 1_500_000_000, 3_000_000_000),
            "vision.c" to descriptor("F16", listOf(750_000_000), 3_000_000_000, 4_500_000_000),
            "language_model.weight" to descriptor("F32", listOf(1), 4_500_000_000, 4_500_000_004),
        )
        val prefix = pack(header, ByteArray(0))
        val index = jsonOf(mapOf("weight_map" to header.keys.associateWith { "a.safetensors" }))
        val fixture = fixture(index = index)
        val at = prefix.size + 4_500_000_000L
        val data = floats(7.5f)
        fixture.replacements["/weights/backbone_full/a.safetensors"] = BytesChannel(at + 4) { position ->
            when {
                position < prefix.size -> prefix[position.toInt()]
                position in at until at + 4 -> data[(position - at).toInt()]
                else -> error("Unrequested vision payload was accessed")
            }
        }
        val weights = fixture.open()
        assertEquals(2, weights.names.size)
        assertFailsWith<IllegalArgumentException> { weights["vision.a"] }
        assertEquals(7.5f, weights["language_model.weight"].b(shapeOf(0)))
        val shard = fixture.replacements.getValue("/weights/backbone_full/a.safetensors")
        assertEquals(at, shard.mappings.single().offset)
        assertEquals(4L, shard.mappings.single().length)
        weights.close()
    }

    @Test
    fun malformedHeadersAndShardIndexFailBeforeMappingAndCloseChannels() {
        val badHeaders = listOf(
            jsonOf(mapOf("language_model.weight" to descriptor("F32", listOf(2), 0, 4))),
            jsonOf(mapOf("language_model.weight" to descriptor("F32", listOf(-1), 0, 8))),
            jsonOf(mapOf("language_model.weight" to descriptor("F64", listOf(1), 0, 8))),
            "{\"language_model.weight\":${jsonOf(descriptor("F32", listOf(2), 0, 8))},\"language_model.weight\":${jsonOf(descriptor("F32", listOf(2), 0, 8))}}",
            "{\"language_model.weight\":{\"dtype\":\"F32\",\"dtype\":\"F16\",\"shape\":[2],\"data_offsets\":[0,8]}}",
            jsonOf(mapOf("language_model.weight" to descriptor("F32", listOf(1), 4, 8))),
            jsonOf(mapOf("language_model.weight" to descriptor("F32", listOf(Int.MAX_VALUE), 0, 8))),
        )
        for (header in badHeaders) {
            val fixture = fixture(pack(header, floats(1f, 2f)))
            assertFails { fixture.open() }
            assertTrue(fixture.opened.all { !it.second.isOpen() && it.second.mappings.isEmpty() })
        }
        for (name in listOf("../escape.safetensors", "/absolute.safetensors", "a.safetensors", "a.pt")) {
            val tensor = if (name == "a.safetensors") "language_model.missing" else "language_model.weight"
            val fixture = fixture(index = jsonOf(mapOf("weight_map" to mapOf(tensor to name))))
            assertFails { fixture.open() }
            assertTrue(fixture.opened.all { !it.second.isOpen() })
        }
    }

    @Test
    fun missingHeadAndTruncatedHeaderFailClosedWithoutFallback() {
        val fixture = fixture()
        fixture.bytes.remove("/weights/head.safetensors")
        assertFails { fixture.open() }
        assertTrue(fixture.opened.all { !it.second.isOpen() })
        val truncated = fixture()
        truncated.replacements["/weights/backbone_full/a.safetensors"] = BytesChannel(100) { error("truncated") }
        assertFails { truncated.open() }
        assertTrue(truncated.opened.all { !it.second.isOpen() })
    }

    @Test
    fun blockContentIdentityChangesForPayloadWithIdenticalHeaders() {
        val first = fixture()
        val second = fixture(pack(mapOf("language_model.weight" to descriptor("F32", listOf(2), 0, 8)), floats(1f, 9f)))
        val a = first.open()
        val b = second.open()
        assertEquals(a.metadataIdentity, b.metadataIdentity)
        assertNotEquals(a.contentIdentity(), b.contentIdentity())
        val reads = first.opened.sumOf { it.second.transfers.size }
        assertEquals(a.contentIdentity(), a.identity)
        assertEquals(reads, first.opened.sumOf { it.second.transfers.size })
        first.bytes["/weights/config.json"] = "{\"version\":1}".encodeToByteArray()
        val before = a.contentIdentity()
        assertEquals("{\"version\":1}", a.text("config.json", 100))
        assertNotEquals(before, a.contentIdentity())
        assertTrue(first.opened.all { (_, channel) -> channel.transfers.all { it.second <= JevaWeights.BLOCK_BYTES } })
        a.close(); b.close()
    }

    @Test
    fun textBoundsPathsAndNonFiniteValuesAreRejected() {
        val fixture = fixture(pack(mapOf("language_model.weight" to descriptor("F32", listOf(1), 0, 4)), floats(Float.NaN)))
        val weights = fixture.open()
        assertFailsWith<IllegalArgumentException> { weights["language_model.weight"].b(shapeOf(0)) }
        assertFailsWith<IllegalArgumentException> { weights.text("../config.json") }
        fixture.bytes["/weights/config.json"] = ByteArray(8)
        assertFailsWith<IllegalArgumentException> { weights.text("config.json", 4) }
        assertFalse(fixture.opened.last().second.isOpen())
        weights.close()
    }

    private class Fixture(val bytes: MutableMap<String, ByteArray>) {
        val opened = mutableListOf<Pair<String, BytesChannel>>()
        val replacements = mutableMapOf<String, BytesChannel>()
        fun open(budget: Long = 536_870_912): JevaWeights = JevaWeights.open(InMemoryFileOperations(), "/weights", { path ->
            val channel = replacements[path] ?: bytes.getValue(path).let { data -> BytesChannel(data.size.toLong()) { data[it.toInt()] } }
            opened += path to channel
            channel
        }, budget)
    }

    private class Region(val offset: Long, override val length: Long, val byte: (Long) -> Byte) : MemoryMappingAccess {
        override val address = 0x1_0000_0000L
        var closed = false
        var closeFailures = 0
        var closeAttempts = 0
        var readFailures = 0
        val reads = mutableListOf<Pair<Long, Int>>()
        var maxReadBuffer = 0
        override fun get(offset: Long) = byte(this.offset + offset)
        override fun set(offset: Long, value: Byte): Unit = error("read only")
        override fun read(offset: Long, dst: ByteArray, start: Int, length: Int) {
            reads += offset to length
            maxReadBuffer = maxOf(maxReadBuffer, dst.size)
            if (readFailures > 0) { readFailures--; error("read fixture failure") }
            repeat(length) { dst[start + it] = get(offset + it) }
        }
        override fun write(offset: Long, src: ByteArray, start: Int, length: Int): Unit = error("read only")
        override fun sync(offset: Long, length: Long, flags: Int): Unit = error("read only")
        override fun close() {
            closeAttempts++
            if (closeFailures > 0) { closeFailures--; error("unmap fixture failure") }
            closed = true
        }
    }

    private class BytesChannel(val length: Long, val byte: (Long) -> Byte) : FileChannel() {
        var open = true
        var closeFailures = 0
        var closeAttempts = 0
        val transfers = mutableListOf<Pair<Long, Int>>()
        val mappings = mutableListOf<Region>()
        override fun read(dst: ByteBuffer, position: Long): Int {
            val count = minOf(31L, dst.remaining().toLong(), length - position).toInt()
            if (count <= 0) return -1
            transfers += position to count
            repeat(count) { dst.put(byte(position + it)) }
            return count
        }
        override fun map(mode: MapMode, position: Long, size: Long): MemoryMapping {
            assertSame(MapMode.READ_ONLY, mode)
            require(position >= 0 && size > 0 && position <= length - size)
            return Region(position, size, byte).also { mappings += it }.let { MemoryMapping(it, 1) }
        }
        override fun size() = length
        override fun close() {
            closeAttempts++
            if (closeFailures > 0) { closeFailures--; error("channel fixture failure") }
            open = false
        }
        override fun isOpen() = open
        override fun implCloseChannel() = close()
        override fun begin() {}
        override fun end(completed: Boolean) {}
        override fun read(dst: ByteBuffer): Int = error("positioned read required")
        override fun read(dsts: Array<out ByteBuffer>, offset: Int, length: Int): Long = error("unused")
        override fun read(dsts: Array<out ByteBuffer>): Long = error("unused")
        override fun write(src: ByteBuffer): Int = error("read only")
        override fun write(src: ByteBuffer, position: Long): Int = error("read only")
        override fun write(srcs: Array<out ByteBuffer>, offset: Int, length: Int): Long = error("read only")
        override fun write(srcs: Array<out ByteBuffer>): Long = error("read only")
        override fun position(): Long = error("unused")
        override fun position(newPosition: Long): FileChannel = error("unused")
        override fun truncate(size: Long): FileChannel = error("read only")
        override fun force(metaData: Boolean) = error("read only")
        override fun transferTo(position: Long, count: Long, target: WritableByteChannel): Long = error("unused")
        override fun transferFrom(src: ReadableByteChannel, position: Long, count: Long): Long = error("read only")
        override fun lock(position: Long, size: Long, shared: Boolean): FileLock = error("unused")
        override fun lock(): FileLock = error("unused")
        override fun tryLock(position: Long, size: Long, shared: Boolean): FileLock? = error("unused")
        override fun tryLock(): FileLock? = error("unused")
    }
}
