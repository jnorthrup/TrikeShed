package borg.trikeshed.couch.isam

import borg.trikeshed.isam.*
import borg.trikeshed.lib.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FileBackedStringpoolTest {
    private inline fun inDirectory(body: (Path) -> Unit) {
        val directory = Files.createTempDirectory("trikeshed-pool-")
        try { body(directory) } finally {
            Files.list(directory).use { paths -> paths.forEach { Files.deleteIfExists(it) } }
            Files.deleteIfExists(directory)
        }
    }

    private fun leaves(bytes: ByteArray, pool: FileBackedStringpool): IsamLeaves =
        requireNotNull(openLeaves({ at, dst -> bytes.copyInto(dst, 0, at.toInt(), at.toInt() + dst.size) },
            bytes.size.toLong(), width(pool.column), listOf(pool.column)))

    @Test
    fun ids_are_dense_rows_of_a_leaf_isam_column_and_survive_reopen() = inDirectory { directory ->
        val location = directory.resolve("operation.pool").toString()
        val pool = FileBackedStringpool(location)
        assertEquals(listOf(0, 1, 0, 2), listOf("chat", "speech", "chat", "images").map(pool::put))
        assertEquals("speech", pool[1])
        assertNull(pool[3])
        val reopened = FileBackedStringpool(location)
        assertEquals(listOf(1, 3, 3), listOf("speech", "kokoro", "kokoro").map(reopened::put))
        val isam = IsamDataFile(location)
        isam.open()
        try {
            assertEquals(listOf("chat", "speech", "images", "kokoro"), (0 until isam.size).map { isam[it][0].a })
        } finally { isam.close() }
    }

    @Test
    fun put_appends_one_row_behind_the_full_leaves() = inDirectory { directory ->
        val location = directory.resolve("key.pool").toString()
        val pool = FileBackedStringpool(location)
        // 256 rows of 64 bytes fill leaf 0; rows 256..299 sit in the partial leaf 1.
        for (k in 0 until 300) assertEquals(k, pool.put("key-$k"))
        val before = Files.readAllBytes(Path.of(location))
        val full = leaves(before, pool).table.packed[1].toInt()
        assertEquals(2, leaves(before, pool).table.frames)
        assertEquals(300, pool.put("key-300"))
        val after = Files.readAllBytes(Path.of(location))
        assertContentEquals(before.copyOf(full), after.copyOf(full))
        assertEquals(301, leaves(after, pool).rows)
    }
}
