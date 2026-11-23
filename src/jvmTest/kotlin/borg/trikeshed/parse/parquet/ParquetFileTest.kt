package borg.trikeshed.parse.parquet

import borg.trikeshed.lib.*
import kotlin.test.*

/** The four fixtures hold the same 300 rows, written with each codec, page version and row-group split the reader carries. */
class ParquetFileTest {
    val names = s_["snappy-v1.parquet", "zstd-v2-groups.parquet", "plain-v1.parquet", "gzip-v1-pages.parquet"]

    fun fixture(name: String): Join<ParquetFile, Int> {
        val bytes = checkNotNull(ParquetFileTest::class.java.getResourceAsStream("/parquet/$name")).use { it.readAllBytes() }
        return ParquetFile(bytes.size.toLong(), { offset, length -> bytes.copyOfRange(offset.toInt(), offset.toInt() + length) }, parquetInflate) j bytes.size
    }

    @Test
    fun everyColumnReadsAsItWasWritten() = names.forEach { name ->
        val file = fixture(name).a
        assertEquals(300L, file.footer.rows, name)
        val id = file.column("id")
        val label = file.column("name")
        val body = file.column("body")
        val flag = file.column("flag")
        val score = file.column("score")
        val count = file.column("count")
        (0 until 300).toSeries().forEach { i ->
            assertEquals(i.toLong(), id[i], "$name id $i")
            assertEquals(if (i % 7 == 0) null else "name-${i % 13}", label[i], "$name name $i")
            assertEquals("line $i " + "x".repeat(i % 40), body[i], "$name body $i")
            assertEquals(if (i % 5 == 0) null else i % 3 == 0, flag[i], "$name flag $i")
            assertEquals(i / 4.0, score[i], "$name score $i")
            assertEquals((i - 100).toLong(), count[i], "$name count $i")
        }
    }

    @Test
    fun theFooterIsTheByteRangeIndex() {
        val (file, size) = fixture("zstd-v2-groups.parquet")
        val footer = file.footer
        val paths = s_["id", "name", "body", "flag", "score", "count"]
        assertEquals(paths.size, footer.columns.size)
        paths.forEachIndexed { i, path -> assertEquals(path, footer.columns[i].path) }
        assertEquals(3 * paths.size, footer.chunks.size)
        footer.chunks.forEachIndexed { i, chunk ->
            assertTrue(chunk.begin >= 4 && chunk.begin + chunk.length <= size, "chunk $i lies inside the file")
            if (i > 0) assertTrue(footer.chunks[i - 1].begin + footer.chunks[i - 1].length <= chunk.begin, "chunk $i follows chunk ${i - 1}")
        }
    }

    @Test
    fun aRepeatedColumnIsRefusedNotMisread() {
        val chunk = ParquetChunk(0, ParquetColumn("list.element", 2, false, 0, 3, 1), 0, 1, 4, 0, 0)
        val refusal = assertFailsWith<IllegalArgumentException> { ParquetPages.values(chunk, ByteArray(0)) { _, bytes, _ -> bytes } }
        assertTrue("repeated" in refusal.message.orEmpty())
    }
}
