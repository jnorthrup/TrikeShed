package borg.trikeshed.parse.aac

import borg.trikeshed.tilting.zran.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AacTest {
    /** `aacid_small/annas_archive_meta__aacid__isbngrp_records__20240920T194930Z--20240920T194930Z.jsonl.seekable.zst` of the annas-archive repository (CC0), made there by t2sz: two records, 1,329 bytes of JSON Lines. */
    val isbngrp = ("28b52ffd643104350d00b6195120209307b37bf3a8af5b7df73ba6ee76ff0108aae91cc771e61480ac00440205014a0047004900d4b4b5b2984e7e5197b26e45e57d1fe9edbfadfdb9f6a21791100c1444b00b8bcefbe032206cb66603d3248954572d1b71ea58679cd3c4597eabb89f321eeb14974d93f4acacdafb7d0231c20833d64f7a9f89f4b8173dffbb66ab5ef430dc1f20ab6f48db2cac6708c8e23feb0e4fd539b65ff4f8df9f2340955f9ff4b8b6fbf4fbf69fcb30ee5ff4b2bd3153be31bf669b20ee8a2f7c51696d4b59bc662ea94cada52c9592e2d492e03c140d0de7b58c3609088eb81a035300baaea3481bb38e594e3c23b652d60ca5d518577db38dbad7c92eee3319cf9f0280aeeb2817463ca784594e98a1aef7529a23c538e76ce19535560d6fac74422b2f8614df4c25d4944e2a21ae3a4e98b18d99de5d659e6b01e8da208efffd195ccf309826209028a0c9490739eb2a968401cbf239a96c2d37995ea50166f18a4f08d50ea11c2c2f8c830bbdc1c4ea3263d0e0e619dd62c353bbd651ea97c40b2a604fd12a07ab0f2f4b3ed377b53ebb8e57f5b905ad2e7d9d00b6e2bc72e19a0396035417186e5e2a4d1811000000b4010000310500000100000000b1ea928f")
        .chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** The standard's own second example line: a container with binary data. */
    val filed = """{"aacid":"aacid__zlib3_files__20230808T051503Z__22433983__NRgUGwTJYJpkQjTbz2jA3M","data_folder":"annas_archive_data__aacid__zlib3_files__20230808T051503Z--20230808T051504Z","metadata":{"zlibrary_id":"22433983","md5":"63332c8d6514aa6081d088de96ed1d4f"}}"""

    @Test fun anIdHasItsCollectionTimestampKeyAndUuid() {
        val id = AacId.of("aacid__zlib3_records__20230808T014342Z__22433983__URsJNGy5CjokTsNT6hUmmj")
        assertEquals(AacId("zlib3_records", "20230808T014342Z", "22433983", "URsJNGy5CjokTsNT6hUmmj"), id)
        assertEquals("aacid__zlib3_records__20230808T014342Z__22433983__URsJNGy5CjokTsNT6hUmmj", id.toString())
        val bare = AacId.of("aacid__ia2_records__20240126T065114Z__36XV8fUiR5vpmLUMMamqyS")
        assertNull(bare.key)
        assertEquals("aacid__ia2_records__20240126T065114Z__36XV8fUiR5vpmLUMMamqyS", bare.toString())
        assertFailsWith<IllegalArgumentException> { AacId.of("aacid__zlib3_records__2023-08-08__1__u") }
        assertFailsWith<IllegalArgumentException> { AacId.of("zlib3_records__20230808T014342Z__1__u") }
    }

    @Test fun aNameIsARangeOfOneCollection() {
        val meta = AacRange.of("annas_archive_meta__aacid__zlib3_records__20230808T014342Z--20230808T023702Z.jsonl.zst")
        assertEquals(AacRange("annas_archive", false, "zlib3_records", "20230808T014342Z", "20230808T023702Z", ".jsonl.zst"), meta)
        assertTrue(meta.holds("20230808T014342Z") && meta.holds("20230808T023702Z"))
        assertFalse(meta.holds("20230808T023703Z"))
        val data = AacRange.of("annas_archive_data__aacid__zlib3_files__20230808T055130Z--20230808T055131Z")
        assertTrue(data.binary)
        assertEquals("", data.suffix)
        for (name in listOf(
            "annas_archive_meta__aacid__zlib3_files__20230808T051503Z--20230809T223215Z.jsonl.zst.torrent",
            "my_institute_meta__aacid__gbooks_records__20240920T051416Z--20240920T051416Z.jsonl.seekable.zst",
            "annas_archive_meta__aacid__ebscohost_records__20240823T161729Z--Wk44RExtNXgJ3346eBgRk9.jsonl",
        )) assertEquals(name, AacRange.of(name).toString())
        assertFalse(AacRange.of("annas_archive_meta__aacid__ebscohost_records__20240823T161729Z--Wk44RExtNXgJ3346eBgRk9.jsonl").holds("20240823T161729Z"))
        assertFailsWith<IllegalArgumentException> { AacRange.of("annas_archive_meta__zlib3_records__20230808T014342Z--20230808T023702Z.jsonl") }
    }

    @Test fun aLineIsItsIdItsMetadataAndItsDataFolder() {
        val record = Aac.record(filed.encodeToByteArray())
        assertEquals("22433983", record.id.key)
        assertEquals("63332c8d6514aa6081d088de96ed1d4f", (record.metadata as Map<*, *>)["md5"])
        assertEquals("annas_archive_data__aacid__zlib3_files__20230808T051503Z--20230808T051504Z/aacid__zlib3_files__20230808T051503Z__22433983__NRgUGwTJYJpkQjTbz2jA3M", record.path)
        val whole = Aac.record("""{"aacid":"aacid__zlib3_records__20230808T014342Z__22430000__hnyiZz2K44Ur5SBAuAgpg8","metadata":{"zlibrary_id":22430000,"isbns":[]}}""".encodeToByteArray())
        assertNull(whole.path)
        assertEquals(22430000L, (whole.metadata as Map<*, *>)["zlibrary_id"])
        assertFailsWith<IllegalArgumentException> { Aac.record("""{"aacid":"aacid__c__20230808T014342Z__u","metadata":1,"note":2}""".encodeToByteArray()) }
        assertFailsWith<IllegalArgumentException> { Aac.record("""{"aacid":"aacid__c__20230808T014342Z__u"}""".encodeToByteArray()) }
    }

    @Test fun aFileIsItsLinesPlainOrCompressed() {
        val text = (filed + "\n" + filed).encodeToByteArray()
        assertEquals(2, Aac.records(text).size)
        assertEquals(2, Aac.records(text + Aac.NEWLINE).size)
        assertEquals(2, Aac.records(ZstdEncoder().compress(text)).size)
        assertFailsWith<IllegalStateException> { Aac.records((filed + "\n\n" + filed).encodeToByteArray()) }
    }

    @Test fun aReleasedSeekableFileDecodesToItsRecords() = runTest {
        val text = Aac.lines(isbngrp)
        assertEquals(1329, text.size)
        val records = Aac.records(isbngrp)
        assertEquals(listOf("aacid__isbngrp_records__20240920T194930Z__A5mavEDkDnenRFaCXbGEZY", "aacid__isbngrp_records__20240920T194930Z__PgSm6KeKE2mpmrsuTPVwNj"),
            records.map { it.id.toString() })
        assertEquals(listOf("4th Estate", "Collins"), records.map { ((it.metadata as Map<*, *>)["record"] as Map<*, *>)["registrant_name"] })
        var reads = 0
        val read: suspend (Long, Int) -> ByteArray = { at, n -> reads++; isbngrp.copyOfRange(at.toInt(), at.toInt() + n) }
        assertEquals(1, Aac.table(isbngrp.size.toLong(), read)!!.frames)
        val scanned = ArrayList<String>()
        assertEquals(1, Aac.scan(isbngrp.size.toLong(), read) { scanned.add(it.id.toString()); true })
        assertEquals(records.map { it.id.toString() }, scanned)
        assertNull(Aac.table(text.size.toLong()) { at, n -> text.copyOfRange(at.toInt(), at.toInt() + n) })
    }

    @Test fun aLineCutByAFrameBoundaryIsReadWholeAndAStopFetchesNoFurtherFrame() = runTest {
        val lines = (0 until 5).map { """{"aacid":"aacid__c__20230808T01434${it}Z__${it}__u$it","metadata":{"n":$it}}""" }
        val text = lines.joinToString("\n", postfix = "\n").encodeToByteArray()
        val cuts = intArrayOf(0, lines[0].length + 10, lines[0].length + lines[1].length + 2, text.size)
        val frames = (0 until 3).map { ZstdEncoder().compress(text, cuts[it], cuts[it + 1] - cuts[it]) }
        val packed = LongArray(4); val content = LongArray(4)
        for (k in 0 until 3) { packed[k + 1] = packed[k] + frames[k].size; content[k + 1] = cuts[k + 1].toLong() }
        val file = frames.reduce { a, b -> a + b } + SeekTable(3, packed, content).bytes()
        assertContentEquals(text, Aac.lines(file))
        val fetched = ArrayList<Long>()
        val read: suspend (Long, Int) -> ByteArray = { at, n -> fetched.add(at); file.copyOfRange(at.toInt(), at.toInt() + n) }
        val seen = ArrayList<Long>()
        assertEquals(3, Aac.scan(file.size.toLong(), read) { seen.add((it.metadata as Map<*, *>)["n"] as Long); true })
        assertEquals(listOf(0L, 1L, 2L, 3L, 4L), seen)
        fetched.clear()
        assertEquals(2, Aac.scan(file.size.toLong(), read) { it.id.key != "1" })
        assertFalse(packed[2] in fetched, "the third frame is not fetched once the scan is stopped")
    }
}
