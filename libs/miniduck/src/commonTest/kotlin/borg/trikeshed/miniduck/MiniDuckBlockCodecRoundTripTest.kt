package borg.trikeshed.miniduck

import borg.trikeshed.cursor.RowVec
import borg.trikeshed.lib.*
import kotlin.test.*

class MiniDuckBlockCodecRoundTripTest {

    // ── helpers ──────────────────────────────────────────────────────────

    /** Create a sealed block containing the given rows and return the NDJSON. */
    private fun encodeBlock(vararg rows: RowVec): String {
        val block = BlockRowVec.mutable()
        rows.forEach(block::append)
        block.seal()
        return MiniDuckBlockCodec.encode(block)
    }

    /** Decode NDJSON text back into a sealed BlockRowVec. */
    private fun decodeBlock(text: String): BlockRowVec =
        MiniDuckBlockCodec.decode(text)

    /** Round-trip helper: encode rows → decode → return decoded block. */
    private fun roundTrip(vararg rows: RowVec): BlockRowVec =
        decodeBlock(encodeBlock(*rows))

    // ── DocRowVec ────────────────────────────────────────────────────────

    @Test
    fun docRowVecRoundTrip() {
        val doc = DocRowVec(
            keys = listOf("name", "age", "active"),
            cells = listOf("Alice", 30, true),
        )
        val decoded = roundTrip(doc.toRowVec())
        assertEquals(1, decoded.rowCount)
        val out = decoded.child!![0] as DocRowVec
        assertEquals(3, out.size)
        assertEquals(3, out.keys.size)
        assertEquals("name", out.keys[0])
        assertEquals("age", out.keys[1])
        assertEquals("active", out.keys[2])
        assertEquals("Alice", out.cells[0])
        assertEquals(30, out.cells[1])
        assertEquals(true, out.cells[2])
    }

    @Test
    fun docRowVecWithNestedChildRoundTrip() {
        val nested = DocRowVec(listOf("x"), listOf(99))
        val doc = DocRowVec(
            keys = listOf("top"),
            cells = listOf("value"),
            child = 1 j { nested },
        )
        val decoded = roundTrip(doc.toRowVec())
        val out = decoded.child!![0] as DocRowVec
        assertEquals(1, out.keys.size)
        assertEquals("top", out.keys[0])
        assertNotNull(out.child)
        assertEquals(1, out.child!!.size)
        val inner = out.child!![0] as DocRowVec
        assertEquals(1, inner.keys.size)
        assertEquals("x", inner.keys[0])
        assertEquals(99, inner.cells[0])
    }

    // ── ViewRowVec ───────────────────────────────────────────────────────

    @Test
    fun viewRowVecRoundTrip() {
        val view = ViewRowVec("doc-42", "sort-key", mapOf("val" to 1)).toRowVec()
        val decoded = roundTrip(view.toRowVec())
        assertEquals(1, decoded.rowCount)
        val out = decoded.child!![0] as ViewRowVec
        assertEquals("doc-42", out.id)
        assertEquals("sort-key", out.key)
        // value is a Map after JSON reification; check structure
        val v = out.value as Map<*, *>
        assertEquals(1, v["val"])
    }

    // ── JsonRowVec ───────────────────────────────────────────────────────

    @Test
    fun jsonRowVecRoundTrip() {
        val json = JsonRowVec("object", "{\"a\":1}")
        val decoded = roundTrip(json.toRowVec())
        assertEquals(1, decoded.rowCount)
        val out = decoded.child!![0] as JsonRowVec
        assertEquals("object", out.nodeType)
        assertEquals("{\"a\":1}", out.rawValue)
    }

    @Test
    fun jsonRowVecWithChildrenRoundTrip() {
        val child1 = JsonRowVec("string", "\"hello\"")
        val child2 = JsonRowVec("number", "42")
        val json = JsonRowVec("array", "[1,2]") {
            2 j { i -> if (i == 0) child1 else child2 }
        }
        val decoded = roundTrip(json.toRowVec())
        val out = decoded.child!![0] as JsonRowVec
        assertEquals("array", out.nodeType)
        assertEquals("[1,2]", out.rawValue)
        // Children should survive round-trip
        assertNotNull(out.child)
        assertEquals(2, out.child!!.size)
        assertEquals("string", (out.child!![0] as JsonRowVec).nodeType)
        assertEquals("\"hello\"", (out.child!![0] as JsonRowVec).rawValue)
        assertEquals("number", (out.child!![1] as JsonRowVec).nodeType)
        assertEquals("42", (out.child!![1] as JsonRowVec).rawValue)
    }

    // ── YamlRowVec ───────────────────────────────────────────────────────

    @Test
    fun yamlRowVecRoundTrip() {
        val yaml = YamlRowVec("scalar", "hello world")
        val decoded = roundTrip(yaml.toRowVec())
        assertEquals(1, decoded.rowCount)
        val out = decoded.child!![0] as YamlRowVec
        assertEquals("scalar", out.nodeKind)
        assertEquals("hello world", out.scalarValue)
    }

