package borg.trikeshed.kanban

import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.test.*

class BoardFileWalLeaseTest {
    @org.junit.jupiter.api.io.TempDir lateinit var root: Path

    @Test fun anotherOwnerCannotRecoverOrOverwriteTheActiveWal(): Unit {
        val files = JvmFileOperations()
        val directory = root.resolve("board")
        val first = BoardFileWal(files, directory.toString())
        try {
            assertEquals(1L, first.append("first".encodeToByteArray()))
            first.flush()
            val wal = directory.resolve("board.wal")
            val complete = Files.readAllBytes(wal)
            Files.write(wal, "short tail".encodeToByteArray(), StandardOpenOption.APPEND)
            val before = Files.readAllBytes(wal)
            val refusal = assertFails { BoardFileWal(JvmFileOperations(), directory.toString()).close() }
            assertTrue(refusal.message.orEmpty().contains("lease", ignoreCase = true))
            assertContentEquals(before, Files.readAllBytes(wal), "Refused owner must not truncate the active WAL")
            first.close()
            val recovered = BoardFileWal(files, directory.toString())
            try {
                assertContentEquals(complete, Files.readAllBytes(wal), "A new exclusive owner may recover the short tail")
                assertEquals(2L, recovered.append("second".encodeToByteArray()))
                recovered.flush()
            } finally { recovered.close(); recovered.close() }
            val next = BoardFileWal(files, directory.toString())
            try { assertEquals(3L, next.append("third".encodeToByteArray())); next.flush() }
            finally { next.close() }
        } finally { first.close() }
    }

    @Test fun corruptFrameRefusalReleasesTheLeaseWithoutChangingTheWal(): Unit {
        val files = JvmFileOperations()
        val directory = root.resolve("corrupt")
        val first = BoardFileWal(files, directory.toString())
        try { first.append("complete".encodeToByteArray()); first.flush() } finally { first.close() }
        val wal = directory.resolve("board.wal")
        val complete = Files.readAllBytes(wal)
        val corrupt = complete.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        Files.write(wal, corrupt)
        val refusal = assertFails { BoardFileWal(files, directory.toString()).close() }
        assertTrue(refusal.message.orEmpty().contains("checksum"))
        assertContentEquals(corrupt, Files.readAllBytes(wal))
        Files.write(wal, complete)
        val recovered = BoardFileWal(files, directory.toString())
        try { assertEquals(2L, recovered.append("after repair".encodeToByteArray())); recovered.flush() }
        finally { recovered.close() }
    }

    @Test fun channelOpenFailureReleasesTheLease(): Unit {
        val files = JvmFileOperations()
        val directory = Files.createDirectory(root.resolve("occupied"))
        val occupied = Files.createDirectory(directory.resolve("board.wal"))
        assertFails { BoardFileWal(files, directory.toString()).close() }
        assertTrue(Files.isDirectory(occupied))
        Files.delete(occupied)
        val recovered = BoardFileWal(files, directory.toString())
        try { assertEquals(1L, recovered.append("opened".encodeToByteArray())); recovered.flush() }
        finally { recovered.close() }
    }

    @Test fun directoryAliasesShareOwnershipWhileDistinctDirectoriesRemainIndependent(): Unit {
        val files = JvmFileOperations()
        val directory = root.resolve("actual")
        val first = BoardFileWal(files, directory.toString())
        try {
            val alias = Files.createSymbolicLink(root.resolve("alias"), directory)
            assertFails { BoardFileWal(JvmFileOperations(), alias.toString()).close() }
            val independent = BoardFileWal(files, root.resolve("other").toString())
            try { assertEquals(1L, independent.append("independent".encodeToByteArray())); independent.flush() }
            finally { independent.close() }
        } finally { first.close() }
    }
}
