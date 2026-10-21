package borg.trikeshed.cas

import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import borg.trikeshed.narsese.CitationLinks
import borg.trikeshed.narsese.LawCites
import borg.trikeshed.parse.reify
import borg.trikeshed.parse.reifyMap
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The CursorTrie's two orders: the hash trie's page geometry and even depth under a synthetic load, the lanes'
 * append-only pages and lock-free readers, and real landings from a copy of the forge (TRIKESHED_FORGE_COPY, default
 * ~/.local/forge-cursortrie-1019): the CAS pack's records indexed by digest, and the curated books' sections and
 * citations of codified law, with the citation trie's prefix reads checked against a brute-force count.
 */
class CursorTrieTest {
    private val files = JvmFileOperations()
    private val forge = File(System.getenv("TRIKESHED_FORGE_COPY") ?: "${System.getenv("HOME")}/.local/forge-cursortrie-1019")

    private fun fold(i: Int): Long = HashTrie.fold(MessageDigest.getInstance("SHA-256").digest(i.toString().encodeToByteArray()))

    @Test
    fun hashLeavesArePagesOfEvenDepthThatInheritTheirPrefix() {
        val dir = files.createTempDir("hash-trie")
        val trie = HashTrie(files, dir)
        // One level: 256 leaves of 16 KiB; two: 65,536.
        assertEquals(712, trie.capacity(1)); assertEquals(744, trie.capacity(2))
        println("geometry: depth 1 holds ${256 * trie.capacity(1)} rows, depth 2 ${65536L * trie.capacity(2)}")
        assertTrue(256 * trie.capacity(1) in 170_000..190_000)
        assertTrue(65536L * trie.capacity(2) in 40_000_000L..50_000_000L)

        val n = 1_000_000
        val folds = LongArray(n) { fold(it) }
        for (i in 0 until n) {
            trie.put(folds[i], i * 37L, i, i and 3)
            if (i == 150_000 - 1) assertEquals(0, trie.sealedPages, "150,000 uniform rows fill no leaf of depth 1")
        }
        trie.commit()
        val depths = trie.leavesByDepth()
        println("1,000,000 rows: leaves by depth ${depths.toList()}, sealed pages ${trie.sealedPages}")
        assertEquals(256, depths[1]); assertTrue(depths[2] > 0); assertEquals(0, depths[3], "depth never differs by more than one")
        assertEquals(256, trie.sealedPages, "every depth-1 leaf sealed once, nothing deeper sealed")

        // A sealed depth-1 page holds 712 rows of 23 bytes: each row's 7-byte tail, under the one leading byte the
        // leaf's path supplies, is a digest that was put; the page stores that byte nowhere.
        val held = folds.toHashSet()
        val page = trie.page(0)
        val tails = (0 until trie.capacity(1)).map { r -> (0 until 7).fold(0L) { t, k -> (t shl 8) or (page[r * 23 + k].toLong() and 0xff) } }
        val leaf = (0 until 256).single { s -> ((s.toLong() shl 56) or tails[0]) in held }
        assertTrue(tails.all { ((leaf.toLong() shl 56) or it) in held }, "every row of the page lies under its leaf's byte")
        assertTrue(tails.zipWithNext().all { (a, b) -> a.toULong() < b.toULong() }, "a sealed page is sorted by tail")
        assertTrue((trie.capacity(1) * 23 until 16384).all { page[it] == 0.toByte() }, "a page holds rows and nothing else")

        val probe = Random(7)
        repeat(20_000) { val i = probe.nextInt(n); val r = assertNotNull(trie.get(folds[i])); assertEquals(i * 37L, r.offset); assertEquals(i, r.length) }
        assertNull(trie.get(fold(n + 1)))
        trie.close()

        // A boot replays the log: the same leaves seal against hash.dir, and no page is written again.
        val pagesBefore = File(dir, "hash.pages").length()
        val again = HashTrie(files, dir)
        assertEquals(n.toLong(), again.size)
        assertEquals(pagesBefore, File(dir, "hash.pages").length())
        repeat(5_000) { val i = probe.nextInt(n); assertEquals(i * 37L, assertNotNull(again.get(folds[i])).offset) }
        again.close()
        println("hash files: log ${File(dir, "hash.log").length()} B, pages ${File(dir, "hash.pages").length()} B, dir ${File(dir, "hash.dir").length()} B")
        files.deleteRecursively(dir)
    }