    @Test
    fun yamlRowVecWithChildrenRoundTrip() {
        val entry = YamlRowVec("scalar", "v1")
        val mapping = YamlRowVec("mapping", null) { 1 j { entry } }
        val decoded = roundTrip(mapping)
        val out = decoded.child!![0] as YamlRowVec
        assertEquals("mapping", out.nodeKind)
        assertNull(out.scalarValue)
        assertNotNull(out.child)
        assertEquals(1, out.child!!.size)
        val child = out.child!![0] as YamlRowVec
        assertEquals("scalar", child.nodeKind)
        assertEquals("v1", child.scalarValue)
    }

    // ── BlobRowVec ───────────────────────────────────────────────────────

    @Test
    fun blobRowVecRoundTrip() {
        val bytes = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
        val blob = BlobRowVec(bytes, "application/octet-stream")
        val decoded = roundTrip(blob.toRowVec())
        assertEquals(1, decoded.rowCount)
        val out = decoded.child!![0] as BlobRowVec
        assertTrue(out.bytes.contentEquals(bytes))
        assertEquals("application/octet-stream", out.mimeType)
    }

    // ── GcsRowVec ────────────────────────────────────────────────────────

    @Test
    fun gcsRowVecRoundTrip() {
        val gcs = GcsRowVec(
            bucket = "my-bucket",
            key = "path/to/object.txt",
            byteSize = 1024L,
            contentType = "text/plain",
            etag = "\"abc123\"",
            lastModified = "Mon, 01 Jan 2024 00:00:00 GMT",
            versionId = "v1",
            metadata = mapOf("x-custom" to "yes"),
        )
        val decoded = roundTrip(gcs.toRowVec())
        assertEquals(1, decoded.rowCount)
        val out = decoded.child!![0] as GcsRowVec
        assertEquals("my-bucket", out.bucket)
        assertEquals("path/to/object.txt", out.key)
        assertEquals(1024L, out.byteSize)
        assertEquals("text/plain", out.contentType)
        assertEquals("\"abc123\"", out.etag)
        assertEquals("Mon, 01 Jan 2024 00:00:00 GMT", out.lastModified)
        assertEquals("v1", out.versionId)
        assertEquals(mapOf("x-custom" to "yes"), out.metadata)
        assertEquals(ObjectStoreProvider.GCS, out.provider)
    }

    // ── S3RowVec ─────────────────────────────────────────────────────────

    @Test
    fun s3RowVecRoundTrip() {
        val s3 = S3RowVec(
            bucket = "aws-bucket",
            key = "data/report.csv",
            byteSize = 2048L,
            contentType = "text/csv",
            etag = "\"def456\"",
            lastModified = "Tue, 02 Feb 2024 12:00:00 GMT",
            versionId = null,
            metadata = null,
        )
        val decoded = roundTrip(s3.toRowVec())
        assertEquals(1, decoded.rowCount)
        val out = decoded.child!![0] as S3RowVec
        assertEquals("aws-bucket", out.bucket)
        assertEquals("data/report.csv", out.key)
        assertEquals(2048L, out.byteSize)
        assertEquals("text/csv", out.contentType)
        assertEquals("\"def456\"", out.etag)
        assertEquals("Tue, 02 Feb 2024 12:00:00 GMT", out.lastModified)
        assertNull(out.versionId)
        assertNull(out.metadata)
        assertEquals(ObjectStoreProvider.S3, out.provider)
    }

    // ── AlibabaRowVec ────────────────────────────────────────────────────

    @Test
    fun alibabaRowVecRoundTrip() {
        val ali = AlibabaRowVec(
            bucket = "oss-bucket",
            key = "archive/backup.zip",
            byteSize = 999999L,
            contentType = "application/zip",
            etag = "\"ghi789\"",
            lastModified = "Wed, 03 Mar 2024 08:30:00 GMT",
            versionId = "CAEQARiBgIDMgbGE0RciIDVkMzQxMjAy",
            metadata = mapOf("source" to "cron", "env" to "prod"),
        )
        val decoded = roundTrip(ali)
        assertEquals(1, decoded.rowCount)
        val out = decoded.child!![0] as AlibabaRowVec
        assertEquals("oss-bucket", out.bucket)
        assertEquals("archive/backup.zip", out.key)
        assertEquals(999999L, out.byteSize)
        assertEquals("application/zip", out.contentType)
        assertEquals("\"ghi789\"", out.etag)
        assertEquals("Wed, 03 Mar 2024 08:30:00 GMT", out.lastModified)
        assertEquals("CAEQARiBgIDMgbGE0RciIDVkMzQxMjAy", out.versionId)
        assertEquals(mapOf("source" to "cron", "env" to "prod"), out.metadata)
        assertEquals(ObjectStoreProvider.ALIBABA, out.provider)
    }

    // ── BlockRowVec (nested block) ───────────────────────────────────────

