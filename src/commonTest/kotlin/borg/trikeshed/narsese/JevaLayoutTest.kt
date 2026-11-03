package borg.trikeshed.narsese

import borg.trikeshed.lib.*
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.userspace.*
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.channels.*
import borg.trikeshed.userspace.nio.file.spi.InMemoryFileOperations
import kotlin.test.*

class JevaLayoutTest {
    private fun tensor(dtype: String, data: ByteArray, name: String = "language_model.weight"): ByteArray {
        val header = jsonOf(_m[name j _m["dtype" j dtype, "shape" j _l[1],
            "data_offsets" j _l[0, data.size]]]).encodeToByteArray()
        return ByteArray(8 + header.size + data.size).also { out ->
            for (i in 0..7) out[i] = (header.size.toLong() ushr (i * 8)).toByte()
            header.copyInto(out, 8); data.copyInto(out, 8 + header.size)
        }
    }
    private class Fixture(backbone: ByteArray, indexed: Boolean) {
        val files = InMemoryFileOperations()
        val opened = mutableListOf<Bytes>()
        init {
            files.write("/weights/backbone_full/model.safetensors", backbone)
            files.write("/weights/head.safetensors", tensorHead())
            if (indexed) files.write("/weights/backbone_full/model.safetensors.index.json",
                jsonOf(_m["weight_map" j _m["language_model.weight" j "model.safetensors"]]))
        }
        fun channel(path: String): FileChannel = Bytes(files.readAllBytes(path)).also(opened::add)
        fun open() = JevaWeights.open(files, "/weights", ::channel)
        private fun tensorHead(): ByteArray {
            val header = "{\"context_proj.weight\":{\"dtype\":\"F32\",\"shape\":[1],\"data_offsets\":[0,4]}}".encodeToByteArray()
            return ByteArray(8 + header.size + 4).also { out ->
                for (i in 0..7) out[i] = (header.size.toLong() ushr (i * 8)).toByte()
                header.copyInto(out, 8)
            }
        }
    }

    @Test fun singleFileAndIndexUseIdenticalUnquantizedDecoding() {
        for ((dtype, bytes) in s_["F32" j _a[0.toByte(), 0.toByte(), 0x80.toByte(), 0x3f.toByte()],
            "BF16" j _a[0x80.toByte(), 0x3f.toByte()], "F16" j _a[0.toByte(), 0x3c.toByte()]].view) {
            val blob = tensor(dtype, bytes)
            val indexed = Fixture(blob, true); val single = Fixture(blob, false)
            val a = indexed.open(); val b = single.open()
            try {
                assertEquals(a.names, b.names)
                assertEquals(1f, a["language_model.weight"].b(shapeOf(0)))
                assertEquals(1f, b["language_model.weight"].b(shapeOf(0)))
                assertTrue(b.provenance.toString().contains("model.safetensors"))
                assertFalse(b.provenance.toString().contains("index_sha256"))
            } finally { a.close(); b.close() }
            assertTrue(indexed.opened.all { !it.isOpen() } && single.opened.all { !it.isOpen() })
        }
    }

    @Test fun malformedIndexDoesNotFallThroughToExistingSingleFile() {
        val f = Fixture(tensor("F32", ByteArray(4)), true)
        f.files.write("/weights/backbone_full/model.safetensors.index.json", "{invalid}")
        val failure = assertFailsWith<IllegalArgumentException> { f.open() }
        assertTrue(failure.message.orEmpty().contains("/weights"))
        assertEquals(1, f.opened.size)
        assertTrue(f.opened.all { !it.isOpen() && it.maps == 0 })
    }

    @Test fun packedQuantizationAndMissingHeadFailWithoutMappingOrRuntimeSwitch() {
        val q4 = Fixture(tensor("U32", ByteArray(4)), false)
        val quantized = assertFailsWith<IllegalArgumentException> { q4.open() }
        assertTrue(quantized.message.orEmpty().contains("packed MLX/Q4"))
        assertTrue(q4.opened.all { !it.isOpen() && it.maps == 0 })
        val configured = Fixture(tensor("F32", ByteArray(4)), false)
        configured.files.write("/weights/config.yaml", "model:\n  pooling: last\n")
        configured.files.write("/weights/backbone_full/config.json", "{\"quantization\":{\"bits\":4}}")
        val declaredQuantization = assertFailsWith<IllegalArgumentException> {
            JevaInference.load(JevaConfig(model = "/weights"), configured.files, channels = configured::channel)
        }
        assertTrue(declaredQuantization.message.orEmpty().contains("Quantized backbone configuration"))
        assertTrue(configured.opened.all { !it.isOpen() && it.maps == 0 })
        val missing = Fixture(tensor("F32", ByteArray(4)), false)
        missing.files.deleteRecursively("/weights/head.safetensors")
        val absent = assertFailsWith<IllegalArgumentException> { missing.open() }
        assertTrue(absent.message.orEmpty().contains("head.safetensors"))
        assertTrue(missing.opened.all { !it.isOpen() && it.maps == 0 })
    }