    @Test
    fun lanesRollPagesAppendOnlyAndRestoreFromTheirFiles() {
        val dir = files.createTempDir("lane-trie")
        val trie = LaneTrie(files, dir)
        val project = trie.project("p", mapOf("decisionProvider" to "jev"))
        val doc = trie.document(project, "d", mapOf("path" to "/x", "size" to 1L))
        val lane = trie.lane(doc, "r", listOf("i" to IOMemento.IoInt, "v" to IOMemento.IoLong))
        assertEquals(12, lane.width); assertEquals(1365, lane.perPage)
        val meta = trie.nodeBytes()
        trie.appender(lane).use { rows ->
            assertFailsWith<IllegalStateException> { trie.appender(lane) }
            for (i in 0 until 20_000) rows.append(i, i * 3L)
        }
        trie.commit()
        assertEquals(2, trie.forces, "one commit, two forces, 20,000 rows")
        assertEquals(meta, trie.nodeBytes(), "rows write no meta")
        // Fourteen full pages and the tail's 890 rows: the pack ends where the last row did.
        assertEquals(14 * 16384L + 890 * 12, File(dir, "lane.pages").length())
        assertEquals(14, lane.sealed)
        assertNotNull(lane.page(13)); assertNull(lane.page(14), "the tail page is the appender's alone")
        // A sealed page holds rows and nothing else.
        val p0 = lane.page(0)!!
        assertTrue((1365 * 12 until 16384).all { p0[it] == 0.toByte() })
        val sealedBefore = (0 until 14).map { ContentId.of(lane.page(it)!!).value }
        val bytes20k = trie.bytes()
        trie.close()

        val reopened = LaneTrie(files, dir)
        val back = assertNotNull(reopened.lane(lane.id))
        assertEquals(20_000L, back.size)
        assertEquals("jev", reopened.meta(project)["decisionProvider"])
        val cursor = back.cursor()
        assertEquals(19_999, cursor[19_999][0].a); assertEquals(19_999 * 3L, cursor[19_999][1].a)
        reopened.appender(back).use { rows -> for (i in 20_000 until 40_000) rows.append(i, i * 3L) }
        reopened.commit()
        assertEquals(sealedBefore, (0 until 14).map { ContentId.of(back.page(it)!!).value }, "sealed pages are never rewritten")
        val bytes40k = reopened.bytes()
        println("lane bytes: 20,000 rows $bytes20k, 40,000 rows $bytes40k")
        assertTrue(bytes40k in (2 * bytes20k - 2 * 16384)..(2 * bytes20k + 2 * 16384), "storage grows with rows")
        reopened.close()
        files.deleteRecursively(dir)
    }

    @Test
    fun readersTakeNoLockWhileTheAppenderWrites() {
        val dir = files.createTempDir("lane-readers")
        val trie = LaneTrie(files, dir)
        val lane = trie.lane(trie.document(trie.project("p"), "d"), "r", listOf("i" to IOMemento.IoInt, "v" to IOMemento.IoLong))
        val n = 100_000
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        val seen = IntArray(4)
        val readers = (0 until 4).map { k ->
            Thread {
                val random = Random(k)
                var last = 0L
                val reader = trie.reader()
                while (!done.get()) {
                    val cursor = reader.cursor(lane)
                    val c = cursor.size
                    check(c >= last) { "published count went back" }
                    last = c.toLong()
                    if (c > 0) repeat(16) { val j = random.nextInt(c); val row = cursor[j]; check(row[0].a == j && row[1].a == j * 7L) { "row $j read torn" } }
                    seen[k]++
                }
                reader.close()
            }.apply { start() }
        }
        trie.appender(lane).use { rows -> for (i in 0 until n) { rows.append(i, i * 7L); if (i % 10_000 == 9_999) trie.commit() } }
        done.set(true)
        readers.forEach { it.join() }
        trie.commit()
        println("readers' passes while 100,000 rows were appended: ${seen.toList()}; forces ${trie.forces}")
        assertTrue(seen.all { it > 0 })
        assertEquals(n.toLong(), lane.size)
        assertEquals(22, trie.forces, "ten commits during the appends and one after: two forces each")
        trie.close()
        files.deleteRecursively(dir)
    }

