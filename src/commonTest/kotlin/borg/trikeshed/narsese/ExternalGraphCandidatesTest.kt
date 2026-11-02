package borg.trikeshed.narsese

import borg.trikeshed.job.*
import borg.trikeshed.lib.*
import kotlin.test.*

class ExternalGraphCandidatesTest {
    @Test fun retainsExactRowsWithoutClaimingShardMembership() {
        val cas = CasStore.inMemory()
        val shard = "parquet bytes".encodeToByteArray()
        val record = "{\"type\":\"HAS_SOURCE\"}".encodeToByteArray()
        val manifest = ("""{"artifacts":[{"relative_path":"data/shard.parquet","row_count":1,"size_bytes":${shard.size},"sha256":"${ContentId.of(shard).hex}","schema_id":"example/v1"}]}""").encodeToByteArray()
        val rows = s_[ExternalGraphRow(0, "source-entry", record, ContentId.of(record).hex)]
        val result = ExternalGraphCandidates.retain(cas, manifest, ContentId.of(manifest), "data/shard.parquet", shard, "external decoder", rows)
        assertContentEquals(shard, cas.get(result.shard))
        assertContentEquals(record, cas.get(result.rows[0]))
        val receipt = CanonicalCbor.decodeMap(assertNotNull(cas.get(result.receipt)))
        assertEquals("unverified", receipt["membership"])
        assertEquals("unreviewed", receipt["reviewStatus"])
    }

    @Test fun refusesTamperingAndDuplicateDescriptors() {
        val cas = CasStore.inMemory()
        val shard = "s".encodeToByteArray()
        val row = "{}".encodeToByteArray()
        val descriptor = """{"relative_path":"x","row_count":1,"size_bytes":1,"sha256":"${ContentId.of(shard).hex}","schema_id":"v1"}"""
        val manifest = """{"artifacts":[$descriptor]}""".encodeToByteArray()
        fun attempt(m: ByteArray, bytes: ByteArray, recordHash: String) = ExternalGraphCandidates.retain(cas, m, ContentId.of(m), "x", bytes, "decoder", s_[ExternalGraphRow(0, "id", row, recordHash)])
        assertFailsWith<IllegalArgumentException> { attempt(manifest, "t".encodeToByteArray(), ContentId.of(row).hex) }
        assertFailsWith<IllegalArgumentException> { attempt(manifest, shard, ContentId.of("bad".encodeToByteArray()).hex) }
        assertFailsWith<IllegalArgumentException> { attempt("""{"artifacts":[$descriptor,$descriptor]}""".encodeToByteArray(), shard, ContentId.of(row).hex) }
        assertFailsWith<IllegalArgumentException> { ExternalGraphCandidates.retain(cas, manifest, ContentId.of("wrong".encodeToByteArray()), "x", shard, "decoder", emptySeriesOf()) }
    }

    @Test fun snapshotsMutableInputsAndReadsEachLazyRowOnce() {
        val cas = CasStore.inMemory()
        val shard = "s".encodeToByteArray()
        val first = "{}".encodeToByteArray()
        val second = "{\"n\":1}".encodeToByteArray()
        val firstId = ContentId.of(first)
        val secondId = ContentId.of(second)
        val shardId = ContentId.of(shard)
        val manifest = """{"artifacts":[{"relative_path":"x","row_count":2,"size_bytes":1,"sha256":"${shardId.hex}","schema_id":"v1"}]}""".encodeToByteArray()
        val manifestId = ContentId.of(manifest)
        var reads = 0
        val rows: Series<ExternalGraphRow> = 2 j { i: Int ->
            reads++
            if (i == 0) ExternalGraphRow(0, "first", first, firstId.hex)
            else {
                // A caller-controlled oracle can mutate every caller-owned array on the next read.
                first[0] = 'x'.code.toByte(); shard[0] = 'x'.code.toByte(); manifest[0] = 'x'.code.toByte()
                ExternalGraphRow(1, "second", second, secondId.hex)
            }
        }
        val result = ExternalGraphCandidates.retain(cas, manifest, manifestId, "x", shard, "decoder", rows)
        assertEquals(2, reads)
        assertEquals(manifestId, result.manifest)
        assertEquals(shardId, result.shard)
        assertEquals(firstId, result.rows[0])
        assertEquals(secondId, result.rows[1])
        assertContentEquals("{}".encodeToByteArray(), cas.get(result.rows[0]))
    }

    @Test fun rejectsFractionalDescriptorNumbersAndNegativeRowCount() {
        val cas = CasStore.inMemory()
        val shard = "s".encodeToByteArray()
        fun manifest(count: String, size: String) =
            """{"artifacts":[{"relative_path":"x","row_count":$count,"size_bytes":$size,"sha256":"${ContentId.of(shard).hex}","schema_id":"v1"}]}""".encodeToByteArray()
        fun attempt(m: ByteArray) = ExternalGraphCandidates.retain(cas, m, ContentId.of(m), "x", shard, "decoder", emptySeriesOf())
        assertFailsWith<IllegalArgumentException> { attempt(manifest("1.5", "1")) }
        assertFailsWith<IllegalArgumentException> { attempt(manifest("1", "1.5")) }
        val good = manifest("1", "1")
        val invalid: Series<ExternalGraphRow> = (-1) j { _: Int -> error("must not read") }
        assertFailsWith<IllegalArgumentException> { ExternalGraphCandidates.retain(cas, good, ContentId.of(good), "x", shard, "decoder", invalid) }
    }

    @Test fun capsJsonNestingButDoesNotCountBracketsInStrings() {
        val cas = CasStore.inMemory()
        val shard = "s".encodeToByteArray()
        val manifest = """{"artifacts":[{"relative_path":"x","row_count":1,"size_bytes":1,"sha256":"${ContentId.of(shard).hex}","schema_id":"v1"}]}""".encodeToByteArray()
        val deep = ("{\"nested\":" + "[".repeat(65) + "0" + "]".repeat(65) + "}").encodeToByteArray()
        assertFailsWith<IllegalArgumentException> {
            ExternalGraphCandidates.retain(cas, manifest, ContentId.of(manifest), "x", shard, "decoder",
                s_[ExternalGraphRow(0, "id", deep, ContentId.of(deep).hex)])
        }
        val deepManifest = ("{\"nested\":" + "[".repeat(65) + "0" + "]".repeat(65) + ",\"artifacts\":[]}").encodeToByteArray()
        assertFailsWith<IllegalArgumentException> {
            ExternalGraphCandidates.retain(cas, deepManifest, ContentId.of(deepManifest), "x", shard, "decoder", emptySeriesOf())
        }
        val quoted = "{\"text\":\"[[[\\\"{{{\"}".encodeToByteArray()
        val retained = ExternalGraphCandidates.retain(cas, manifest, ContentId.of(manifest), "x", shard, "decoder",
            s_[ExternalGraphRow(0, "id", quoted, ContentId.of(quoted).hex)])
        assertContentEquals(quoted, cas.get(retained.rows[0]))
    }
}
