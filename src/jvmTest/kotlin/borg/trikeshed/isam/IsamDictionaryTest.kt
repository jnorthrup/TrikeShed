package borg.trikeshed.isam

import borg.trikeshed.cursor.ColumnMeta
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.job.CasStore
import borg.trikeshed.lib.*
import borg.trikeshed.tilting.zran.*
import com.github.luben.zstd.Zstd
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class IsamDictionaryTest {
    val metadata = ColumnMeta("term", IOMemento.IoString)
    val terms = arrayOf("medical review: retained source; human admission required", "legal review: evidence scope; human admission required")
    fun rows(start: Int, count: Int): Cursor = count j { index: Int ->
        1 j { _: Int -> (terms[(start + index) % terms.size] as Any) j metadata.`↺` }
    }
    fun codec() = ZstdDictionaryCodec(terms.joinToString("\n").encodeToByteArray())
    fun operations(codec: IsamLeafCodec = ZstdLeafCodec, mode: IsamReadMode = IsamReadMode.CHANNEL) =
        UringIsamOperations(readMode = mode, leafBytes = 256, leafCodec = codec)

    inline fun directory(body: (Path) -> Unit) {
        val directory = Files.createTempDirectory("isam-dictionary-")
        try { body(directory) } finally {
            Files.list(directory).use { paths -> paths.forEach { Files.delete(it) } }
            Files.delete(directory)
        }
    }

    fun values(path: String, codec: IsamLeafCodec, mode: IsamReadMode = IsamReadMode.CHANNEL): List<String> {
        val file = IsamDataFile(path, operations = operations(codec, mode))
        try {
            file.open()
            return file.view.map { it[0].a as String }
        } finally { file.close() }
    }

    @Test fun dictionaryLeavesReopenAppendAndProjectThroughBothIoModes() = directory { directory ->
        val path = directory.resolve("terms.bin").toString()
        val codec = codec()
        val store = operations(codec)
        store.write(rows(0, 5), path, mapOf("term" to 64), false)
        store.append(rows(5, 7).view, path, mapOf("term" to 64), null, false)
        for (mode in IsamReadMode.entries) {
            assertEquals((0 until 12).map { terms[it % 2] }, values(path, codec(), mode))
            val file = IsamDataFile(path, operations = operations(codec(), mode))
            file.open()
            try { assertEquals(terms[1], file["term"][7][0].a) } finally { file.close() }
        }
        assertEquals(12, values(path, codec).size)
    }

    @Test fun missingOrWrongDictionaryFailsBeforeAppendAndLeavesBytesUntouched() = directory { directory ->
        val path = directory.resolve("terms.bin").toString()
        operations(codec()).write(rows(0, 5), path, mapOf("term" to 64), false)
        val before = Files.readAllBytes(Path.of(path))
        val meta = Files.readAllBytes(Path.of("$path.meta"))
        for (wrong in arrayOf(ZstdLeafCodec, ZstdDictionaryCodec("wrong dictionary".encodeToByteArray()))) {
            val failure = assertFailsWith<IllegalArgumentException> { values(path, wrong) }
            assertContains(failure.message.orEmpty(), codec().dictionary.value)
            assertFailsWith<IllegalArgumentException> {
                operations(wrong).append(rows(5, 1).view, path, mapOf("term" to 64), null, false)
            }
            assertContentEquals(before, Files.readAllBytes(Path.of(path)))
            assertContentEquals(meta, Files.readAllBytes(Path.of("$path.meta")))
        }
        assertEquals(5, values(path, codec()).size)
    }

    @Test fun defaultFormatRemainsPortableAndCannotBeMixedOnAppend() = directory { directory ->
        val path = directory.resolve("terms.bin").toString()
        operations().write(rows(0, 5), path, mapOf("term" to 64), false)
        val before = Files.readAllBytes(Path.of(path))
        assertEquals(5 * 64, ZstdDecoder().decompress(before).size)
        assertEquals(values(path, ZstdLeafCodec), values(path, codec()))
        assertFailsWith<IllegalArgumentException> {
            operations(codec()).append(rows(5, 1).view, path, mapOf("term" to 64), null, false)
        }
        assertContentEquals(before, Files.readAllBytes(Path.of(path)))
        operations().append(rows(5, 1).view, path, mapOf("term" to 64), null, false)
        assertEquals(6, values(path, ZstdLeafCodec).size)
    }

    @Test fun failedAppendRestoresDictionaryFenceAndSeekTable() = directory { directory ->
        val path = directory.resolve("terms.bin").toString()
        val codec = codec()
        val store = operations(codec)
        store.write(rows(0, 5), path, mapOf("term" to 64), false)
        val before = Files.readAllBytes(Path.of(path))
        var count = 0
        assertFailsWith<IllegalStateException> {
            store.append(rows(5, 16).view, path, mapOf("term" to 64), {
                if (++count == 10) error("source failed after a full leaf")
                it
            }, false)
        }
        assertContentEquals(before, Files.readAllBytes(Path.of(path)))
        assertEquals(5, values(path, codec).size)
        store.append(rows(5, 1).view, path, mapOf("term" to 64), null, false)
        assertEquals(6, values(path, codec).size)
    }

    @Test fun trainedDictionaryIsContentAddressedAndActuallyRequiredToDecode() {
        val samples = Array(128) { i -> "${terms[i % 2]} case=$i; party=${i % 7}; provenance=source-${i % 11}".encodeToByteArray() }
        val trained = ByteArray(1024)
        val length = Zstd.trainFromBuffer(samples, trained)
        check(!Zstd.isError(length)) { Zstd.getErrorName(length) }
        val bytes = trained.copyOf(length.toInt())
        val cas = CasStore.inMemory()
        val cid = cas.put(bytes)
        val codec = ZstdDictionaryCodec(requireNotNull(cas.get(cid)))
        assertEquals(cid, codec.dictionary)
        bytes.fill(0) // The codec owns an immutable copy of its input.
        val original = samples[17]
        val frame = codec.encoder()(original, original.size)
        assertTrue(Zstd.getDictIdFromFrame(frame) != 0L)
        assertFails { ZstdDecoder().decompress(frame) }
        assertFails { Zstd.decompress(frame, original.size) }
        val decoded = ByteArray(original.size)
        codec.decoder()(frame, decoded)
        assertContentEquals(original, decoded)
        assertFails { codec.decoder()(frame, ByteArray(original.size + 1)) }
        assertFails { codec.decoder()(frame.copyOf(frame.size - 1), decoded) }
    }

    @Test fun portableAndNativeCodecsInteroperateForRawAndTrainedDictionaries() {
        val samples = Array(128) { i -> "${terms[i % 2]} case=$i; party=${i % 7}; provenance=source-${i % 11}".encodeToByteArray() }
        val trained = ByteArray(1024)
        val length = Zstd.trainFromBuffer(samples, trained)
        check(!Zstd.isError(length)) { Zstd.getErrorName(length) }
        for (bytes in listOf(samples[17], trained.copyOf(length.toInt()))) {
            val portable = DictionaryLeafCodec(bytes)
            val native = ZstdDictionaryCodec(bytes)
            assertEquals(native.dictionary, portable.dictionary)
            val encode = portable.encoder()
            val decode = portable.decoder()
            for (source in listOf(byteArrayOf(), byteArrayOf(1), samples[17], samples[33],
                ByteArray(3 * ZSTD_BLOCK_MAX + 19) { samples[17][it % samples[17].size] })) {
                val nativeFrame = native.encoder()(source, source.size)
                val portableFrame = encode(source, source.size)
                val restored = ByteArray(source.size)
                decode(nativeFrame, restored)
                assertContentEquals(source, restored)
                native.decoder()(portableFrame, restored)
                assertContentEquals(source, restored)
                decode(portableFrame, restored)
                assertContentEquals(source, restored)
            }
        }
    }
}
