package borg.trikeshed.userspace.nio.platform.spi

import borg.trikeshed.common.Files
import borg.trikeshed.lib.*
import borg.trikeshed.userspace.nio.channels.FileChannel
import kotlin.test.*

class MlxCheckpointBudgetTest {
    private fun checkpoint(): ByteArray {
        val header = """{"a":{"dtype":"F32","shape":[1],"data_offsets":[0,4]}}""".encodeToByteArray()
        return ByteArray(8 + header.size + 4).also { bytes ->
            for (i in 0 until 8) bytes[i] = (header.size.toLong() ushr (8 * i)).toByte()
            header.copyInto(bytes, 8)
            bytes[bytes.lastIndex - 1] = 0x80.toByte()
            bytes[bytes.lastIndex] = 0x3f
        }
    }

    @Test fun concurrentSessionsShareAndReleaseExactFileBytes() {
        val dir = Files.createTempDir("mlx-checkpoint-budget-")
        val bytes = checkpoint()
        val path = "$dir/model.safetensors"
        Files.write(path, bytes)
        val limit = bytes.size.toLong() * 2
        val operations = MlxMatrixOperations(checkpointLimitBytes = limit)
        val first = operations.open()
        val second = operations.open()
        val third = operations.open()
        try {
            assertEquals(MlxMatrixOperations.CheckpointUsage(0, limit, 3), MlxMatrixOperations.checkpointUsage())
            first.load(path, "a")
            second.load(path, "a")
            assertEquals(MlxMatrixOperations.CheckpointUsage(limit, limit, 3), MlxMatrixOperations.checkpointUsage())
            assertFailsWith<IllegalArgumentException> { third.load(path, "a") }
            assertEquals(MlxMatrixOperations.CheckpointUsage(limit, limit, 3), MlxMatrixOperations.checkpointUsage())
            first.close()
            assertEquals(MlxMatrixOperations.CheckpointUsage(bytes.size.toLong(), limit, 2), MlxMatrixOperations.checkpointUsage())
            val loaded = third.load(path, "a")
            assertEquals(1f, loaded.single().b(s_[0]))
            assertEquals(MlxMatrixOperations.CheckpointUsage(limit, limit, 2), MlxMatrixOperations.checkpointUsage())
            assertFailsWith<IllegalStateException> { MlxMatrixOperations(checkpointLimitBytes = limit + 1).open().close() }
        } finally {
            first.close(); second.close(); third.close()
            Files.deleteRecursively(dir)
        }
        assertEquals(MlxMatrixOperations.CheckpointUsage(0, null, 0), MlxMatrixOperations.checkpointUsage())
    }

    @Test fun failedValidationReleasesTheReservation() {
        val dir = Files.createTempDir("mlx-checkpoint-invalid-")
        val good = checkpoint()
        val path = "$dir/model.safetensors"
        Files.write(path, ByteArray(good.size))
        val operations = MlxMatrixOperations(checkpointLimitBytes = good.size.toLong())
        val session = operations.open()
        try {
            assertFails { session.load(path, "a") }
            assertEquals(MlxMatrixOperations.CheckpointUsage(0, good.size.toLong(), 1), MlxMatrixOperations.checkpointUsage())
            Files.write(path, good)
            class Interrupted : RuntimeException()
            var checks = 0
            assertFailsWith<Interrupted> { session.load(path, "a") { if (++checks == 3) throw Interrupted() } }
            assertEquals(MlxMatrixOperations.CheckpointUsage(0, good.size.toLong(), 1), MlxMatrixOperations.checkpointUsage())
            assertEquals(1f, session.load(path, "a").single().b(s_[0]))
        } finally {
            session.close()
            Files.deleteRecursively(dir)
        }
        assertEquals(MlxMatrixOperations.CheckpointUsage(0, null, 0), MlxMatrixOperations.checkpointUsage())
    }

    @Test fun nativeReaderCloseFailuresAreReportedAfterAllResourcesRetire() {
        val dir = Files.createTempDir("mlx-checkpoint-close-")
        val bytes = checkpoint()
        val path = "$dir/model.safetensors"
        Files.write(path, bytes)
        val limit = bytes.size.toLong() * 2
        val session = MlxMatrixOperations(checkpointLimitBytes = limit).open()
        val closed = mutableListOf<String>()
        fun closeFile(name: String): (FileChannel) -> Unit = { channel ->
            channel.close()
            closed.add(name)
            throw IllegalStateException(name).also { it.addSuppressed(IllegalArgumentException("detail: $name")) }
        }
        try {
            session.load(path, _a["a"], {}, closeFile("first close"))
            session.load(path, _a["a"], {}, closeFile("second close"))
            assertEquals(MlxMatrixOperations.CheckpointUsage(limit, limit, 1), MlxMatrixOperations.checkpointUsage())
            val error = assertFailsWith<IllegalStateException> { session.close() }
            assertEquals(_s["first close", "second close"], closed.toSet())
            val failures = _l[error] + error.suppressedExceptions.filter { it.message in _s["first close", "second close"] }
            assertEquals(_s["first close", "second close"], failures.map { it.message }.toSet())
            assertTrue(failures.all { failure -> failure.suppressedExceptions.any { it.message == "detail: ${failure.message}" } })
            assertEquals(MlxMatrixOperations.CheckpointUsage(0, null, 0), MlxMatrixOperations.checkpointUsage())
            session.close()
        } finally {
            session.close()
            Files.deleteRecursively(dir)
        }
    }
}
