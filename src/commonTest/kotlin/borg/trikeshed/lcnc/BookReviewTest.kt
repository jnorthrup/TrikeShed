package borg.trikeshed.lcnc

import borg.trikeshed.agent.mirrormux.MirrorFetch
import borg.trikeshed.agent.modelmux.Airgap
import borg.trikeshed.lib.*
import borg.trikeshed.parse.aac.Aac
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BookReviewTest {
    /** An `ia2_records` container in the shape Anna's Archive releases, here describing an item archive.org serves openly. */
    val container = Aac.record("""{"aacid":"aacid__ia2_records__20240126T065114Z__36XV8fUiR5vpmLUMMamqyS","metadata":{"ia_id":"dictionaryof_blac_1891_00_20160211","metadata_json":{"created":1705008442,"d1":"ia601009.us.archive.org","dir":"/22/items/dictionaryof_blac_1891_00_20160211","files":[],"metadata":{"identifier":"dictionaryof_blac_1891_00_20160211","title":"A dictionary of law","creator":"Black, Henry Campbell, 1860-1927","date":"1891","language":"eng","mediatype":"texts"}}}}""".encodeToByteArray())

    fun answer(private: Boolean) = """{"dir":"/22/items/dictionaryof_blac_1891_00_20160211","workable_servers":["ia801009.us.archive.org","ia601009.us.archive.org"],
        "files":[{"name":"dictionaryof_blac_1891_00_20160211_djvu.txt","format":"DjVuTXT","size":"6294752","sha1":"6789569fdc7a7c17ad5dee0a1a595621b57ed456"${if (private) ""","private":"true"""" else ""}},
                 {"name":"dictionaryof_blac_1891_00_20160211.epub","format":"EPUB","size":"9494655","sha1":"6607267ebc60877aec763c9e2e5fd9ec086f7779"${if (private) ""","private":"true"""" else ""}}],
        "metadata":{"identifier":"dictionaryof_blac_1891_00_20160211"${if (private) ""","access-restricted-item":"true"""" else ""}}}""".encodeToByteArray()

    @Test fun theWorkIsWhatTheContainerDescribes() {
        assertEquals(mapOf("identifier" to "dictionaryof_blac_1891_00_20160211", "title" to "A dictionary of law",
            "creator" to "Black, Henry Campbell, 1860-1927", "date" to "1891", "language" to "eng"), BookReview.work(container))
        val other = Aac.record("""{"aacid":"aacid__gbooks_records__20240920T051416Z__GETzR5Zximcxw4kAvBisvM","metadata":{"id":"dNC07lyONssC"}}""".encodeToByteArray())
        assertFailsWith<IllegalArgumentException> { BookReview.work(other) }
    }

    @Test fun anOpenItemGivesOnePinnedTextAtItsMirrors() = runTest {
        val asked = ArrayList<String>()
        val review = BookReview.of(container) { asked.add(it.single()); answer(private = false) }
        assertEquals(listOf("https://archive.org/metadata/dictionaryof_blac_1891_00_20160211"), asked)
        assertEquals(1, review.prerequisites.size)
        assertEquals("sha-1=6789569fdc7a7c17ad5dee0a1a595621b57ed456", review.prerequisites[0].file.checksum)
        assertEquals(BookReview.ARCHIVE, review.prerequisites[0].source)
        assertTrue(review.withheld.isEmpty())
        val document = review.toMap()
        assertEquals("trikeshed.book-review/v1", document["schema"])
        assertEquals("aacid__ia2_records__20240126T065114Z__36XV8fUiR5vpmLUMMamqyS", document["record"])
        val prerequisite = (document["prerequisites"] as List<*>).single() as Map<*, *>
        assertEquals(listOf("https://ia801009.us.archive.org/22/items/dictionaryof_blac_1891_00_20160211/dictionaryof_blac_1891_00_20160211_djvu.txt",
            "https://ia601009.us.archive.org/22/items/dictionaryof_blac_1891_00_20160211/dictionaryof_blac_1891_00_20160211_djvu.txt"), prerequisite["uris"])
        assertEquals(emptyList<Any?>(), document["expectations"], "the scenario forms no expectation of its own")
    }

    @Test fun theBoundSelectsTheSourceTheFilesAndTheirSize() = runTest {
        val two = BookReview.of(container, PrerequisiteBound(files = 2)) { answer(private = false) }
        assertEquals(listOf("dictionaryof_blac_1891_00_20160211/dictionaryof_blac_1891_00_20160211_djvu.txt", "dictionaryof_blac_1891_00_20160211/dictionaryof_blac_1891_00_20160211.epub"),
            two.prerequisites.map { it.file.destination })
        val small = BookReview.of(container, PrerequisiteBound(bytes = 1_000_000)) { answer(private = false) }
        assertTrue(small.prerequisites.isEmpty())
        assertEquals("none of [DjVuTXT, OCR Search Text, EPUB] within 1000000 bytes", small.withheld[BookReview.ARCHIVE])
        var asked = 0
        val none = BookReview.of(container, PrerequisiteBound(sources = emptySet())) { asked++; answer(private = false) }
        assertEquals(0, asked, "a source the bound leaves out is not asked")
        assertEquals("not selected", none.withheld[BookReview.ARCHIVE])
        assertNull(none.prerequisites.firstOrNull())
    }

    /** Wikipedia's Action API answers of 24 November 2026 for an article, a disambiguation page and a title it has no article for. */
    val article = """{"batchcomplete":true,"query":{"normalized":[{"fromencoded":false,"from":"Henry_Campbell_Black","to":"Henry Campbell Black"}],"pages":[{"pageid":7460545,"ns":0,"title":"Henry Campbell Black","revisions":[{"revid":1380749915,"parentid":1370623962,"size":4111,"sha1":"3751ae50f671de280a04ba03e25b4dca4976eb23"}]}]}}"""
    val ambiguous = """{"batchcomplete":true,"query":{"pages":[{"pageid":19007,"ns":0,"title":"Mercury","revisions":[{"revid":1387325808,"parentid":1380113383,"size":8299,"sha1":"131ce498229af9ccb20c7f8cb631b754ac9b5760"}],"pageprops":{"disambiguation":""}}]}}"""
    val missing = """{"batchcomplete":true,"query":{"pages":[{"ns":0,"title":"Blacks law dictionary","missing":true}]}}"""

    @Test fun theArticlesTheBoundNamesArePinnedAtTheirRevision() = runTest {
        val asked = ArrayList<String>()
        val fetch = MirrorFetch { uris ->
            val url = uris.single()
            asked.add(url)
            when { "archive.org" in url -> answer(private = false); "Henry" in url -> article.encodeToByteArray(); "Mercury" in url -> ambiguous.encodeToByteArray(); else -> missing.encodeToByteArray() }
        }
        val bound = PrerequisiteBound(sources = setOf(BookReview.ARCHIVE, BookReview.WIKIPEDIA), articles = listOf("Henry Campbell Black", "Mercury", "blacks law dictionary"))
        val review = BookReview.of(container, bound, fetch)
        assertEquals(4, asked.size)
        assertEquals(listOf(BookReview.ARCHIVE, BookReview.WIKIPEDIA), review.prerequisites.map { it.source })
        val page = review.prerequisites[1]
        assertEquals("wikipedia/Henry_Campbell_Black@1380749915.wiki", page.file.destination)
        assertEquals(4111L, page.file.size)
        assertEquals("sha-1=3751ae50f671de280a04ba03e25b4dca4976eb23", page.file.checksum)
        assertEquals(listOf("https://en.wikipedia.org/w/index.php?title=Henry_Campbell_Black&oldid=1380749915&action=raw"), page.mirrors.uris(page.file))
        assertEquals(mapOf("wikipedia: Mercury" to "Mercury is a disambiguation page", "wikipedia: blacks law dictionary" to "Wikipedia has no article Blacks law dictionary"), review.withheld)
        asked.clear()
        val unselected = BookReview.of(container, bound.copy(sources = setOf(BookReview.ARCHIVE)), fetch)
        assertEquals(1, asked.size, "Wikipedia is not asked when the bound leaves it out")
        assertEquals("not selected", unselected.withheld["wikipedia: Mercury"])
        val small = BookReview.of(container, bound.copy(sources = setOf(BookReview.WIKIPEDIA), bytes = 4000, articles = listOf("Henry Campbell Black")), fetch)
        assertEquals("4111 bytes, over the bound's 4000", small.withheld["wikipedia: Henry Campbell Black"])
    }

    /** Project Gutenberg's description of ebook 1342 as its mirror served it on 24 November 2026, cut to the book's head and two of its files. */
    val rdf = """<?xml version="1.0" encoding="utf-8"?>
<rdf:RDF xml:base="http://www.gutenberg.org/" xmlns:dcterms="http://purl.org/dc/terms/" xmlns:pgterms="http://www.gutenberg.org/2009/pgterms/" xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
  <pgterms:ebook rdf:about="ebooks/1342">
    <dcterms:rights>Public domain in the USA.</dcterms:rights>
    <dcterms:title>Pride and Prejudice</dcterms:title>
    <dcterms:hasFormat>
      <pgterms:file rdf:about="https://www.gutenberg.org/ebooks/1342.epub.noimages">
        <dcterms:extent rdf:datatype="http://www.w3.org/2001/XMLSchema#integer">609176</dcterms:extent>
      </pgterms:file>
    </dcterms:hasFormat>
    <dcterms:hasFormat>
      <pgterms:file rdf:about="https://www.gutenberg.org/ebooks/1342.txt.utf-8">
        <dcterms:isFormatOf rdf:resource="ebooks/1342"/>
        <dcterms:extent rdf:datatype="http://www.w3.org/2001/XMLSchema#integer">772386</dcterms:extent>
        <dcterms:format><rdf:Description><rdf:value rdf:datatype="http://purl.org/dc/terms/IMT">text/plain; charset=utf-8</rdf:value></rdf:Description></dcterms:format>
      </pgterms:file>
    </dcterms:hasFormat>
  </pgterms:ebook>
</rdf:RDF>"""

    @Test fun theEbooksTheBoundNamesArePinnedByTheLengthTheirDescriptionStates() = runTest {
        val asked = ArrayList<List<String>>()
        val fetch = MirrorFetch { uris -> asked.add(uris); if ("pg1342" in uris.first()) rdf.encodeToByteArray() else rdf.replace("1342.txt.utf-8", "7.audio").encodeToByteArray() }
        val bound = PrerequisiteBound(sources = setOf(BookReview.GUTENBERG), ebooks = listOf("1342", "7", "pride"))
        val review = BookReview.of(container, bound, fetch)
        assertEquals(listOf(listOf("https://www.gutenberg.org/cache/epub/1342/pg1342.rdf", "https://gutenberg.pglaf.org/cache/epub/1342/pg1342.rdf"),
            listOf("https://www.gutenberg.org/cache/epub/7/pg7.rdf", "https://gutenberg.pglaf.org/cache/epub/7/pg7.rdf")), asked, "the description is asked of both mirrors; a name that is no number is not asked")
        val text = review.prerequisites.single()
        assertEquals(BookReview.GUTENBERG, text.source)
        assertEquals("gutenberg/pg1342.txt", text.file.destination)
        assertEquals(772386L, text.file.size)
        assertNull(text.file.checksum, "Project Gutenberg publishes no checksum")
        assertEquals(listOf("https://www.gutenberg.org/cache/epub/1342/pg1342.txt", "https://gutenberg.pglaf.org/cache/epub/1342/pg1342.txt"), text.mirrors.uris(text.file))
        assertEquals(772386L, text.mirrors.options(text.file, "/r").length)
        assertEquals(mapOf("archive.org" to "not selected", "gutenberg: 7" to "Project Gutenberg ebook 7 has no plain text",
            "gutenberg: pride" to "a Project Gutenberg ebook is named by its number, not 'pride'"), review.withheld)
        val small = BookReview.of(container, bound.copy(bytes = 700_000, ebooks = listOf("1342")), fetch)
        assertEquals("772386 bytes, over the bound's 700000", small.withheld["gutenberg: 1342"])
        val unselected = BookReview.of(container, bound.copy(sources = emptySet(), ebooks = listOf("1342")), fetch)
        assertEquals("not selected", unselected.withheld["gutenberg: 1342"])
    }

    @Test fun theNodeFormsTheScenarioWithinTheBoundItsParamsState() = runTest {
        val asked = ArrayList<String>()
        val runner = BookReview.registry { uris -> asked.add(uris.first()); if ("archive.org" in uris.first()) answer(private = false) else article.encodeToByteArray() }.getValue(BookReview.TYPE)
        val line = """{"aacid":"aacid__ia2_records__20240126T065114Z__36XV8fUiR5vpmLUMMamqyS","metadata":{"ia_id":"dictionaryof_blac_1891_00_20160211","metadata_json":{"metadata":{"title":"A dictionary of law"}}}}"""
        val out = runner.run(LcncNode("review", BookReview.TYPE, _m["sources" j "archive.org, wikipedia", "files" j "2", "articles" j "Henry Campbell Black\n"]), _m["record" j line])
        assertEquals(2, asked.size)
        val scenario = out["scenario"] as Map<*, *>
        assertEquals("A dictionary of law", (scenario["work"] as Map<*, *>)["title"])
        assertEquals(listOf("archive.org", "archive.org", "wikipedia"), (out["prerequisites"] as List<*>).map { (it as Map<*, *>)["source"] })
        assertEquals(emptyMap<String, String>(), out["withheld"])
        val fromParam = runner.run(LcncNode("review", BookReview.TYPE, _m["record" j line, "sources" j ""]), emptyMap())
        assertEquals(mapOf("archive.org" to "not selected"), fromParam["withheld"])
        assertEquals(2, asked.size, "no source selected, none asked")
        assertFailsWith<IllegalStateException> { runner.run(LcncNode("review", BookReview.TYPE), _m["record" j ""]) }
        assertFailsWith<IllegalArgumentException> { runner.run(LcncNode("review", BookReview.TYPE, _m["sources" j "libgen"]), _m["record" j line]) }
        assertFailsWith<IllegalArgumentException> { runner.run(LcncNode("review", BookReview.TYPE, _m["bytes" j "many"]), _m["record" j line]) }
        val rows = """[{"author":"person","claim":"Bailment is defined as a delivery of goods in trust."},{"author":"model","claim":"The work is a law dictionary."},{"author":"","claim":""}]"""
        val expected = listOf(mapOf("author" to "person", "standing" to "truth", "claim" to "Bailment is defined as a delivery of goods in trust."),
            mapOf("author" to "model", "standing" to "candidate", "claim" to "The work is a law dictionary."))
        val typed = runner.run(LcncNode("review", BookReview.TYPE, _m["sources" j "", "expectations" j rows]), _m["record" j line])
        assertEquals(expected, (typed["scenario"] as Map<*, *>)["expectations"], "the rows the editor's list holds")
        val cabled = runner.run(LcncNode("review", BookReview.TYPE, _m["sources" j ""]),
            _m["record" j line, "expectations" j listOf(mapOf("author" to "model", "claim" to "The work is a law dictionary."))])
        assertEquals(listOf(expected[1]), (cabled["scenario"] as Map<*, *>)["expectations"], "the rows a cable carries")
        val anonymous = assertFailsWith<IllegalArgumentException> { runner.run(LcncNode("review", BookReview.TYPE, _m["sources" j "", "expectations" j """[{"author":"engine","claim":"x"}]"""]), _m["record" j line]) }
        assertEquals("book.review: an expectation's author is person or model, not 'engine'", anonymous.message)
        assertFailsWith<IllegalArgumentException> { runner.run(LcncNode("review", BookReview.TYPE, _m["sources" j "", "expectations" j """[{"author":"person","claim":" "}]"""]), _m["record" j line]) }
        val failure = assertFailsWith<IllegalStateException> { withContext(Airgap()) { runner.run(LcncNode("review", BookReview.TYPE), _m["record" j line]) } }
        assertEquals("book.review: https://archive.org/metadata/dictionaryof_blac_1891_00_20160211 is remote, and the airgap is on", failure.message)
        assertEquals(2, asked.size, "under the airgap nothing is asked")
    }

    @Test fun aPassageIsCutFromAPrerequisiteOnlyAfterItsPinHolds() = runTest {
        val body = "ABANDON. To forsake.\n\nBAILMENT.  A delivery  of goods\nin trust.\n\nBAILOR. The party who bails."
        val sha1 = borg.trikeshed.torrent.Sha1.digest(body.encodeToByteArray()).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        val asked = ArrayList<List<String>>()
        val runner = BookReview.registry { uris -> asked.add(uris); body.encodeToByteArray() }.getValue(BookReview.PASSAGE)
        fun rows(checksum: String?, size: Int = body.length) = _m["prerequisites" j listOf(mapOf("source" to "archive.org", "destination" to "x/x_djvu.txt",
            "size" to size, "checksum" to checksum, "uris" to listOf("https://a.example/x", "https://b.example/x")))]
        val out = runner.run(LcncNode("passage", BookReview.PASSAGE, _m["from" j "BAILMENT. A delivery", "until" j "BAILOR."]), rows("sha-1=$sha1"))
        assertEquals("BAILMENT.  A delivery  of goods\nin trust.\n\n", out["text"], "the markers match across the text's own spacing")
        assertEquals(mapOf("destination" to "x/x_djvu.txt", "checksum" to "sha-1=$sha1", "offset" to 22, "length" to 43), out["range"])
        assertEquals(listOf(listOf("https://a.example/x", "https://b.example/x")), asked, "the mirrors are the engine's to try")
        assertFailsWith<IllegalStateException> { runner.run(LcncNode("passage", BookReview.PASSAGE, _m["from" j "BAILMENT.", "until" j "BAILOR."]), rows("sha-1=" + "0".repeat(40))) }
        assertFailsWith<IllegalStateException> { runner.run(LcncNode("passage", BookReview.PASSAGE, _m["from" j "BAILMENT.", "until" j "BAILOR."]), rows(null, body.length + 1)) }
        assertFailsWith<IllegalArgumentException> { runner.run(LcncNode("passage", BookReview.PASSAGE, _m["from" j "BAILOR.", "until" j "BAILMENT."]), rows("sha-1=$sha1")) }
        assertFailsWith<IllegalArgumentException> { runner.run(LcncNode("passage", BookReview.PASSAGE, _m["from" j "BAILMENT."]), rows("sha-1=$sha1")) }
        assertFailsWith<IllegalArgumentException> { runner.run(LcncNode("passage", BookReview.PASSAGE, _m["prerequisite" j "1", "from" j "BAILMENT.", "until" j "BAILOR."]), rows("sha-1=$sha1")) }
        val gapped = assertFailsWith<IllegalStateException> { withContext(Airgap()) { runner.run(LcncNode("passage", BookReview.PASSAGE, _m["from" j "BAILMENT.", "until" j "BAILOR."]), rows("sha-1=$sha1")) } }
        assertEquals("prerequisite.passage: https://a.example/x is remote, and the airgap is on", gapped.message)
    }

    @Test fun thePrefabIsOfferedTypedAndKeyed() {
        val document = LcncPresets.all().getValue(BookReview.NAME)
        val program = LcncProgramConfix.fromJson(BookReview.NAME, document)
        assertEquals(document, LcncProgramConfix.toJson(program))
        assertTrue(LcncTypeCheck.check(program, LcncContracts.all().associateBy { it.type }, strict = true).isEmpty())
        assertEquals(BookReview.info, LcncPresets.info(BookReview.NAME))
        assertSame(LcncNodeKey.BOOK_REVIEW, LcncNodeKey.of(BookReview.TYPE))
        val contract = assertNotNull(LcncContracts.find(BookReview.TYPE))
        assertEquals(BookReview.bound(emptyMap()), BookReview.bound(contract.params.mapValues { it.value.v }.filterValues { it.isNotEmpty() }),
            "the defaults the editor shows are the bound a bare node runs with")
    }

    @Test fun aLentItemGivesNothingAndSaysWhy() = runTest {
        val lent = BookReview.of(container) { answer(private = true) }
        assertTrue(lent.prerequisites.isEmpty())
        assertEquals("the item is lent, not served: none of [DjVuTXT, OCR Search Text, EPUB] is open", lent.withheld[BookReview.ARCHIVE])
        val absent = BookReview.of(container) { "{}".encodeToByteArray() }
        assertEquals("archive.org serves no such item", absent.withheld[BookReview.ARCHIVE])
    }
}
