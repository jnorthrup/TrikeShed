package borg.trikeshed.userspace.nio.file.spi

import borg.trikeshed.job.ContentId
import borg.trikeshed.util.oroboros.FileCasStore
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DurableStoragePrimitivesTest {

    @Test
    fun `restart truncates a torn legacy tail before committed append`() = runBlocking {
        val directory = Files.createTempDirectory("append-wal-torn-").toFile()
        val walPath = File(directory, "events.wal")
        try {
            withContext(Dispatchers.IO) { JvmAppendWal(walPath).close() }
            val tornOffset = withContext(Dispatchers.IO) {
                RandomAccessFile(walPath, "rw").use { file ->
                    file.seek(file.length())
                    file.writeLegacy("legacy", "stable".encodeToByteArray())
                    val offset = file.filePointer
                    file.writeInt(4)
                    file.write("tail".encodeToByteArray())
                    file.writeInt(32)
                    file.write("partial".encodeToByteArray())
                    file.fd.sync()
                    offset
                }
            }

            val restarted = withContext(Dispatchers.IO) { JvmAppendWal(walPath) }
            try {
                val provenPrefix = withContext(Dispatchers.IO) { restarted.replay().toList() }
                assertEquals(listOf("legacy"), provenPrefix.map { it.first })
                restarted.append("fresh", "committed".encodeToByteArray())
            } finally {
                withContext(Dispatchers.IO) { restarted.close() }
            }

            withContext(Dispatchers.IO) {
                RandomAccessFile(walPath, "r").use { file ->
                    file.seek(tornOffset)
                    assertTrue(file.readInt() < 0, "new committed frame must replace the torn legacy tail")
                }
            }

            val reader = withContext(Dispatchers.IO) { JvmAppendWal(walPath) }
            val records = try {
                withContext(Dispatchers.IO) { reader.replay().toList() }
            } finally {
                withContext(Dispatchers.IO) { reader.close() }
            }
            assertEquals(listOf("legacy", "fresh"), records.map { it.first })
            assertEquals(listOf("stable", "committed"), records.map { it.second.decodeToString() })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `committed frame corruption blocks replay and append`() = runBlocking {
        val directory = Files.createTempDirectory("append-wal-crc-").toFile()
        val walPath = File(directory, "events.wal")
        try {
            val writer = withContext(Dispatchers.IO) { JvmAppendWal(walPath) }
            try {
                writer.append("checked", "payload".encodeToByteArray())
            } finally {
                withContext(Dispatchers.IO) { writer.close() }
            }

            withContext(Dispatchers.IO) {
                RandomAccessFile(walPath, "rw").use { file ->
                    file.seek(8L)
                    assertTrue(file.readInt() < 0)
                    val keyLength = file.readInt()
                    val payloadLength = file.readInt()
                    assertTrue(payloadLength > 0)
                    val payloadOffset = file.filePointer + keyLength
                    file.seek(payloadOffset)
                    val original = file.readUnsignedByte()
                    file.seek(payloadOffset)
                    file.writeByte(original xor 0x01)
                    file.fd.sync()
                }
            }
            val corruptLength = withContext(Dispatchers.IO) { walPath.length() }

            val reader = withContext(Dispatchers.IO) { JvmAppendWal(walPath) }
            try {
                val replayFailure = assertFailsWith<IllegalStateException> {
                    withContext(Dispatchers.IO) { reader.replay().toList() }
                }
                assertTrue(replayFailure.message.orEmpty().contains("CRC mismatch"))

                val appendFailure = assertFailsWith<IllegalStateException> {
                    reader.append("must-not-append", byteArrayOf(1))
                }
                assertTrue(appendFailure.message.orEmpty().contains("CRC mismatch"))
                assertEquals(corruptLength, withContext(Dispatchers.IO) { walPath.length() })
            } finally {
                withContext(Dispatchers.IO) { reader.close() }
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `multiple wal handles serialize complete frames`() = runBlocking {
        val directory = Files.createTempDirectory("append-wal-lock-").toFile()
        val walPath = File(directory, "events.wal")
        val handles = try {
            (0 until 4).map {
                async(Dispatchers.IO) { JvmAppendWal(walPath) }
            }.awaitAll()
        } catch (failure: Throwable) {
            directory.deleteRecursively()
            throw failure
        }

        try {
            (0 until 128).map { ordinal ->
                async(Dispatchers.Default) {
                    handles[ordinal % handles.size].append(
                        key = "key-$ordinal",
                        payload = "payload-$ordinal".encodeToByteArray(),
                    )
                }
            }.awaitAll()
        } finally {
            withContext(Dispatchers.IO) { handles.forEach(JvmAppendWal::close) }
        }

        try {
            val reader = withContext(Dispatchers.IO) { JvmAppendWal(walPath) }
            val records = try {
                withContext(Dispatchers.IO) { reader.replay().toList() }
            } finally {
                withContext(Dispatchers.IO) { reader.close() }
            }
            assertEquals(128, records.size)
            assertEquals((0 until 128).map { "key-$it" }.toSet(), records.map { it.first }.toSet())
            records.forEach { (key, payload) ->
                assertEquals("payload-${key.removePrefix("key-")}", payload.decodeToString())
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `cas put atomically repairs a corrupt object`() {
        val root = Files.createTempDirectory("file-cas-repair-")
        val fileOps = JvmFileOperations()
        val cas = FileCasStore(fileOps, root.toString())
        val expected = "durable Jules artifact".encodeToByteArray()
        val cid = cas.put(expected)
        val objectPath = root.resolve("sha256").resolve(cid.hex.take(2)).resolve(cid.hex.drop(2))

        try {
            Files.write(objectPath, "corrupt".encodeToByteArray())
            assertFailsWith<IllegalStateException> { cas.get(cid) }

            assertEquals(cid, cas.put(expected))
            assertContentEquals(expected, cas.get(cid))
            assertEquals(ContentId.of(expected), ContentId.of(Files.readAllBytes(objectPath)))
            Files.list(objectPath.parent).use { entries ->
                assertTrue(entries.noneMatch { it.fileName.toString().endsWith(".tmp") })
            }
        } finally {
            fileOps.deleteRecursively(root.toString())
        }
    }

    private fun RandomAccessFile.writeLegacy(key: String, payload: ByteArray) {
        val keyBytes = key.encodeToByteArray()
        writeInt(keyBytes.size)
        write(keyBytes)
        writeInt(payload.size)
        write(payload)
    }
}