    @Test
    fun theForgeCopyLandsInBothOrders() {
        if (!forge.isDirectory) { println("no forge copy at $forge"); return }
        // Hash order: every record the CAS pack holds, by its digest.
        val pack = File(forge, "cas/pack")
        val trieDir = File(forge, ".oroboros/trie").also { it.mkdirs() }
        val hashDir = files.createTempDir("forge-hash")
        val hash = HashTrie(files, hashDir)
        val records = ArrayList<Triple<Long, Long, Int>>()
        RandomAccessFile(pack, "r").use { raf ->
            var at = 0L
            val header = ByteArray(37)
            while (at + 37 <= raf.length()) {
                raf.seek(at); raf.readFully(header)
                val len = java.nio.ByteBuffer.wrap(header, 1, 4).int
                if (at + 37 + len > raf.length()) break
                val f = HashTrie.fold(header.copyOfRange(5, 37))
                records.add(Triple(f, at, len)); hash.put(f, at, len, header[0].toInt())
                at += 37 + len
            }
        }
        hash.commit()
        // The newest record of a digest wins, as the pack's own index reads it.
        val newest = LinkedHashMap<Long, Triple<Long, Long, Int>>()
        for (r in records) newest[r.first] = r
        assertTrue(hash.size >= newest.size && hash.size <= records.size)
        for ((f, at, len) in newest.values) { val r = assertNotNull(hash.get(f)); assertEquals(at, r.offset); assertEquals(len, r.length) }
        val depths = hash.leavesByDepth()
        println("CAS pack ${pack.length()} B: ${records.size} records, ${newest.size} digests, ${hash.size} rows, leaves by depth ${depths.toList()}")
        assertEquals(0, depths[3], "depth never differs by more than one")
        hash.close(); files.deleteRecursively(hashDir)

        // Semantic order: the curated books' sections and citations, under the forge's own trie.
        val books = File(forge, "constellations/books")
        val names = books.listFiles { f -> f.name.endsWith(".sections.jsonl") }!!.map { it.name.removeSuffix(".sections.jsonl") }.sorted()
        val trie = LaneTrie(files, trieDir.path)
        val project = trie.project("coinbase-ua")
        val cites = ArrayList<Pair<Int, LaneTrie.Lane>>()
        val brute = HashMap<List<String>, MutableSet<Int>>()
        var sectionRows = 0L
        for ((ordinal, name) in names.withIndex()) {
            val texts = File(books, "$name.sections.jsonl").readLines().map { reify(it).toString() }
            val book = reifyMap(File(books, "$name.json").readText())
            val urns = (book["sections"] as List<*>).map { it.toString() }
            assertEquals(texts.size, urns.size, "$name: one section text per URN")
            val source = File(books, "$name.source").takeIf { it.isFile }?.readText()?.trim() ?: "unsourced"
            val (sections, cited) = LawCites.land(trie, trie.document(project, "$name@$source"), texts)
            assertEquals(texts.size.toLong(), sections.size, "$name: one row per section")
            val rows = sections.cursor()
            for (k in texts.indices) {
                val row = rows[k]
                val hex = (1..4).joinToString("") { (row[it].a as Long).toULong().toString(16).padStart(16, '0') }
                assertEquals(urns[k], "urn:sha256:$hex", "$name section $k: the row is its URN")
            }
            val expected = texts.sumOf { t -> CitationLinks.keys(t).count { it.joinToString("/").length <= LawCites.KEY } }
            assertEquals(expected.toLong(), cited.size, "$name: one row per citation")
            sectionRows += sections.size
            cites.add(ordinal to cited)
            val citeRows = cited.cursor()
            for (r in 0 until citeRows.size) {
                val key = (citeRows[r][1].a as CharSequence).toString().split('/')
                for (d in 1..key.size) brute.getOrPut(key.take(d)) { HashSet() }.add((ordinal shl 20) or (citeRows[r][0].a as Int))
            }
        }
        trie.commit()
        val law = LawCites.trie(cites)
        for ((prefix, sources) in brute) assertEquals(sources.size, law[prefix.toSeries()].basis.cardinality, "prefix $prefix")
        val usc9 = listOf("9", "U.S.C.")
        // The pair: the agreement as captured 2026-08-01 and its January 2022 edition.
        val coinbase = listOf("www.coinbase.com__legal_user_agreement_united_states.html", "www.coinbase.com-legal_user_agreement_united_states_jan_2022.html")
        val pair = coinbase.map { names.indexOf(it) }
        assertTrue(pair.all { it >= 0 })
        for (k in pair) assertEquals(370L, cites[k].second.let { trie.lane(it.document, LawCites.SECTIONS, emptyList()).size }, "${names[k]}: 370 sections")
        assertEquals(17L, cites[pair[0]].second.size, "§§ 1-16 and § 1 et seq.: seventeen citations of Title 9")
        println("books ${names.size}, section rows $sectionRows, citation rows ${cites.sumOf { it.second.size }}; " +
            "9 U.S.C.: ${law[usc9.toSeries()].citations} citations from ${law[usc9.toSeries()].basis.cardinality} sections; " +
            "Coinbase pair $coinbase; trie files ${trie.bytes()} B")
        assertTrue(pair.all { k -> law[usc9.toSeries()].basis.contains(k shl 20) || (0 until 370).any { law[usc9.toSeries()].basis.contains((k shl 20) or it) } },
            "both Coinbase editions cite Title 9")
        trie.close()
    }
}