    @Test fun metadataDepthAndMissingLayoutGiveBoundedLocalConfigurationErrors() {
        assertFailsWith<IllegalArgumentException> { JevaWeights.metadata("[".repeat(33) + "0" + "]".repeat(33), "fixture") }
        assertEquals("((", JevaWeights.metadata("{\"value\":\"((\"}", "fixture")["value"])
        val config = JevaConfig(model = "LIQIIIII/Jeva-4B")
        val failure = assertFailsWith<IllegalArgumentException> {
            JevaInference.load(config, InMemoryFileOperations(), channels = { error("missing local fixture: $it") })
        }
        val message = failure.message.orEmpty()
        assertTrue(message.contains("runtime=kotlin") && message.contains("device='cpu'"))
        assertTrue(message.contains(config.model) && message.contains("tokenizer/tokenizer.json"))
        assertTrue(message.contains("repository ID") && message.contains("no Python switch or download"))
    }

    private class Bytes(val bytes: ByteArray) : FileChannel() {
        var maps = 0
        var open = true
        override fun size() = bytes.size.toLong()
        override fun read(dst: ByteBuffer, position: Long): Int {
            val n = minOf(dst.remaining(), bytes.size - position.toInt())
            if (n <= 0) return -1
            repeat(n) { dst.put(bytes[position.toInt() + it]) }; return n
        }
        override fun map(mode: MapMode, position: Long, size: Long): MemoryMapping {
            maps++
            return MemoryMapping(object : MemoryMappingAccess {
                override val address = 0x1_0000_0000L
                override val length = size
                override fun get(offset: Long) = bytes[(position + offset).toInt()]
                override fun set(offset: Long, value: Byte): Unit = error("readonly")
                override fun read(offset: Long, dst: ByteArray, start: Int, length: Int) {
                    bytes.copyInto(dst, start, (position + offset).toInt(), (position + offset).toInt() + length)
                }
                override fun write(offset: Long, src: ByteArray, start: Int, length: Int): Unit = error("readonly")
                override fun sync(offset: Long, length: Long, flags: Int) = Unit
                override fun close() = Unit
            }, 1)
        }
        override fun isOpen() = open
        override fun close() { open = false }
        override fun read(dst: ByteBuffer): Int = error("unused")
        override fun read(dsts: Array<out ByteBuffer>): Long = error("unused")
        override fun write(src: ByteBuffer): Int = error("readonly")
        override fun write(src: ByteBuffer, position: Long): Int = error("readonly")
        override fun write(srcs: Array<out ByteBuffer>): Long = error("readonly")
        override fun position(): Long = error("unused")
        override fun position(newPosition: Long): FileChannel = error("unused")
        override fun truncate(size: Long): FileChannel = error("readonly")
        override fun force(metaData: Boolean) = Unit
        override fun transferTo(position: Long, count: Long, target: WritableByteChannel): Long = error("unused")
        override fun transferFrom(src: ReadableByteChannel, position: Long, count: Long): Long = error("unused")
        override fun lock(position: Long, size: Long, shared: Boolean): FileLock = error("unused")
        override fun lock(): FileLock = error("unused")
        override fun tryLock(position: Long, size: Long, shared: Boolean): FileLock? = error("unused")
        override fun tryLock(): FileLock? = error("unused")
        override fun read(dsts: Array<out ByteBuffer>, offset: Int, length: Int): Long = error("unused")
        override fun write(srcs: Array<out ByteBuffer>, offset: Int, length: Int): Long = error("readonly")
        override fun implCloseChannel() { open = false }
        override fun begin() = Unit
        override fun end(completed: Boolean) = Unit
    }
}