    @Test
    fun blockRowVecRoundTrip() {
        val innerBlock = BlockRowVec.mutable()
        innerBlock.append(DocRowVec(listOf("a"), listOf(1)))
        innerBlock.append(DocRowVec(listOf("b"), listOf(2)))
        innerBlock.seal()

        val decoded = roundTrip(innerBlock)
        assertEquals(1, decoded.rowCount)
        val out = decoded.child!![0] as BlockRowVec
        assertEquals(BlockRowVec.State.SEALED, out.state)
        assertEquals(2, out.rowCount)
        assertEquals(1, (out.child!![0] as DocRowVec).cells[0])
        assertEquals(2, (out.child!![1] as DocRowVec).cells[0])
    }

    // ── Mixed block with multiple different family types ─────────────────

    @Test
    fun mixedBlockRoundTrip() {
        val rows: List<RowVec> = listOf(
            DocRowVec(listOf("name"), listOf("Bob")),
            ViewRowVec("id-1", "k1", 42),
            JsonRowVec("string", "\"test\""),
            YamlRowVec("scalar", "yaml-val"),
            BlobRowVec(byteArrayOf(0x01, 0x02), "img/png"),
            GcsRowVec("b1", "k1", 10L, "text/plain"),
            S3RowVec("b2", "k2", 20L, null),
            AlibabaRowVec("b3", "k3", 30L, "application/json"),
            run {
                val inner = BlockRowVec.mutable()
                inner.append(DocRowVec(listOf("inner"), listOf(true)))
                inner.seal()
                inner
            },
        )
        assertEquals(9, rows.size)

        val text = encodeBlock(*rows.toTypedArray())
        val decoded = decodeBlock(text)

        assertEquals(9, decoded.rowCount)
        assertEquals(BlockRowVec.State.SEALED, decoded.state)

        // DocRowVec
        val d = decoded.child!![0] as DocRowVec
        assertEquals(1, d.keys.size)
        assertEquals("name", d.keys[0])
        assertEquals("Bob", d.cells[0])

        // ViewRowVec
        val v = decoded.child!![1] as ViewRowVec
        assertEquals("id-1", v.id)
        assertEquals("k1", v.key)
        assertEquals(42, v.value)

        // JsonRowVec
        val j = decoded.child!![2] as JsonRowVec
        assertEquals("string", j.nodeType)
        assertEquals("\"test\"", j.rawValue)

        // YamlRowVec
        val y = decoded.child!![3] as YamlRowVec
        assertEquals("scalar", y.nodeKind)
        assertEquals("yaml-val", y.scalarValue)

        // BlobRowVec
        val bl = decoded.child!![4] as BlobRowVec
        assertTrue(bl.bytes.contentEquals(byteArrayOf(0x01, 0x02)))
        assertEquals("img/png", bl.mimeType)

        // GcsRowVec
        val g = decoded.child!![5] as GcsRowVec
        assertEquals("b1", g.bucket)
        assertEquals("k1", g.key)
        assertEquals(10L, g.byteSize)
        assertEquals("text/plain", g.contentType)

        // S3RowVec
        val s = decoded.child!![6] as S3RowVec
        assertEquals("b2", s.bucket)
        assertEquals("k2", s.key)
        assertEquals(20L, s.byteSize)
        assertNull(s.contentType)

        // AlibabaRowVec
        val a = decoded.child!![7] as AlibabaRowVec
        assertEquals("b3", a.bucket)
        assertEquals("k3", a.key)
        assertEquals(30L, a.byteSize)
        assertEquals("application/json", a.contentType)

        // Nested BlockRowVec
        val nestedBlock = decoded.child!![8] as BlockRowVec
        assertEquals(BlockRowVec.State.SEALED, nestedBlock.state)
        assertEquals(1, nestedBlock.rowCount)
        assertEquals(true, (nestedBlock.child!![0] as DocRowVec).cells[0])
    }

    // ── ObjectStoreRowVec factory functions ──────────────────────────────

    @Test
    fun objectStoreFactoryRoundTrips() {
        val gcs = ObjectStoreRowVec.gcs("fb", "fk", 5L, "text/html")
        val s3 = ObjectStoreRowVec.s3("sb", "sk", 6L, "text/xml")
        val ali = ObjectStoreRowVec.alibaba("ab", "ak", 7L, "text/md")

        val decoded = roundTrip(gcs, s3, ali)
        assertEquals(3, decoded.rowCount)

        val dg = decoded.child!![0] as GcsRowVec
        assertEquals("fb", dg.bucket)
        assertEquals(ObjectStoreProvider.GCS, dg.provider)

        val ds = decoded.child!![1] as S3RowVec
        assertEquals("sb", ds.bucket)
        assertEquals(ObjectStoreProvider.S3, ds.provider)

        val da = decoded.child!![2] as AlibabaRowVec
        assertEquals("ab", da.bucket)
        assertEquals(ObjectStoreProvider.ALIBABA, da.provider)
    }
}
