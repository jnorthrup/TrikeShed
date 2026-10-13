package borg.trikeshed.isam

import borg.trikeshed.couch.isam.FileBackedStringpool
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.util.oroboros.FileCasStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class CasLeafCodecTest {
    inline fun directory(body: (Path) -> Unit) {
        val root = Files.createTempDirectory("isam-cas-codec-")
        try { body(root) } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    fun codec(cas: CasStore, cid: ContentId? = null) = CasLeafCodec(cas, { ZstdDictionaryCodec(it) }, cid)
    fun rows(): Cursor = 8 j { row: Int -> 1 j { _: Int ->
        "retained evidence source-$row" j RecordMeta("term", IOMemento.IoString, 0, 64).`↺`
    } }
    fun operations(codec: IsamLeafCodec, mode: IsamReadMode = IsamReadMode.CHANNEL) =
        UringIsamOperations(readMode = mode, leafBytes = 256, leafCodec = codec)

    @Test fun reopenedCasResolvesHistoricalDictionariesByFileIdentity() = directory { root ->
        val casRoot = root.resolve("cas").toString()
        val cas = FileCasStore(JvmFileOperations(), casRoot)
        val cids = listOf("retained evidence source", "different evidence dictionary").map { cas.put(it.encodeToByteArray()) }
        for (i in cids.indices) operations(codec(cas, cids[i])).write(rows(), root.resolve("$i.bin").toString(), emptyMap(), false)
        operations(ZstdLeafCodec).write(rows(), root.resolve("portable.bin").toString(), emptyMap(), false)

        // New owner knows only the CAS location. Neither dictionary nor a live old codec is supplied.
        for (mode in IsamReadMode.entries) {
            val reopened = codec(FileCasStore(JvmFileOperations(), casRoot))
            for (name in listOf("0.bin", "1.bin", "portable.bin")) {
                val file = IsamDataFile(root.resolve(name).toString(), operations = operations(reopened, mode))
                file.open()
                try { assertEquals(rows().view.map { it[0].a }, file.view.map { it[0].a }) }
                finally { file.close() }
            }
        }
        val path = root.resolve("0.bin").toString()
        val before = Files.readAllBytes(Path.of(path))
        assertFailsWith<IllegalArgumentException> {
            operations(codec(cas, cids[1])).append(rows().view, path, emptyMap(), null, false)
        }
        assertContentEquals(before, Files.readAllBytes(Path.of(path)))
        operations(codec(cas, cids[0])).append(rows().view, path, emptyMap(), null, false)
        val file = IsamDataFile(path, operations = operations(codec(FileCasStore(JvmFileOperations(), casRoot))))
        file.open()
        try { assertEquals(16, file.size) } finally { file.close() }
    }

    @Test fun missingCorruptAndUnpublishedDictionariesCannotModifyFiles() = directory { root ->
        val bytes = "retained evidence source".encodeToByteArray()
        val cid = ContentId.of(bytes)
        var failSync = true
        val failing = object : CasStore() {
            override fun sync() { if (failSync) error("CAS sync failed") }
        }
        failing.put(bytes)
        val corrupt = object : CasStore() { override fun get(cid: ContentId) = byteArrayOf(7) }
        val path = root.resolve("protected.bin").toString()
        operations(ZstdLeafCodec).write(rows(), path, emptyMap(), false)
        val before = Files.readAllBytes(Path.of(path))
        val meta = Files.readAllBytes(Path.of("$path.meta"))
        for (cas in listOf(CasStore.inMemory(), corrupt, failing)) {
            val operations = operations(codec(cas, cid))
            assertFails { operations.write(rows(), path, emptyMap(), false) }
            assertFails { operations.append(rows().view, path, emptyMap(), null, false) }
            assertContentEquals(before, Files.readAllBytes(Path.of(path)))
            assertContentEquals(meta, Files.readAllBytes(Path.of("$path.meta")))
            val absent = root.resolve("absent.bin").toString()
            assertFails { operations.write(rows(), absent, emptyMap(), false) }
            assertFalse(Files.exists(Path.of(absent)))
            assertFalse(Files.exists(Path.of("$absent.meta")))
        }
        failSync = false
        val packed = root.resolve("packed.bin").toString()
        operations(codec(failing, cid)).write(rows(), packed, emptyMap(), false)
        for (cas in listOf(CasStore.inMemory(), corrupt)) {
            val file = IsamDataFile(packed, operations = operations(codec(cas)))
            try { assertFails { file.open() } } finally { file.close() }
        }
    }

    @Test fun pooledColumnsReopenWithTheOwnersCodecAndChannels() = directory { root ->
        val cas = FileCasStore(JvmFileOperations(), root.resolve("cas").toString())
        val cid = cas.put("retained evidence source".encodeToByteArray())
        val writeCodec = codec(cas, cid)
        val location = root.resolve("term.pool").toString()
        val pool = FileBackedStringpool(location, leafCodec = writeCodec)
        assertEquals(0, pool.put("retained evidence"))
        assertEquals(1, pool.put("source revision"))
        val meta = RecordMeta("term", IOMemento.IoInt, 0, 4).also { it.pool = pool }
        val rows: Cursor = 2 j { row: Int -> 1 j { _: Int -> row j meta.`↺` } }
        val path = root.resolve("terms.bin").toString()
        operations(writeCodec).write(rows, path, emptyMap(), false)
        val opened = mutableListOf<String>()
        val channels: IsamChannelFactory = { file, options -> opened.add(file); openIsamChannel(file, options) }
        val readCodec = codec(FileCasStore(JvmFileOperations(), root.resolve("cas").toString()))
        val file = IsamDataFile(path, operations = UringIsamOperations(channels, leafCodec = readCodec))
        file.open()
        try {
            val recovered = requireNotNull((file[0][0].b() as RecordMeta).pool)
            assertEquals("source revision", recovered[file[1][0].a as Int])
            assertEquals("retained evidence", file.metafile.constraints[0].pool!![0],
                "The public metadata projection must retain the same codec owner")
            assertTrue(location in opened, "Pool data must use the owner's channel factory")
            assertTrue("$location.meta" in opened, "Pool metadata must use the owner's channel factory")
        } finally { file.close() }
        val writable = FileBackedStringpool(location, leafCodec = codec(cas, cid))
        assertEquals(2, writable.put("human review"))
        assertEquals(0, writable.put("retained evidence"))
        operations(codec(cas, cid)).append(rows.view, path, emptyMap(), null, false)
        val reopened = FileBackedStringpool(location, leafCodec = readCodec)
        assertEquals("human review", reopened[2])
    }
}
