package borg.trikeshed.isam

import borg.trikeshed.cursor.ColumnMeta
import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.isam.meta.IsamMetaFileReader
import borg.trikeshed.lib.get
import borg.trikeshed.lib.toSeries
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class IsamMetaFileReaderTest {
    private inline fun withMetadata(body: (Path, IsamMetaFileReader) -> Unit) {
        val path = Files.createTempFile("trikeshed-isam-", ".meta")
        val reader = IsamMetaFileReader(path.toString())
        try { body(path, reader) } finally { reader.close(); Files.deleteIfExists(path) }
    }

    @Test
    fun malformed_metadata_rejects_cardinality_offsets_and_codec_widths() = withMetadata { path, reader ->
        val cases = arrayOf(
            "",
            "0 4\nid",
            "0 4\nid flag\nIoInt IoByte",
            "0 4 4 5\nid flag\nIoInt",
            "0 4 4 5\nid\nIoInt",
            "-1 3\nid\nIoInt",
            "0 0\nid\nIoInt",
            "1 5\nid\nIoInt",
            "0 4 3 4\nid flag\nIoInt IoByte",
            "0 4 5 6\nid flag\nIoInt IoByte",
            "0 3\nid\nIoInt",
            "0 2147483648\ntext\nIoString",
            "0 4\nid\nUnknownCodec"
        )
        for (text in cases) {
            Files.writeString(path, text)
            assertFailsWith<IllegalArgumentException>(text) { reader.open() }
        }
    }

    @Test
    fun close_and_failed_reload_discard_cached_metadata() = withMetadata { path, reader ->
        Files.writeString(path, "0 4 4 5\nid flag\nIoInt IoByte\n")
        assertEquals(5, reader.recordlen)
        val original = reader.constraints
        assertEquals(0, original[0].groupId)
        assertEquals(0, original[1].groupId)
        Files.writeString(path, "0 0\nbroken\nIoInt")
        assertFailsWith<IllegalArgumentException> { reader.open() }
        assertFailsWith<IllegalArgumentException> { reader.recordlen }
        reader.close()
        Files.writeString(path, "0 8\nvalue\nIoLong\n")
        assertEquals(8, reader.recordlen)
        assertEquals("value", reader.constraints[0].name)
        assertEquals("id", original[0].name)
        assertEquals("flag", original[1].name)
    }

    @Test
    fun invalid_group_assignments_are_rejected_before_expanding_ranges() {
        for (line in arrayOf("broken", ":x", "0:", "1:x", "-1:x", "0-2147483647:x", "1-0:x",
            "0:x 0:y", "0,0:x", "0:a/b", "0:..\\escape", "0:a:b")) {
            assertFailsWith<IllegalArgumentException>(line) { IsamMetaFileReader.parseGroupsLine(line, 1) }
        }
        val groups = IsamMetaFileReader.parseGroupsLine("0:1", 2)
        assertEquals("1", groups[0].b)
        assertNotEquals(groups[0].b, groups[1].b)
        assertNotEquals(groups[0].a, groups[1].a)
    }

    @Test
    fun invalid_write_widths_and_overflow_leave_existing_metadata_intact() = withMetadata { path, _ ->
        Files.writeString(path, "unchanged")
        val columns = arrayOf(ColumnMeta("text", IOMemento.IoString), ColumnMeta("flag", IOMemento.IoByte)).toSeries()
        for (width in arrayOf(0, -1, Int.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> {
                IsamMetaFileReader.write(path.toString(), columns, mapOf("text" to width), useMonocursorGroupings = false)
            }
            assertEquals("unchanged", Files.readString(path))
        }
        val conflicting = arrayOf<ColumnMeta>(
            RecordMeta("id", IOMemento.IoInt, 0, 4).also { it.groupName = "ids" },
            RecordMeta("flag", IOMemento.IoByte, 4, 5).also { it.groupName = "flags" }
        ).toSeries()
        assertFailsWith<IllegalArgumentException> {
            IsamMetaFileReader.sanitize(conflicting, emptyMap(), false)
        }
    }
}
