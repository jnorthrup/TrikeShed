package borg.trikeshed.isam

import borg.trikeshed.cursor.ColumnMeta
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.isam.meta.IsamMetaFileReader
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.view
import borg.trikeshed.userspace.FunctionalUringFacade
import borg.trikeshed.userspace.JvmDescriptor
import borg.trikeshed.userspace.JvmFileTable
import borg.trikeshed.userspace.SelectionResult
import borg.trikeshed.userspace.UringOp
import borg.trikeshed.userspace.UringOp.Companion.Submissions
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.UserspaceChannelBackend
import borg.trikeshed.userspace.openJvmEmulatedChannelBackend
import borg.trikeshed.userspace.nio.IOException
import borg.trikeshed.userspace.nio.channels.FileChannel
import borg.trikeshed.userspace.nio.channels.UringChannel
import borg.trikeshed.userspace.nio.channels.UringFileChannel
import borg.trikeshed.userspace.nio.file.File
import borg.trikeshed.userspace.nio.file.OpenOption
import borg.trikeshed.userspace.nio.file.StandardOpenOption
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JvmIsamOperationsTest {
    /** Real files, with faults confined to SQE/CQE handling beneath the existing facade. */
    private class Backend(private val io: UserspaceChannelBackend = openJvmEmulatedChannelBackend()) : UserspaceChannelBackend by io {
        override val capabilities get() = io.capabilities
        override val nativeCapabilities get() = io.nativeCapabilities
        var maximumTransfer = 2
        var failure: UringOp? = null
        var invalidResult: Int? = null
        var writesBeforeFailure = Int.MAX_VALUE
        var rejectClose = false
        var closes = 0
        val operations = mutableListOf<UringOp>()
        val descriptors = mutableListOf<JvmDescriptor>()

        override fun submitBatch(submissions: List<UringSubmission>): List<SelectionResult> = submissions.map { submission ->
            operations += submission.opcode
            if (submission.opcode == failure || (submission.opcode == UringOp.WRITE && writesBeforeFailure-- <= 0) ||
                (submission.opcode == UringOp.CLOSE && rejectClose)) {
                SelectionResult(invalidResult ?: -5, submission.userData)
            } else {
                val limited = if (submission.opcode == UringOp.READ || submission.opcode == UringOp.WRITE)
                    submission.copy(len = minOf(maximumTransfer, submission.len)) else submission
                val result = io.submitBatch(listOf(limited)).single()
                if (submission.opcode == UringOp.OPENAT && result.res >= 0) {
                    descriptors += requireNotNull(JvmFileTable.descriptor(result.res))
                }
                result
            }
        }

        override fun close() { closes++; io.close() }
    }

    private class Channels(val configure: (Backend, Int) -> Unit = { _, _ -> }) {
        val backends = mutableListOf<Backend>()

        fun open(path: String, options: Set<OpenOption>): FileChannel {
            val backend = Backend().also { configure(it, backends.size); backends += it }
            val channel = UringChannel(FunctionalUringFacade(32, backend))
            try {
                val write = StandardOpenOption.WRITE in options
                var flags = if (write) { if (StandardOpenOption.READ in options) 2 else 1 } else 0
                if (StandardOpenOption.CREATE in options) flags = flags or 64
                if (StandardOpenOption.TRUNCATE_EXISTING in options) flags = flags or 512
                channel.enqueue(Submissions.openat(path, flags, 0))
                channel.submit()
                val completion = channel.wait(1).single()
                if (completion.res < 0) throw IOException("ISAM test OPENAT failed: ${completion.res}")
                return UringFileChannel(File.fromFd(completion.res), channel)
            } catch (failure: Throwable) {
                channel.closeNow()
                throw failure
            }
        }

        fun assertClosed() {
            backends.forEach { backend ->
                assertEquals(1, backend.closes, "Facade backend closes exactly once")
                assertTrue(backend.descriptors.all { !it.isOpen() }, "Actual file descriptors must be closed")
            }
        }
    }

    private fun cursor(vararg values: Pair<Int, Byte>): Cursor {
        val metadata = arrayOf(ColumnMeta("id", IOMemento.IoInt), ColumnMeta("flag", IOMemento.IoByte))
        return values.size j { row: Int ->
            val data: Array<Any> = arrayOf(values[row].first, values[row].second)
            2 j { column: Int -> data[column] j { metadata[column] } }
        }
    }

    private inline fun inDirectory(body: (Path) -> Unit) {
        val directory = Files.createTempDirectory("trikeshed-isam-")
        try { body(directory) } finally {
            Files.list(directory).use { paths -> paths.forEach { Files.deleteIfExists(it) } }
            Files.deleteIfExists(directory)
        }
    }

    private fun reader(operations: JvmIsamOperations, path: String) =
        operations.createReader(path, "$path.meta", IsamMetaFileReader("$path.meta"))

    @Test
    fun grouped_rows_round_trip_through_partial_facade_io_as_direct_files() = inDirectory { directory ->
        val channels = Channels()
        val operations = JvmIsamOperations(channels::open)
        val path = directory.resolve("rows.bin").toString()
        operations.write(cursor(0x01020304 to 9, 0x11223344 to 7), path, emptyMap(), true)
        channels.assertClosed()
        assertContentEquals(byteArrayOf(4, 3, 2, 1, 0x44, 0x33, 0x22, 0x11), Files.readAllBytes(Path.of(path)))
        assertContentEquals(byteArrayOf(9, 7), Files.readAllBytes(directory.resolve("rows.IoByte.bin")))
        Files.list(directory).use { paths ->
            assertEquals(setOf("rows.bin", "rows.bin.meta", "rows.IoByte.bin"), paths.map { it.fileName.toString() }.toList().toSet())
        }
        val source = reader(operations, path)
        source.open()
        try {
            assertEquals(2, source.recordCount)
            assertEquals(0x01020304, source.readRow(0)[0].a)
            assertEquals(7.toByte(), source.readRow(1)[1].a)
            assertTrue(channels.backends.any { it.operations.count { op -> op == UringOp.READ } > 2 })
            assertFails { source.readRow(2) }
        } finally { source.close(); source.close() }
        channels.assertClosed()
        assertTrue(channels.backends.take(2).all { UringOp.FSYNC in it.operations })
    }

    @Test
    fun ungrouped_write_truncates_then_append_preserves_the_existing_schema() = inDirectory { directory ->
        val path = directory.resolve("rows.bin").toString()
        val channels = Channels()
        val operations = JvmIsamOperations(channels::open)
        operations.write(cursor(1 to 2, 3 to 4, 5 to 6), path, emptyMap(), false)
        operations.write(cursor(7 to 8), path, emptyMap(), false)
        assertEquals(5L, Files.size(Path.of(path)))
        val metadata = Files.readAllBytes(Path.of("$path.meta"))
        operations.append(cursor(9 to 10, 11 to 12).view, path, emptyMap(), null, false)
        assertContentEquals(metadata, Files.readAllBytes(Path.of("$path.meta")))
        assertEquals(15L, Files.size(Path.of(path)))
        val source = reader(operations, path)
        source.open()
        try {
            assertEquals(3, source.recordCount)
            assertEquals(7, source.readRow(0)[0].a)
            assertEquals(9, source.readRow(1)[0].a)
            assertEquals(12.toByte(), source.readRow(2)[1].a)
        } finally { source.close() }
        channels.assertClosed()
    }

    @Test
    fun append_schema_mismatch_and_empty_input_do_not_mutate_existing_files() = inDirectory { directory ->
        val path = directory.resolve("rows.bin").toString()
        val operations = JvmIsamOperations()
        operations.append(emptyList<RowVec>(), path, emptyMap(), null, false)
        assertFalse(Files.exists(Path.of(path)))
        operations.write(cursor(17 to 18), path, emptyMap(), false)
        val data = Files.readAllBytes(Path.of(path))
        val metadata = Files.readAllBytes(Path.of("$path.meta"))
        assertFails { operations.append(cursor(1 to 2).view, path, emptyMap(), null, true) }
        assertContentEquals(data, Files.readAllBytes(Path.of(path)))
        assertContentEquals(metadata, Files.readAllBytes(Path.of("$path.meta")))
    }

    @Test
    fun partial_write_failure_restores_row_boundaries_and_preserves_the_primary_error() = inDirectory { directory ->
        val channels = Channels { backend, index ->
            if (index == 0) { backend.writesBeforeFailure = 1; backend.rejectClose = true }
        }
        val path = directory.resolve("rows.bin").toString()
        val failure = assertFailsWith<IOException> {
            JvmIsamOperations(channels::open).write(cursor(0x01020304 to 9), path, emptyMap(), true)
        }
        assertTrue(failure.message.orEmpty().contains("WRITE"))
        assertEquals(1, failure.suppressedExceptions.size)
        assertEquals(0L, Files.size(Path.of(path)))
        assertEquals(0L, Files.size(directory.resolve("rows.IoByte.bin")))
        assertEquals(2, channels.backends.size)
        channels.assertClosed()
    }

    @Test
    fun eof_after_reader_open_closes_channels_and_allows_reopen_after_repair() = inDirectory { directory ->
        val path = directory.resolve("rows.bin").toString()
        JvmIsamOperations().write(cursor(1 to 2, 3 to 4), path, emptyMap(), true)
        val channels = Channels()
        val source = reader(JvmIsamOperations(channels::open), path)
        source.open()
        Files.write(Path.of(path), byteArrayOf(1))
        assertFailsWith<IOException> { source.readRow(1) }
        source.close()
        channels.assertClosed()
        assertFails { source.recordCount }
        JvmIsamOperations().write(cursor(5 to 6), path, emptyMap(), true)
        source.open()
        try {
            assertEquals(1, source.recordCount)
            assertEquals(5, source.readRow(0)[0].a)
        } finally { source.close() }
        channels.assertClosed()
    }

    @Test
    fun open_failure_and_malformed_completions_close_owned_resources() = inDirectory { directory ->
        val path = directory.resolve("rows.bin").toString()
        JvmIsamOperations().write(cursor(1 to 2), path, emptyMap(), true)
        val failedOpen = Channels { backend, index -> if (index == 1) backend.failure = UringOp.OPENAT }
        assertFailsWith<IOException> { reader(JvmIsamOperations(failedOpen::open), path).open() }
        failedOpen.assertClosed()
        for (result in arrayOf(0, 99, 1)) {
            val channels = Channels { backend, _ -> backend.failure = UringOp.READ; backend.invalidResult = result }
            val source = reader(JvmIsamOperations(channels::open), path)
            source.open()
            assertFailsWith<IOException> { source.readRow(0) }
            channels.assertClosed()
        }
    }

    @Test
    fun sync_failure_and_misaligned_group_sizes_are_reported_with_cleanup() = inDirectory { directory ->
        val path = directory.resolve("rows.bin").toString()
        val channels = Channels { backend, _ -> backend.failure = UringOp.FSYNC }
        assertFailsWith<IOException> { JvmIsamOperations(channels::open).write(cursor(1 to 2), path, emptyMap(), true) }
        channels.assertClosed()
        Files.write(Path.of(path), byteArrayOf(1))
        val readers = Channels()
        assertFailsWith<IllegalArgumentException> { reader(JvmIsamOperations(readers::open), path).open() }
        readers.assertClosed()
    }

    @Test
    fun reopen_refreshes_groups_and_count_while_retained_rows_keep_their_schema() = inDirectory { directory ->
        val path = directory.resolve("rows.bin").toString()
        val channels = Channels()
        val operations = JvmIsamOperations(channels::open)
        operations.write(cursor(1 to 2, 3 to 4), path, emptyMap(), false)
        val source = reader(operations, path)
        source.open()
        val opened = channels.backends.size
        source.open()
        assertEquals(opened, channels.backends.size)
        val retained = source.readRow(0)
        assertFailsWith<IllegalArgumentException> { source.readRow(-1) }
        source.close()
        channels.assertClosed()
        assertFailsWith<IllegalStateException> { source.readRow(0) }
        operations.write(cursor(9 to 10), path, emptyMap(), true)
        source.open()
        try {
            assertEquals(1, source.recordCount)
            assertEquals(9, source.readRow(0)[0].a)
            assertEquals(10.toByte(), source.readRow(0)[1].a)
            assertEquals(1, retained[0].a)
            assertEquals(2.toByte(), retained[1].a)
            assertEquals("0", (retained[0].b() as RecordMeta).groupName)
        } finally { source.close() }
        assertEquals(1, retained[0].a)
        channels.assertClosed()
    }

    @Test
    fun failed_group_open_can_be_retried_on_the_same_reader() = inDirectory { directory ->
        val path = directory.resolve("rows.bin").toString()
        JvmIsamOperations().write(cursor(1 to 2), path, emptyMap(), true)
        val channels = Channels { backend, index -> if (index == 1) backend.failure = UringOp.OPENAT }
        val source = reader(JvmIsamOperations(channels::open), path)
        assertFailsWith<IOException> { source.open() }
        channels.assertClosed()
        source.open()
        try {
            assertEquals(1, source.recordCount)
            assertEquals(2.toByte(), source.readRow(0)[1].a)
        } finally { source.close() }
        channels.assertClosed()
    }

    @Test
    fun failed_append_restores_all_groups_and_can_be_retried() = inDirectory { directory ->
        val path = directory.resolve("rows.bin").toString()
        JvmIsamOperations().write(cursor(1 to 2), path, emptyMap(), true)
        val primary = Files.readAllBytes(Path.of(path))
        val secondary = Files.readAllBytes(directory.resolve("rows.IoByte.bin"))
        val channels = Channels { backend, index -> if (index == 1) backend.failure = UringOp.WRITE }
        val operations = JvmIsamOperations(channels::open)
        assertFailsWith<IOException> { operations.append(cursor(3 to 4).view, path, emptyMap(), null, true) }
        channels.assertClosed()
        assertContentEquals(primary, Files.readAllBytes(Path.of(path)))
        assertContentEquals(secondary, Files.readAllBytes(directory.resolve("rows.IoByte.bin")))
        operations.append(cursor(5 to 6).view, path, emptyMap(), null, true)
        val source = reader(operations, path)
        source.open()
        try {
            assertEquals(2, source.recordCount)
            assertEquals(1, source.readRow(0)[0].a)
            assertEquals(5, source.readRow(1)[0].a)
        } finally { source.close() }
        channels.assertClosed()
    }

    @Test
    fun variable_width_padding_and_oversize_rejection_preserve_completed_rows() = inDirectory { directory ->
        val path = directory.resolve("rows.bin").toString()
        val metadata = ColumnMeta("text", IOMemento.IoString)
        fun textCursor(vararg values: String): Cursor = values.size j { row: Int ->
            1 j { _: Int -> (values[row] as Any) j { metadata } }
        }
        val channels = Channels()
        val operations = JvmIsamOperations(channels::open)
        operations.write(textCursor("abcd", "q"), path, mapOf("text" to 4), false)
        val bytes = byteArrayOf(97, 98, 99, 100, 113, 0, 0, 0)
        assertContentEquals(bytes, Files.readAllBytes(Path.of(path)))
        assertFailsWith<IllegalArgumentException> {
            operations.append(textCursor("\u00e9\u00e9x").view, path, mapOf("text" to 4), null, false)
        }
        assertContentEquals(bytes, Files.readAllBytes(Path.of(path)))
        channels.assertClosed()
    }

    @Test
    fun encoder_and_transform_failures_close_every_group_before_the_next_row() = inDirectory { directory ->
        val path = directory.resolve("rows.bin").toString()
        val channels = Channels()
        val operations = JvmIsamOperations(channels::open)
        val metadata = RecordMeta("id", IOMemento.IoInt, 0, 4, encoder = { byteArrayOf(1) })
        val invalid: Cursor = 1 j { _: Int -> 1 j { _: Int -> (1 as Any) j { metadata } } }
        assertFailsWith<IllegalArgumentException> { operations.write(invalid, path, emptyMap(), false) }
        assertEquals(0L, Files.size(Path.of(path)))
        channels.assertClosed()
        operations.write(cursor(1 to 2), path, emptyMap(), true)
        val failure = IllegalStateException("transform failed")
        val caught = assertFailsWith<IllegalStateException> {
            operations.append(cursor(3 to 4, 5 to 6).view, path, emptyMap(), { row ->
                if (row[0].a == 5) throw failure
                row
            }, true)
        }
        assertTrue(caught === failure)
        channels.assertClosed()
        val source = reader(operations, path)
        source.open()
        try {
            assertEquals(2, source.recordCount)
            assertEquals(3, source.readRow(1)[0].a)
        } finally { source.close() }
        channels.assertClosed()
    }

    @Test
    fun mismatched_group_counts_reject_read_and_append_without_mutating_files() = inDirectory { directory ->
        val path = directory.resolve("rows.bin").toString()
        JvmIsamOperations().write(cursor(1 to 2), path, emptyMap(), true)
        val groupPath = directory.resolve("rows.IoByte.bin")
        Files.write(groupPath, byteArrayOf(2, 3))
        val primary = Files.readAllBytes(Path.of(path))
        val channels = Channels()
        val operations = JvmIsamOperations(channels::open)
        assertFailsWith<IllegalArgumentException> { reader(operations, path).open() }
        assertFailsWith<IllegalArgumentException> { operations.append(cursor(5 to 6).view, path, emptyMap(), null, true) }
        assertContentEquals(primary, Files.readAllBytes(Path.of(path)))
        assertContentEquals(byteArrayOf(2, 3), Files.readAllBytes(groupPath))
        channels.assertClosed()
    }
}
