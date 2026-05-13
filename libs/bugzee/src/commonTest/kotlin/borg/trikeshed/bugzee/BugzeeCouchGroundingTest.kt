package borg.trikeshed.bugzee

import borg.trikeshed.hazelnut.*
import borg.trikeshed.lib.*
import borg.trikeshed.miniduck.DocRowVec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BugzeeCouchGroundingTest {

    // ── CouchDocument ─────────────────────────────────────────────────────

    @Test
    fun couchDocumentStoresAndRetrievesFields() {
        val doc = CouchDocument(
            id = "bug_testproduct_BUG-1",
            rev = "1-abc123",
            fields = mapOf(
                "_type" to "bugzee",
                "severity" to 7,
                "assignee" to "alice",
            ),
        )
        assertEquals("bug_testproduct_BUG-1", doc.docId)
        assertEquals("bugzee", doc.docType)
        assertEquals(7, doc.getInt("severity"))
        assertEquals("alice", doc.getString("assignee"))
    }

    @Test
    fun couchDocumentReturnsNullForMissingFields() {
        val doc = CouchDocument(id = "test", fields = emptyMap())
        assertEquals(null, doc.docType)
        assertEquals(0, doc.getInt("missing"))
        assertEquals(null, doc.getString("missing"))
    }

    @Test
    fun couchDocumentHandlesIntFieldFromString() {
        val doc = CouchDocument(
            id = "test",
            fields = mapOf("count" to "42"),
        )
        assertEquals(42, doc.getInt("count"))
    }

    // ── BugzeeDoc: Envelope ↔ Document mapping ────────────────────────────

    @Test
    fun envelopeToDocumentRoundTrip() {
        val envelope = BugzeeEnvelope(
            product = "myproduct",
            bugId = "BUG-42",
            commentId = "C-1",
            summary = "Test summary",
            description = "Test description",
            assignee = "bob",
            severity = 5,
            metadata = mapOf("source" to "web"),
        )
        val doc = BugzeeDoc.toDocument(envelope)

        assertEquals("1-abc123", doc.rev)
        assertEquals("bob", doc.getString("assignee"))
        assertEquals(8, doc.getInt("severity"))
        assertEquals("critical", doc.getString("category"))
        assertEquals("web", doc.get("metadata"))
        assertEquals(2, doc.get("attachmentCount"))
    }

    @Test
    fun toCouchRowVecMatchesExpectedKeys() {
        val row = DString("obj-1", "hello world").toCouchRowVec()
        assertEquals("obj-1", row["objectId"])
        assertEquals("STRING", row["objectType"])
        assertEquals("hello world", row["value"])
        assertEquals(11, row["valueLength"])
        assertEquals("local", row["originNode"])
    }

    @Test
    fun toCouchRowVecForDList() {
        val list = DList("list-1", listOf("a", "b", "c"), ttl = 60L)
        val row = list.toCouchRowVec()
        assertEquals("list-1", row["objectId"])
        assertEquals("LIST", row["objectType"])
        assertEquals(3, row["elementCount"])
        assertEquals("60", row["ttl"])
    }

    @Test
    fun toCouchRowVecForDHash() {
        val hash = DHash("hash-1", mapOf("k1" to "v1", "k2" to "v2"))
        val row = hash.toCouchRowVec()
        assertEquals("hash-1", row["objectId"])
        assertEquals("HASH", row["objectType"])
        assertEquals(2, row["fieldCount"])
    }

    @Test
    fun toCouchRowVecForDSet() {
        val set = DSet("set-1", setOf("x", "y", "z"))
        val row = set.toCouchRowVec()
        assertEquals("set-1", row["objectId"])
        assertEquals("SET", row["objectType"])
        assertEquals(3, row["memberCount"])
    }

    @Test
    fun toCouchRowVecForDSortedSet() {
        val ss = DSortedSet("ss-1", listOf(
            SortedSetEntry("a", 1.0),
            SortedSetEntry("b", 2.0),
        ))
        val row = ss.toCouchRowVec()
        assertEquals("ss-1", row["objectId"])
        assertEquals("SORTED_SET", row["objectType"])
        assertEquals(2, row["entryCount"])
    }

    @Test
    fun toCouchRowVecForDBitmap() {
        val bm = DBitmap("bm-1", byteArrayOf(0xFF.toByte(), 0x00.toByte()))
        val row = bm.toCouchRowVec()
        assertEquals("bm-1", row["objectId"])
        assertEquals("BITMAP", row["objectType"])
        assertEquals(2, row["byteSize"])
        assertEquals(8, row["populateCount"])
    }

    @Test
    fun toCouchRowVecForDGeo() {
        val geo = DGeo("geo-1", listOf(
            GeoPoint(1.0, 2.0, "point-a"),
        ))
        val row = geo.toCouchRowVec()
        assertEquals("geo-1", row["objectId"])
        assertEquals("GEO", row["objectType"])
        assertEquals(1, row["pointCount"])
    }

    @Test
    fun toCouchRowVecForDStream() {
        val stream = DStream("stream-1", listOf(
            StreamEntry("0-1", mapOf("field1" to "val1")),
        ), maxLen = 1000L)
        val row = stream.toCouchRowVec()
        assertEquals("stream-1", row["objectId"])
        assertEquals("STREAM", row["objectType"])
        assertEquals(1, row["entryCount"])
        assertEquals(1000, row["maxLen"])
    }

    @Test
    fun toCouchRowVecForDStreamNoMaxLen() {
        val stream = DStream("stream-1", entries = emptyList())
        val row = stream.toCouchRowVec()
        assertEquals(-1, row["maxLen"])
    }

    @Test
    fun toCouchRowVecForDHyperLogLog() {
        val hll = DHyperLogLog("hll-1", cardinality = 42L)
        val row = hll.toCouchRowVec()
        assertEquals("hll-1", row["objectId"])
        assertEquals("HYPERLOGLOG", row["objectType"])
        assertEquals(42L, row["cardinality"])
    }

    // ── Bugzee envelope to distributed object mapping ─────────────────────

    @Test
    fun envelopeToDistributedObjectAsString() {
        val envelope = BugzeeEnvelope(
            product = "p",
            bugId = "1",
            summary = "s",
            description = "desc",
        )
        val obj = envelope.toDistributedObject(BugzeeEntityType.BUG_SUMMARY)
        assertTrue(obj is DString)
        assertEquals("BUG_SUMMARY_p_1", obj.id)
        assertEquals("desc", obj.value)
    }

    @Test
    fun envelopeToDistributedObjectAsSet() {
        val envelope = BugzeeEnvelope(
            product = "p",
            bugId = "2",
            summary = "s",
            description = "d",
            metadata = mapOf("bug" to "1", "feature" to "2"),
        )
        val obj = envelope.toDistributedObject(BugzeeEntityType.BUG_LABELS)
        assertTrue(obj is DSet)
        assertEquals("bug", obj.members, listOf("bug", "feature"))
    }

    @Test
    fun envelopeToDistributedObjectAsList() {
        val envelope = BugzeeEnvelope(
            product = "p",
            bugId = "3",
            commentId = "C-1",
            summary = "s",
            description = "d",
        )
        val obj = envelope.toDistributedObject(BugzeeEntityType.BUG_COMMENTS)
        assertTrue(obj is DList)
        assertEquals(1, obj.elements.size)
        assertEquals("C-1", obj.elements[0])
    }

    @Test
    fun envelopeToDistributedObjectAsHash() {
        val envelope = BugzeeEnvelope(
            product = "p",
            bugId = "5",
            summary = "s",
            description = "d",
            assignee = "jane",
            severity = 7,
            metadata = mapOf("status" to "open"),
        )
        val obj = envelope.toDistributedObject(BugzeeEntityType.BUG_WORKFLOW)
        assertTrue(obj is DHash)
        assertEquals("open", obj.fields["status"])
        assertEquals("7", obj.fields["severity"])
        assertEquals("jane", obj.fields["assignee"])
    }

    @Test
    fun envelopeToDistributedObjectOriginNode() {
        val envelope = BugzeeEnvelope(
            product = "p",
            bugId = "1",
            summary = "s",
            description = "d",
        )
        val obj = envelope.toDistributedObject(
            BugzeeEntityType.BUG_SUMMARY,
            originNode = "node-5",
        )
        assertEquals("node-5", obj.originNode)
    }

    // ── BugzeeEntityType ↔ DistributedObjectType mapping ─────────────────

    @Test
    fun allEntityTypesMapCorrectly() {
        assertEquals(DistributedObjectType.STRING, BugzeeEntityType.BUG_SUMMARY.toDistributedObjectType())
        assertEquals(DistributedObjectType.SET, BugzeeEntityType.BUG_LABELS.toDistributedObjectType())
        assertEquals(DistributedObjectType.LIST, BugzeeEntityType.BUG_COMMENTS.toDistributedObjectType())
        assertEquals(DistributedObjectType.SORTED_SET, BugzeeEntityType.BUG_VOTES.toDistributedObjectType())
        assertEquals(DistributedObjectType.HASH, BugzeeEntityType.BUG_WORKFLOW.toDistributedObjectType())
        assertEquals(DistributedObjectType.STREAM, BugzeeEntityType.BUG_ACTIVITY_LOG.toDistributedObjectType())
        assertEquals(DistributedObjectType.BITMAP, BugzeeEntityType.BUG_FEATURE_FLAGS.toDistributedObjectType())
        assertEquals(DistributedObjectType.HYPERLOGLOG, BugzeeEntityType.BUG_DEDUP_IDS.toDistributedObjectType())
        assertEquals(DistributedObjectType.GEO, BugzeeEntityType.BUG_GEO.toDistributedObjectType())
    }

    // ── CouchView HN-style feeds ─────────────────────────────────────────

    @Test
    fun hotViewProducesExpectedMapOutput() {
        val view = CouchView.hot()
        val doc = CouchDocument(
            id = "bug_p_1",
            fields = mapOf(
                "_type" to "bugzee",
                "severity" to 7,
                "assignee" to "dev",
            ),
        )
        val output = view.mapFn.map(doc)
        assertEquals(1, output.size)
        val (key, _) = output[0]
        // score = 7 + 10 = 17
        assertTrue(key.toString().startsWith("17_"))
    }

    @Test
    fun hotViewScoreWithoutAssignee() {
        val view = CouchView.hot()
        val doc = CouchDocument(
            id = "bug_p_2",
            fields = mapOf("_type" to "bugzee", "severity" to 3),
        )
        val output = view.mapFn.map(doc)
        assertEquals(1, output.size)
        val (key, _) = output[0]
        assertEquals("3_bug_p_2", key)
    }

    @Test
    fun newViewMapsDocById() {
        val view = CouchView.new()
        val doc = CouchDocument(id = "bug_p_3", fields = mapOf("_type" to "bugzee"))
        val output = view.mapFn.map(doc)
        assertEquals(1, output.size)
        assertEquals("bug_p_3", output[0].first)
    }

    @Test
    fun topViewMapsSeverityKey() {
        val view = CouchView.top()
        val doc = CouchDocument(
            id = "bug_p_4",
            fields = mapOf("_type" to "bugzee", "severity" to 9),
        )
        val output = view.mapFn.map(doc)
        assertEquals(1, output.size)
        assertEquals("9", output[0].first)
    }

    @Test
    fun bySeverityViewCategorizesCorrectly() {
        val view = CouchView.bySeverity()

        val testCases = mapOf(
            0 to "trivial",
            1 to "low",
            2 to "low",
            3 to "medium",
            4 to "medium",
            5 to "high",
            7 to "high",
            8 to "critical",
            10 to "critical",
        )

        for ((sev, expected) in testCases) {
            val doc = CouchDocument(
                id = "bug_p_$sev",
                fields = mapOf("_type" to "bugzee", "severity" to sev),
            )
            val output = view.mapFn.map(doc)
            assertEquals(1, output.size)
            assertEquals(expected, output[0].first)
        }
    }

    // ── CouchQuery ───────────────────────────────────────────────────────

    @Test
    fun couchQueryDefaults() {
        val q = CouchQuery(viewName = "hot")
        assertEquals("_design/bugzee", q.designDoc)
        assertEquals("hot", q.viewName)
        assertEquals(false, q.reduce)
        assertEquals(25, q.limit)
        assertEquals(0, q.skip)
        assertEquals(false, q.descending)
        assertEquals(true, q.includeDocs)
        assertEquals(false, q.staleOk)
    }

    @Test
    fun couchQueryWithParameters() {
        val q = CouchQuery(
            designDoc = "_design/custom",
            viewName = "my_view",
            reduce = true,
            startKey = "a",
            endKey = "z",
            limit = 50,
            skip = 10,
            descending = true,
            includeDocs = false,
            staleOk = true,
            group = true,
            groupLevel = 2,
        )
        assertEquals("_design/custom", q.designDoc)
        assertEquals("my_view", q.viewName)
        assertEquals(true, q.reduce)
        assertEquals("a", q.startKey)
        assertEquals("z", q.endKey)
        assertEquals(50, q.limit)
        assertEquals(10, q.skip)
        assertEquals(true, q.descending)
        assertEquals(false, q.includeDocs)
        assertEquals(true, q.staleOk)
        assertEquals(true, q.group)
        assertEquals(2, q.groupLevel)
    }

    // ── CouchViewRow → DocRowVec projection ──────────────────────────────

    @Test
    fun couchViewRowToRowVec() {
        val doc = CouchDocument(id = "bug_1", rev = "1-abc")
        val row = CouchViewRow(
            id = "bug_1",
            key = "hot_score",
            value = doc.fields,
            doc = doc,
        )
        val rowVec = row.toRowVec()
        assertEquals("bug_1", rowVec["viewId"])
        assertEquals("hot_score", rowVec["viewKey"])
        assertEquals("bug_1", rowVec["docId"])
        assertEquals("1-abc", rowVec["docRev"])
    }

    // ── BugzeeCouchService ────────────────────────────────────────────────

    @Test
    fun couchServiceSaveThenLoad() {
        var storedDoc: CouchDocument? = null
        val client = object : CouchClient {
            override fun putDoc(doc: CouchDocument): CouchWriteResult {
                storedDoc = doc
                return CouchWriteResult(id = doc.id, rev = "1-new")
            }
            override fun getDoc(id: CharSequence, rev: CharSequence?): CouchDocument? = storedDoc
            override fun deleteDoc(id: CharSequence, rev: CharSequence): Boolean = false
            override fun queryView(query: CouchQuery): CouchQueryResult =
                CouchQueryResult(emptySeries(), totalRows = 0)
            override fun bulkDocs(docs: Series<CouchDocument>): Series<CouchWriteResult> =
                emptySeries()
            override fun replicate(replication: CouchReplication): CouchReplicationResult =
                CouchReplicationResult("rep-1", 0, 0)
        }

        val service = BugzeeCouchService(client)
        val result = service.save(
            BugzeeEnvelope(
                product = "prod",
                bugId = "1",
                summary = "save me",
                description = "desc",
                assignee = "dev",
                severity = 4,
            ),
        )
        assertEquals("bug_prod_1", result.id)
        assertEquals("1-new", result.rev)

        val loaded = service.load("bug_prod_1")
        assertNotNull(loaded)
        assertEquals("prod", loaded.product)
        assertEquals("1", loaded.bugId)
        assertEquals("dev", loaded.assignee)
        assertEquals(4, loaded.severity)
    }

    @Test
    fun couchServiceIndexBy() {
        val matchingDocs = listOf(
            CouchDocument(
                id = "bug_p_1",
                fields = mapOf(
                    "_type" to "bugzee",
                    "product" to "p",
                    "bugId" to "1",
                    "assignee" to "alice",
                ),
            ),
            CouchDocument(
                id = "bug_p_2",
                fields = mapOf(
                    "_type" to "bugzee",
                    "product" to "p",
                    "bugId" to "2",
                    "assignee" to "bob",
                ),
            ),
        )

        val client = object : CouchClient {
            override fun putDoc(doc: CouchDocument): CouchWriteResult =
                CouchWriteResult(id = doc.id, rev = "1-x")
            override fun getDoc(id: CharSequence, rev: CharSequence?): CouchDocument? = null
            override fun deleteDoc(id: CharSequence, rev: CharSequence): Boolean = false
            override fun queryView(query: CouchQuery): CouchQueryResult {
                val filtered = matchingDocs.filter { doc ->
                    doc.getString("assignee") == query.key
                }.map { doc ->
                    CouchViewRow(
                        id = doc.id,
                        key = query.key!!,
                        value = doc.fields,
                        doc = doc,
                    )
                }
                return CouchQueryResult(filtered.toSeries(), totalRows = filtered.size)
            }
            override fun bulkDocs(docs: Series<CouchDocument>): Series<CouchWriteResult> =
                docs.map { CouchWriteResult(id = it.id, rev = "1-x") }
            override fun replicate(replication: CouchReplication): CouchReplicationResult =
                CouchReplicationResult("rep-1", 0, 0)
        }

        val service = BugzeeCouchService(client)
        val results = service.indexBy("assignee", "alice")
        assertEquals(1, results.size)
        assertEquals("p", results[0].product)
        assertEquals("1", results[0].bugId)
    }

    @Test
    fun couchServiceProjectResult() {
        val rows = listOf(
            CouchViewRow(id = "bug_1", key = "a", doc = CouchDocument(id = "bug_1", rev = "1-abc")),
            CouchViewRow(id = "bug_2", key = "b", doc = CouchDocument(id = "bug_2", rev = "2-xyz")),
        )
        val result = CouchQueryResult(rows.toSeries(), totalRows = 2)
        val service = BugzeeCouchService(
            object : CouchClient {
                override fun putDoc(doc: CouchDocument): CouchWriteResult = CouchWriteResult(id = doc.id, rev = "1")
                override fun getDoc(id: CharSequence, rev: CharSequence?): CouchDocument? = null
                override fun deleteDoc(id: CharSequence, rev: CharSequence): Boolean = false
                override fun queryView(query: CouchQuery): CouchQueryResult = CouchQueryResult(emptySeries())
                override fun bulkDocs(docs: Series<CouchDocument>): Series<CouchWriteResult> = emptySeries()
                override fun replicate(replication: CouchReplication): CouchReplicationResult = CouchReplicationResult("rep-1", 0, 0)
            }
        )
        val projected = service.projectResult(result)
        assertEquals(2, projected.size)
        assertNotNull(projected[0])
        assertNotNull(projected[1])
    }

    @Test
    fun couchServiceFullTextSearch() {
        val mockRows = listOf(
            CouchViewRow(
                id = "bug_1",
                key = "summary:test",
                doc = CouchDocument(
                    id = "bug_1",
                    fields = mapOf(
                        "_type" to "bugzee",
                        "product" to "p",
                        "bugId" to "1",
                        "summary" to "test something",
                        "description" to "details",
                    ),
                ),
            ),
        )

        val client = object : CouchClient {
            override fun putDoc(doc: CouchDocument): CouchWriteResult = CouchWriteResult(id = doc.id, rev = "1")
            override fun getDoc(id: CharSequence, rev: CharSequence?): CouchDocument? = null
            override fun deleteDoc(id: CharSequence, rev: CharSequence): Boolean = false
            override fun queryView(query: CouchQuery): CouchQueryResult {
                assertEquals("summary:test AND description:test", query.key)
                return CouchQueryResult(mockRows.toSeries(), totalRows = 1)
            }
            override fun bulkDocs(docs: Series<CouchDocument>): Series<CouchWriteResult> = emptySeries()
            override fun replicate(replication: CouchReplication): CouchReplicationResult = CouchReplicationResult("rep-1", 0, 0)
        }

        val service = BugzeeCouchService(client)
        val results = service.fullTextSearch("test", listOf("summary", "description"))
        assertEquals(1, results.size)
        assertEquals("p", results[0].product)
    }

    @Test
    fun couchServiceReplicateTo() {
        val client = object : CouchClient {
            override fun putDoc(doc: CouchDocument): CouchWriteResult = CouchWriteResult(id = doc.id, rev = "1")
            override fun getDoc(id: CharSequence, rev: CharSequence?): CouchDocument? = null
            override fun deleteDoc(id: CharSequence, rev: CharSequence): Boolean = false
            override fun queryView(query: CouchQuery): CouchQueryResult = CouchQueryResult(emptySeries())
            override fun bulkDocs(docs: Series<CouchDocument>): Series<CouchWriteResult> = emptySeries()
            override fun replicate(replication: CouchReplication): CouchReplicationResult {
                assertEquals("my_remote_server", replication.source)
                assertEquals("remote", replication.target)
                assertEquals(true, replication.continuous)
                return CouchReplicationResult("session-1", 10, 10)
            }
        }

        val service = BugzeeCouchService(client)
        val result = service.replicateTo("remote", continuous = true)
        assertEquals("session-1", result.sessionId)
        assertEquals(10, result.docsWritten)
    }

    @Test
    fun couchServiceBulkSave() {
        val client = object : CouchClient {
            override fun putDoc(doc: CouchDocument): CouchWriteResult = CouchWriteResult(id = doc.id, rev = "1")
            override fun getDoc(id: CharSequence, rev: CharSequence?): CouchDocument? = null
            override fun deleteDoc(id: CharSequence, rev: CharSequence): Boolean = false
            override fun queryView(query: CouchQuery): CouchQueryResult = CouchQueryResult(emptySeries())
            override fun bulkDocs(docs: Series<CouchDocument>): Series<CouchWriteResult> {
                assertEquals(3, docs.size)
                return docs.map { CouchWriteResult(id = it.id, rev = "1-bulk") }
            }
            override fun replicate(replication: CouchReplication): CouchReplicationResult = CouchReplicationResult("rep-1", 0, 0)
        }

        val service = BugzeeCouchService(client)
        val envelopes = listOf(
            BugzeeEnvelope(product = "p", bugId = "1", summary = "s1", description = "d1"),
            BugzeeEnvelope(product = "p", bugId = "2", summary = "s2", description = "d2"),
            BugzeeEnvelope(product = "p", bugId = "3", summary = "s3", description = "d3"),
        )
        val results = service.bulkSave(envelopes.toSeries())
        assertEquals(3, results.size)
        assertEquals("bug_p_1", results[0].id)
        assertEquals("bug_p_2", results[1].id)
        assertEquals("bug_p_3", results[2].id)
    }

    // ── Design doc generator ─────────────────────────────────────────────

    @Test
    fun designDocContainsExpectedViews() {
        val dd = generateBugzeeDesignDoc("mydb")
        assertEquals("_design/bugzee", dd["_id"])
        assertEquals("javascript", dd["language"])

        val views = dd["views"] as Map<*, *>
        assertTrue(views.containsKey("hot"))
        assertTrue(views.containsKey("new"))
        assertTrue(views.containsKey("top"))
        assertTrue(views.containsKey("by_severity"))
        assertTrue(views.containsKey("by_product"))
        assertTrue(views.containsKey("by_assignee"))
    }

    @Test
    fun designDocViewHasMapFunction() {
        val dd = generateBugzeeDesignDoc()
        val views = dd["views"] as Map<*, *>
        val hot = views["hot"] as Map<*, *>
        assertTrue(hot.containsKey("map"))
        assertTrue(hot.containsKey("reduce"))
        val mapFn = hot["map"] as String
        assertTrue(mapFn.contains("emit"))
        assertTrue(mapFn.contains("bugzee"))
    }
}

// ── Helpers for CouchDocument field access in tests ──────────────────────────

private fun CouchDocument.get(field: CharSequence): Any? = fields[field]
