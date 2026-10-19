package borg.trikeshed.kanban

import borg.trikeshed.couch.isam.WalFrame
import borg.trikeshed.lib.*
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.userspace.nio.file.spi.StorageDurability
import borg.trikeshed.userspace.nio.spi.platformNioProviders
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class BoardFileWalTest {
    private fun platformFiles(): FileOperations =
        platformNioProviders().filterIsInstance<FileOperations>().single()

    @Test
    fun nativeFileAndDirectorySyncRetainLegacyFramesAcrossReopen() = runBlocking<Unit> {
        val files = platformFiles()
        val directory = files.canonicalPath(files.createTempDir("trikeshed-native-board-wal"))
        val nested = files.resolvePath(directory, "new", "board")
        val first = "Native evidence — 患者".encodeToByteArray()
        val second = byteArrayOf(0, 127, -1)
        val third = "next".encodeToByteArray()
        try {
            val wal = BoardFileWal(files, nested)
            try {
                assertEquals(StorageDurability.DURABLE, wal.durability)
                assertEquals(1L, wal.append(first))
                assertEquals(2L, wal.append(second))
                wal.flush()
            } finally { wal.close() }
            val path = files.resolvePath(nested, "board.wal")
            val prefix = WalFrame.encode(1, first) + WalFrame.encode(2, second)
            assertContentEquals(prefix, files.readAllBytes(path))

            val reopened = BoardFileWal(files, nested)
            try {
                val records = mutableListOf<Join<Long, ByteArray>>()
                reopened.replay { sequence, payload -> records.add(sequence j payload) }
                assertEquals(listOf(1L, 2L), records.map { it.a })
                assertContentEquals(first, records[0].b)
                assertContentEquals(second, records[1].b)
                assertEquals(3L, reopened.append(third))
                reopened.flush()
            } finally { reopened.close() }
            assertContentEquals(prefix + WalFrame.encode(3, third), files.readAllBytes(path))
        } finally { files.deleteRecursively(directory) }
    }
}
