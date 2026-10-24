package borg.trikeshed.agent.modelmux

import borg.trikeshed.collections.associative.*
import borg.trikeshed.job.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.*
import borg.trikeshed.parse.confix.*
import borg.trikeshed.agent.modelmux.acp.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class BreadcrumbIndexTest {
    @Test fun collisionsRequireFullIdentityAndNeverReturnAnotherBlob() {
        val buckets = mutableMapOf<BreadcrumbRef, String>()
        var pair: Join<String, String>? = null
        for (index in 0 until 4000) {
            val text = "evidence-$index"
            val ref = BreadcrumbIndex.reference(ContentId.of(text.encodeToByteArray()))
            val previous = buckets[ref]
            if (previous != null) { pair = previous j text; break }
            buckets[ref] = text
        }
        val collision = assertNotNull(pair)
        val context = BreadcrumbContext()
        val first = context.put(BreadcrumbId("first"), "source:first", collision.a)
        val second = context.put(BreadcrumbId("second"), "source:second", collision.b)
        val a = context.index[first.id]!!
        val b = context.index[second.id]!!
        assertEquals(a.ref, b.ref)
        val ambiguous = reifyStrict(context.index.recall(a.ref, null, BreadcrumbPresence(1.0), BreadcrumbBudget())) as Map<*, *>
        assertTrue((ambiguous["error"] as String).contains("ambiguous"))
        val selected = reifyStrict(context.index.recall(a.ref, a.cid, BreadcrumbPresence(1.0), BreadcrumbBudget())) as Map<*, *>
        val ranges = selected["ranges"] as List<*>
        assertEquals(collision.a, (ranges.single() as Map<*, *>)["content"])
        assertEquals(a.cid.value, (ranges.single() as Map<*, *>)["cid"])
    }

    @Test fun macroRecallFollowsLineageWithinTheEntireSerializedBudget() {
        val context = BreadcrumbContext()
        val original = context.put(BreadcrumbId("source"), "source:code", "prefix\n" + "x".repeat(9000) + "\ncritical invariant\n")
        val summary = context.put(BreadcrumbId("summary"), "summary:micro", "A generated long title",
            children = listOf(original.id))
        val indexed = context.index[summary.id]!!
        val budget = BreadcrumbBudget(512, instructionTokens = 0, outputTokens = 0)
        val wire = context.index.recall(indexed.ref, indexed.cid, BreadcrumbPresence(1.0), budget, "critical")
        assertTrue(wire.length <= budget.chars)
        val recalled = reifyStrict(wire) as Map<*, *>
        assertTrue((recalled["omittedChars"] as Number).toInt() > 0)
        val ranges = recalled["ranges"] as List<*>
        assertTrue(ranges.any { (it as Map<*, *>)["content"].toString().contains("critical invariant") })
        for (range in ranges) {
            val row = range as Map<*, *>
            val start = (row["start"] as Number).toInt()
            val end = (row["end"] as Number).toInt()
            assertEquals(original.content.substring(start, end), row["content"])
            assertEquals(context.index[original.id]!!.cid.value, row["cid"])
        }
        val zero = reifyStrict(context.index.recall(indexed.ref, indexed.cid, BreadcrumbPresence(0.0), budget)) as Map<*, *>
        assertEquals(0, (zero["selectedChars"] as Number).toInt())
        assertEquals(0, (zero["ranges"] as List<*>).size)
        assertEquals(original.content, context.lookup(original.id)!!.content)
    }

    @Test fun typedHeritageAndReferencesSurviveCheckpointAndShareCas() {
        val cas = CasStore.inMemory()
        val context = BreadcrumbContext(index = BreadcrumbIndex(cas))
        val heritage = confixDoc(Cbor.encode(itemMapOf("bytes" to byteArrayOf(1, 2, 3).toItem(),
            "approved" to true.toItem(), "count" to 7.toItem())), Syntax.CBOR)
        val record = context.put(BreadcrumbId("record"), "source", "unchanged evidence",
            title = "Generated title carrying exact identifiers", heritage = heritage)
        val indexed = context.index[record.id]!!
        assertNotNull(context.index.facet(record.id, ConfixIndexK.TreeCursor))
        val restored = BreadcrumbContext.restore(context.state(), cas = cas)
        val after = restored.index[record.id]!!
        assertEquals(indexed.ref, after.ref)
        assertEquals(indexed.cid, after.cid)
        assertEquals(indexed.title, after.title)
        assertContentEquals(byteArrayOf(1, 2, 3), after.heritage!!.value("bytes") as ByteArray)
        assertEquals(true, after.heritage.value("approved"))
        val recalled = reifyStrict(restored.index.recall(after.ref, after.cid, BreadcrumbPresence(0.0),
            BreadcrumbBudget())) as Map<*, *>
        val nodes = ((recalled["heritage"] as Map<*, *>)["nodes"] as List<*>).map { it as Map<*, *> }
        assertTrue(nodes.any { it["tag"] == "IoBoolean" && it["value"] == true })
        val binary = nodes.single { it["tag"] == "IoBytes" }["value"] as Map<*, *>
        assertEquals("base64", binary["encoding"])
        assertEquals("AQID", binary["value"])
        val found = reifyStrict(BreadcrumbToolDispatcher(restored).dispatch(TOOL_SEARCH,
            mapOf("query" to "identifiers"))!!) as Map<*, *>
        assertEquals(indexed.cid.value, (((found["matches"] as List<*>).single()) as Map<*, *>)["cid"])
    }

    @Test fun advertisedToolsAreExecutableAndRecallRefreshesAccessWithoutChangingContent() {
        var now = 0L
        val context = BreadcrumbContext({ now })
        val record = context.put(BreadcrumbId("record"), "source", "precise evidence")
        val indexed = context.index[record.id]!!
        val request = confixDoc("""{"messages":[{"role":"user","content":"inspect memory"}]}""")
        val projected = BreadcrumbModelHost.project(request, context)
        assertEquals(listOf("breadcrumb", "recall", "search"),
            projected.getAt("tools")!!.kids.view.map { it.getAt("function", "name", src = projected.src)!!.reify(projected.src) })
        now = 1000
        val recalled = BreadcrumbToolDispatcher(context).dispatch(TOOL_RECALL, mapOf(
            "ref" to indexed.ref.value, "cid" to indexed.cid.value, "presence" to "1"))!!
        assertTrue(recalled.contains("precise evidence"))
        assertEquals(now, context.lookup(record.id)!!.lastAccessAt.value)
        assertEquals(record.content, context.lookup(record.id)!!.content)
    }

    // ---- Ranking through the shared projector -------------------------------------------------------------------

    private fun BreadcrumbContext.found(query: String, limit: Int = 32): List<Map<*, *>> =
        ((reifyStrict(index.search(query, BreadcrumbBudget(16_384), limit)) as Map<*, *>)["matches"] as List<*>).map { it as Map<*, *> }

    private fun BreadcrumbContext.foundIds(query: String): List<String> = found(query).map { it["id"] as String }

    private fun ranking(facets: ProjectionFacets = ProjectionFacets(), cas: CasStore = CasStore.inMemory()) =
        BreadcrumbContext({ 0L }, index = BreadcrumbIndex(cas, facets))

    /** Counts the blobs read back: what a search over the index costs the store. */
    private class CountingCas : CasStore() {
        var reads = 0
        override fun get(cid: ContentId): ByteArray? { reads++; return super.get(cid) }
    }

    @Test fun withFacetsTheContentOfRecordsIsRankedByTheSharedProjector() {
        val ranked = ranking()
        val scanned = BreadcrumbContext({ 0L })
        for (context in listOf(ranked, scanned)) {
            // The record with the rare word is the OLDEST. A scan only counts which query words appear, one point
            // each, so all eight tie and the newest comes first.
            context.put(BreadcrumbId("rare"), "tool:read", "alpha\na quokka sighting\nomega\n", BreadcrumbTimestamp(1_000))
            for (n in 1..7) context.put(BreadcrumbId("routine-$n"), "tool:read", "alpha $n\nroutine item $n\nomega $n\n", BreadcrumbTimestamp(1_000L + n))
        }
        val byProjector = ranked.foundIds("quokka routine")
        val byScan = scanned.foundIds("quokka routine")
        assertEquals("rare", byProjector.first(), "a rare word says more than a common one")
        assertEquals("rare", byScan.last(), "a scan has no notion of rarity, so the oldest of the ties comes last")
        assertEquals(8, byProjector.size)
        assertEquals(byScan.toSet(), byProjector.toSet(), "ranking reorders the matches and never changes which records match")
    }

    @Test fun titlesAndSourcesStillOutrankContentWhenTheIndexRanksByFacets() {
        val context = ranking()
        // A record's title is its source unless it was given one, so the source match also matches the title.
        context.put(BreadcrumbId("bytitle"), "tool:read", "also nothing\n", BreadcrumbTimestamp(1_000), title = "A quokka handbook")
        context.put(BreadcrumbId("bysource"), "tool:quokka", "nothing to see\n", BreadcrumbTimestamp(1_001))
        context.put(BreadcrumbId("bycontent"), "tool:read", "alpha\na quokka quokka quokka sighting\nomega\n", BreadcrumbTimestamp(1_002))
        assertEquals(listOf("bysource", "bytitle", "bycontent"), context.foundIds("quokka"))
    }

    @Test fun forWholeContentWordsRankingChangesTheOrderAndNotTheMatches() {
        // Every query word below is one the lexical policy keeps and occurs in the corpus only as a whole token,
        // which is the case where the two rankings agree on which records match. The tests around this one pin
        // the cases where they do not.
        val ranked = ranking()
        val scanned = BreadcrumbContext({ 0L })
        val corpus = listOf(
            "tool:read" to "the manifest is verified before blocks are cached\nreject a block whose digest differs\n",
            "tool:search" to "peer fetch is bounded by the ceiling\nthe ceiling is checked twice\n",
            "summary:micro" to "A short title about caching\n",
            "tool:bash" to "exit code 2\nerror: no such file\n",
            "tool:read" to "nothing relevant to any question below\n",
        )
        for (context in listOf(ranked, scanned)) corpus.forEachIndexed { i, (source, text) ->
            context.put(BreadcrumbId("r$i"), source, text, BreadcrumbTimestamp(1_000L + i))
        }
        var matched = 0
        for (query in listOf("manifest", "ceiling bounded", "caching", "tool", "error file", "digest", "absent", "verified cached", "")) {
            val expected = scanned.foundIds(query).toSet()
            assertEquals(expected, ranked.foundIds(query).toSet(), "'$query'")
            matched += expected.size
        }
        assertTrue(matched >= 15, "the queries really found things: $matched")
    }

    @Test fun contentIsMatchedByTokenAndFieldsStillBySubstring() {
        val ranked = ranking()
        val scanned = BreadcrumbContext({ 0L })
        for (context in listOf(ranked, scanned)) {
            context.put(BreadcrumbId("body"), "tool:read", "the identifiers are listed here\n", BreadcrumbTimestamp(1_000))
            context.put(BreadcrumbId("titled"), "tool:read", "unrelated text\n", BreadcrumbTimestamp(1_001), title = "Identifiers")
            context.put(BreadcrumbId("camel"), "tool:read", "call getUserName once\n", BreadcrumbTimestamp(1_002))
            context.put(BreadcrumbId("cached"), "tool:bash", "the build was cached\n", BreadcrumbTimestamp(1_003))
        }
        assertEquals(setOf("body", "titled"), scanned.foundIds("ident").toSet(), "a scan finds a fragment anywhere")
        assertEquals(listOf("titled"), ranked.foundIds("ident"), "a fragment still finds a title, but no longer a line of content")
        assertEquals(listOf("titled", "body"), ranked.foundIds("identifiers"), "and the title that was chosen outranks the line")
        assertEquals(emptyList(), scanned.foundIds("get_user"), "a scan needs the exact characters")
        assertEquals(listOf("camel"), ranked.foundIds("get_user"), "a query word is tokenized into the pieces a line is")
        assertEquals(listOf("cached"), scanned.foundIds("cache"), "a scan finds a word inside another form of it")
        assertEquals(emptyList(), ranked.foundIds("cache"), "a line answers only to its own tokens, not to a prefix of one")
    }

    @Test fun wordsTheLexicalPolicyDropsSelectNoContentButStillMatchTitlesAndSources() {
        val ranked = ranking()
        val scanned = BreadcrumbContext({ 0L })
        for (context in listOf(ranked, scanned)) {
            context.put(BreadcrumbId("plain"), "tool:read", "the quokka was seen at the lake\n", BreadcrumbTimestamp(1_000))
            // "the" is not a word of this line, only letters inside two of its words.
            context.put(BreadcrumbId("mother"), "tool:read", "mother and father came\n", BreadcrumbTimestamp(1_001))
            context.put(BreadcrumbId("other"), "tool:read", "nothing to report\n", BreadcrumbTimestamp(1_002))
            // The title holds "the" inside "Another"; titles and sources are matched by substring with every word.
            context.put(BreadcrumbId("titled"), "tool:read", "unrelated text\n", BreadcrumbTimestamp(1_003), title = "Another handbook")
        }
        assertEquals(setOf("titled", "plain", "mother"), scanned.foundIds("the").toSet(), "a scan finds the letters wherever they are")
        assertEquals(listOf("titled"), ranked.foundIds("the"), "a stopword selects no content, only the title and source it is a substring of")
        assertEquals(setOf("titled", "plain", "mother"), scanned.foundIds("the quokka").toSet())
        assertEquals(listOf("titled", "plain"), ranked.foundIds("the quokka"),
            "in a longer query the dropped word adds nothing from content, and the word that is kept selects its line")
        assertEquals(setOf("plain"), ranked.foundIds("quokka").toSet())
    }

    @Test fun searchingAnIndexWithNoRecordsFindsNothing() {
        val context = ranking()
        assertEquals(emptyList(), context.foundIds("quokka"))
        assertEquals(emptyList(), context.foundIds(""))
    }

    @Test fun aSearchOverUnchangedRecordsReadsNoBlobAndANewRecordCostsOne() {
        val facets = ProjectionFacets()
        val indexed = CountingCas()
        val scanning = CountingCas()
        val a = ranking(facets, indexed)
        val b = BreadcrumbContext({ 0L }, index = BreadcrumbIndex(scanning))
        for (context in listOf(a, b)) for (n in 1..5) {
            context.put(BreadcrumbId("r$n"), "tool:read", "line $n about retries\nsecond $n line\n", BreadcrumbTimestamp(1_000L + n))
        }
        indexed.reads = 0; scanning.reads = 0
        a.foundIds("retries"); b.foundIds("retries")
        assertEquals(5, indexed.reads, "the first search reads each blob once, to build the index")
        assertEquals(5, scanning.reads, "a scan reads every blob")

        indexed.reads = 0; scanning.reads = 0
        a.foundIds("retries"); a.foundIds("second line"); b.foundIds("retries"); b.foundIds("second line")
        assertEquals(0, indexed.reads, "a warm index answers any question without reading a blob")
        assertEquals(10, scanning.reads, "a scan pays for every blob on every search")

        a.put(BreadcrumbId("r6"), "tool:read", "line 6 about retries\nsecond 6 line\n", BreadcrumbTimestamp(2_000))
        indexed.reads = 0
        assertEquals(6, a.foundIds("retries").size)
        assertEquals(1, indexed.reads, "only the new record is read; the others are known by their content ids")
    }

    @Test fun aRecordAndAPeersDocumentWithTheSameBytesAreOneIndexInTheSharedMemory() {
        val facets = ProjectionFacets()
        val text = "the manifest is verified before blocks are cached\nreject a block whose digest differs\n"
        val peer = BreadcrumbProjector.project(snapshotOf("notes/verify.md" to text), "verified manifest", facets = facets)
        assertTrue(peer.excerpts.size > 0, "the peer's revision answered the question")
        val before = facets.counters()

        val context = ranking(facets)
        context.put(BreadcrumbId("local"), "tool:read", text, BreadcrumbTimestamp(1_000))
        assertEquals(listOf("local"), context.foundIds("verified manifest"))
        val after = facets.counters()
        assertEquals(before.documentsIndexed, after.documentsIndexed, "the document is found by its content id")
        assertEquals(before.featuresComputed, after.featuresComputed, "no line is tokenized twice")
        assertEquals(before.indexesBuilt, after.indexesBuilt, "the same documents are the same index whoever names them")
        assertEquals(before.indexHits + 1, after.indexHits)
    }

    @Test fun aSixteenBitCollisionStaysAmbiguousAndLeavesTheOriginalsAloneWhenRankingByFacets() {
        val buckets = mutableMapOf<BreadcrumbRef, String>()
        var pair: Join<String, String>? = null
        for (index in 0 until 4000) {
            val text = "evidence-$index"
            val ref = BreadcrumbIndex.reference(ContentId.of(text.encodeToByteArray()))
            val previous = buckets[ref]
            if (previous != null) { pair = previous j text; break }
            buckets[ref] = text
        }
        val collision = assertNotNull(pair)
        val cas = CasStore.inMemory()
        val context = ranking(cas = cas)
        val first = context.put(BreadcrumbId("first"), "source:first", collision.a, BreadcrumbTimestamp(1_000))
        val second = context.put(BreadcrumbId("second"), "source:second", collision.b, BreadcrumbTimestamp(1_001))
        val a = context.index[first.id]!!
        val b = context.index[second.id]!!
        assertEquals(a.ref, b.ref)

        val rows = context.found("evidence")
        assertEquals(setOf(a.cid.value, b.cid.value), rows.map { it["cid"] }.toSet(), "both blobs are found, each under its own identity")
        assertEquals(1, rows.map { it["ref"] }.toSet().size, "under one shared 16-bit reference")
        val ambiguous = reifyStrict(context.index.recall(a.ref, null, BreadcrumbPresence(1.0), BreadcrumbBudget())) as Map<*, *>
        assertTrue((ambiguous["error"] as String).contains("ambiguous"))
        val selected = reifyStrict(context.index.recall(b.ref, b.cid, BreadcrumbPresence(1.0), BreadcrumbBudget())) as Map<*, *>
        assertEquals(collision.b, ((selected["ranges"] as List<*>).single() as Map<*, *>)["content"])
        assertContentEquals(collision.a.encodeToByteArray(), cas.get(a.cid))
        assertContentEquals(collision.b.encodeToByteArray(), cas.get(b.cid))
        assertEquals(collision.a, context.lookup(first.id)!!.content)
    }

    @Test fun aBlankQueryListsEverythingNewestFirstWithoutTouchingTheProjector() {
        val facets = ProjectionFacets()
        val context = ranking(facets)
        for (n in 1..3) context.put(BreadcrumbId("r$n"), "tool:read", "text $n\n", BreadcrumbTimestamp(1_000L + n))
        assertEquals(listOf("r3", "r2", "r1"), context.foundIds(""))
        assertEquals(0, facets.counters().indexesBuilt)
    }

    @Test fun copiesRestoredStatesAndElementsKeepTheRankingFacets() = runTest {
        val facets = ProjectionFacets()
        val cas = CasStore.inMemory()
        val context = ranking(facets, cas)
        context.put(BreadcrumbId("r"), "tool:read", "a quokka sighting\n", BreadcrumbTimestamp(1_000))
        assertSame(facets, context.copy().index.facets)
        assertSame(facets, BreadcrumbContext.restore(context.state(), { 0L }, cas, facets).index.facets)
        assertNull(BreadcrumbContext.restore(context.state(), { 0L }, cas).index.facets, "scanning unless asked otherwise")
        assertNull(BreadcrumbElement().facets)

        val rows = linkedMapOf<String, String>()
        val store = object : BreadcrumbStore {
            override suspend fun load(): Series<Join<String, String>> = rows.entries.map { it.key j it.value }.toSeries()
            override suspend fun write(id: String, state: String?) { if (state == null) rows.remove(id) else rows[id] = state }
        }
        val first = BreadcrumbElement(store = store, cas = cas, facets = facets)
        first.transact("session") { it.put(BreadcrumbId("kept"), "tool:read", "the quokka was kept\n", BreadcrumbTimestamp(1_000)) }
        assertSame(facets, first.transact("session") { it.index.facets })

        val second = BreadcrumbElement(store = store, cas = cas, facets = facets)
        assertEquals(listOf("kept"), second.transact("session") { c -> c.found("quokka").map { it["id"] } })
        assertSame(facets, second.transact("session") { it.index.facets }, "a session restored from the store ranks through the same memory")
    }
}
