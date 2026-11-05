package borg.trikeshed.forge.server

import borg.trikeshed.couch.*
import borg.trikeshed.dag.*
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.job.*
import borg.trikeshed.lcnc.*
import borg.trikeshed.lcnc.NativeHornQueryTest
import borg.trikeshed.lib.*
import borg.trikeshed.module.*
import borg.trikeshed.narsese.*
import borg.trikeshed.parse.*
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.util.oroboros.CouchAttachmentGateway
import borg.trikeshed.util.oroboros.FileCasStore
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class ReviewedKifWireTest {
    @org.junit.jupiter.api.io.TempDir lateinit var directory: Path
    private var watchedDocument: String? = null
    private var watchedReads = 0
    private var watchedMutation = 9

    @Test fun reviewedKifRetainsExactEvidenceAndInvalidatesOnSourceRevision(): Unit = exercise(null)

    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "trikeshed.native.mlnix", matches = ".+")
    fun reviewedKifRunsThroughPinnedNativeSolver(): Unit = exercise(NativeHornQuery(NativeHornQueryTest().profile()))

    private fun exercise(native: NativeHornQuery?): Unit = runBlocking {
        val files = JvmFileOperations()
        val cas = FileCasStore(files, directory.resolve("cas").toString())
        val owner = SupervisorJob(coroutineContext[Job])
        val scope = CoroutineScope(Dispatchers.IO + owner + files)
        val store = CouchStoreFactory.casBacked(cas)
        val ctx = ModuleContext(Couch("reviewed-kif", store, cas), ReteNetwork(), ReteProductionRegistry(),
            null, null, ConfixBlackboard.empty(), cas, CouchAttachmentGateway(store, cas),
            ModuleRouteRegistry(), scope, { 123L }, directory.toFile())
        val corpus = InMemoryProjectCorpus()
        val observedCorpus = object : ProjectCorpus by corpus {
            override suspend fun document(project: String, id: String): ProjectDoc? {
                if (id == watchedDocument && ++watchedReads == watchedMutation)
                    corpus.put(project, id, "(subclass Changed DuringQuery)".encodeToByteArray(), "text/plain")
                return corpus.document(project, id)
            }
        }
        val text = """; (subclass Comment Hidden)
            (subclass A B)
            '(subclass Quoted Hidden)
            (not (subclass Negative Hidden))
            (=> (subclass Rule Hidden) (subclass A Hidden))
            ((Fn HigherOrder) (subclass Nested Hidden))
            (documentation A EnglishLanguage '(subclass QuotedNested Hidden))
            (documentation A EnglishLanguage "(subclass String Hidden); literal")
            (subclass B C)
            (subclass C A)
            (instance thing A)
        """.trimIndent()
        corpus.put("test", "source.kif", text.encodeToByteArray(), "text/plain")
        val original = requireNotNull(corpus.document("test", "source.kif"))
        val source = DocumentCurationLegos.projectSource(cas, corpus, original)
        val record = DocumentCurationRecord(source, null, null, "synthetic", emptySeriesOf(), emptySeriesOf())
        val receipt = cas.put(DocumentCuratorCodec.encode(record))
        val choices = DocumentTaxonomyCandidate.projection(source)
        assertEquals(_l["(subclass A B)", "(subclass B C)", "(subclass C A)", "(instance thing A)"], choices.map { it["label"] })
        val ids = choices.map { it["id"] as String }
        assertFailsWith<IllegalArgumentException> { DocumentTaxonomyCandidate.candidate(cas, receipt, _l[ids[0], ids[0]]) }
        val malformed = text + "\n(subclass X"
        val malformedCid = cas.put(malformed.encodeToByteArray())
        assertFailsWith<IllegalArgumentException> {
            DocumentTaxonomyCandidate.selections(source.copy(originalCid = malformedCid, extractedTextCid = malformedCid, text = malformed))
        }
        fun refusedKif(value: String) {
            val cid = cas.put(value.encodeToByteArray())
            assertFailsWith<IllegalArgumentException> {
                DocumentTaxonomyCandidate.selections(source.copy(originalCid = cid, extractedTextCid = cid, text = value))
            }
        }
        refusedKif("' ".repeat(65) + "(subclass A B)")
        refusedKif("(documentation A " + "' ".repeat(65) + "(subclass A B))")
        refusedKif("(documentation " + "x ".repeat(9000) + ")")
        val different = cas.put("(subclass Different Source)".encodeToByteArray())
        val altered = source.copy(originalCid = different)
        assertFailsWith<IllegalArgumentException> { DocumentTaxonomyCandidate.selections(altered) }
        val alteredRecord = record.copy(source = altered)
        assertEquals("original-mismatch", DocumentCurationLegos.reference(receipt, alteredRecord)["taxonomyStatus"])
        val alteredCid = cas.put(DocumentCuratorCodec.encode(alteredRecord))
        assertFailsWith<IllegalArgumentException> { DocumentTaxonomyCandidate.candidate(cas, alteredCid, ids) }
        fun withProject(project: String, id: String): DocumentCurationRecord = record.copy(source = source.copy(
            project = DocumentRevision(project, id, source.originalCid.value, "revision", 1), extraction = null))
        val firstFamilyRecord = withProject("a/b", "c.kif")
        val secondFamilyRecord = withProject("a", "b/c.kif")
        val firstFamily = DocumentTaxonomyCandidate.candidate(cas, cas.put(DocumentCuratorCodec.encode(firstFamilyRecord)), ids).family
        val secondFamily = DocumentTaxonomyCandidate.candidate(cas, cas.put(DocumentCuratorCodec.encode(secondFamilyRecord)), ids).family
        assertNotEquals(firstFamily, secondFamily)
        val docs = directory.resolve("documents"); Files.createDirectories(docs)
        val token = "synthetic-kif-analyst"
        Files.writeString(docs.resolve("review-principals.json"), jsonOf(_m["principals" j _l[_m[
            "tokenSha256" j ContentId.of(token.encodeToByteArray()).hex, "id" j "synthetic:analyst", "synthetic" j true]]]))
        val books = ConstellationNodes(directory.toFile(), ctx.blackboard, scope = scope, cas = cas)
        var wire: DocumentReviewWire? = null
        try {
            wire = DocumentReviewWire.open(ctx, observedCorpus, books, nativeHorn = native)
            suspend fun request(suffix: String, body: Map<String, Any?>, auth: String? = token, status: Int = 200): Map<String, Any?> {
                val path = "/api/documents/review/$suffix"
                val message = "POST $path HTTP/1.1\r\n" + (auth?.let { "Authorization: Bearer $it\r\n" } ?: "") + "\r\n" + jsonOf(body)
                val response = requireNotNull(ctx.routes.match(path)?.route("POST", path, message, null))
                assertEquals(status, response.status, response.body)
                return reifyMap(response.body)
            }
            exerciseWhitespace(corpus, cas, record, token, ::request)
            exercisePartitions(corpus, cas, record, native, token, ::request)
            if (native != null) exerciseNativeQuads(corpus, cas, record, token, docs.resolve("review.wal"), ::request)
            request("snapshot", _m["kind" j "taxonomy", "receiptCid" j receipt.value, "selection" j _l[ids[0], ids[0]]], status = 409)
            request("snapshot", _m["kind" j "taxonomy", "receiptCid" j receipt.value, "selection" j _l["kif:0:20"]], status = 409)
            val snapshot = request("snapshot", _m["kind" j "taxonomy", "receiptCid" j receipt.value, "selection" j ids])
            val snapshotCid = snapshot["snapshotCid"] as String
            val decision = _m["snapshotCid" j snapshotCid, "expectedRevision" j snapshot["revision"], "action" j "approve"]
            request("decision", decision, auth = null, status = 401)
            val approved = request("decision", decision)
            val goal = _m["snapshotCid" j snapshotCid, "expectedRevision" j approved["revision"],
                "goal" j _m["predicate" j "subclass", "subject" j "A", "object" j "C"], "limit" j 1]
            request("taxonomy-plan", goal, auth = null, status = 401)
            request("taxonomy-plan", goal + _m["review" j "approved"], status = 409)
            val walSize = Files.size(docs.resolve("review.wal"))
            request("release", _m["snapshotCid" j snapshotCid, "approvalCid" j approved["decisionCid"],
                "expectedRevision" j approved["revision"]], status = 409)
            assertEquals(walSize, Files.size(docs.resolve("review.wal")))
            val first = request("taxonomy-plan", goal)
            val second = request("taxonomy-plan", goal + _m["offset" j 1])
            assertEquals("SUPPORTED", first["status"])
            assertEquals(true, first["positivePathProven"])
            assertEquals("positive-reviewed-selected-snapshot", first["coverage"])
            assertEquals(first["plan"], second["plan"])
            assertEquals(1, ((first["counters"] as Map<*, *>)["eligible"] as Number).toInt())
            assertEquals("(subclass A B)", (((first["selected"] as List<*>).single() as Map<*, *>)["quotation"] as Map<*, *>)["quote"])
            assertEquals("(subclass B C)", (((second["selected"] as List<*>).single() as Map<*, *>)["quotation"] as Map<*, *>)["quote"])
            val plan = first["plan"] as Map<*, *>
            val exported = plan["export"] as String
            assertContains(exported, "(subclass A B)")
            assertContains(exported, "(subclass B C)")
            assertEquals(walSize, Files.size(docs.resolve("review.wal")))
            assertNull(ctx.beliefBag)
            val query = goal - "limit"
            request("taxonomy-query", query, auth = null, status = 401)
            request("taxonomy-query", query + _m["review" j "approved"], status = 409)
            if (native == null) {
                assertEquals("UNAVAILABLE", request("taxonomy-query", query)["status"])
            } else {
                for ((predicate, subject, target, expected) in _l[
                    _l["subclass", "A", "C", "SUPPORTED"],
                    _l["instance", "thing", "C", "SUPPORTED"],
                    _l["subclass", "A", "A", "SUPPORTED"],
                    _l["subclass", "C", "B", "SUPPORTED"],
                    _l["instance", "thing", "Missing", "UNKNOWN"],
                ]) {
                    val terms = _m["predicate" j predicate, "subject" j subject, "object" j target]
                    val reqBody = query + _m["goal" j terms]
                    val actual = request("taxonomy-query", reqBody)
                    val local = request("taxonomy-plan", reqBody)
                    assertEquals(expected, actual["status"])
                    assertEquals(local["status"], actual["status"])
                    assertEquals("reviewed-kif-native-result/1", actual["kind"])
                    assertEquals("reviewed-read-only-not-a-sink-permit", actual["authority"])
                    assertEquals(expected == "SUPPORTED", actual["proof"] != null)
                    assertEquals(ids.size, (actual["sourceEvidence"] as Map<*, *>).size)
                }
                assertEquals(walSize, Files.size(docs.resolve("review.wal")))
                val largeText = buildString {
                    repeat(124) { append("(subclass X").append(it).append(" Y").append(it).append(")\n") }
                    append("(subclass Y123 Z123)\n")
                    repeat(61) { append("(subclass D").append(it).append(" D").append(it + 1).append(")\n") }
                    append("(instance thing Y123)\n")
                    append("(instance DeepThing D0)\n")
                }
                corpus.put("test", "large.kif", largeText.encodeToByteArray(), "text/plain")
                val largeDoc = requireNotNull(corpus.document("test", "large.kif"))
                val largeSource = DocumentCurationLegos.projectSource(cas, corpus, largeDoc)
                val largeRecord = DocumentCurationRecord(largeSource, null, null, "synthetic", emptySeriesOf(), emptySeriesOf())
                val largeReceipt = cas.put(DocumentCuratorCodec.encode(largeRecord))
                val largeIds = DocumentTaxonomyCandidate.projection(largeSource).map { it["id"] as String }
                val largeSnapshot = request("snapshot", _m["kind" j "taxonomy", "receiptCid" j largeReceipt.value, "selection" j largeIds])
                val largeApproved = request("decision", _m["snapshotCid" j largeSnapshot["snapshotCid"],
                    "expectedRevision" j largeSnapshot["revision"], "action" j "approve"])
                val largeWal = Files.size(docs.resolve("review.wal"))
                val limited = request("taxonomy-query", _m["snapshotCid" j largeSnapshot["snapshotCid"],
                    "expectedRevision" j largeApproved["revision"], "goal" j _m[
                        "predicate" j "subclass", "subject" j "X0", "object" j "Y0"]], status = 409)
                assertContains(limited["error"] as String, "at most 123")
                val focus = _m["references" j _l[_m["snapshotCid" j largeSnapshot["snapshotCid"],
                    "expectedRevision" j largeApproved["revision"]]], "goal" j _m[
                    "predicate" j "subclass", "subject" j "X123", "object" j "Z123"]]
                val focused = request("taxonomy-union", focus)
                assertEquals("SUPPORTED", focused["status"])
                assertEquals("positive-reviewed-candidate-slice", focused["coverage"])
                assertEquals("candidate-path", focused["projection"])
                assertEquals(188, (focused["reviewedSelectionCount"] as Number).toInt())
                assertEquals(2, (focused["selectionCount"] as Number).toInt())
                assertEquals(2, (focused["sourceEvidence"] as Map<*, *>).size)
                val inheritance = request("taxonomy-union", focus + _m["goal" j _m[
                    "predicate" j "instance", "subject" j "thing", "object" j "Z123"]])
                assertEquals("SUPPORTED", inheritance["status"])
                assertEquals("candidate-path", inheritance["projection"])
                assertEquals(2, (inheritance["selectionCount"] as Number).toInt())
                val reflexive = request("taxonomy-union", focus + _m["goal" j _m[
                    "predicate" j "subclass", "subject" j "Z123", "object" j "Z123"]])
                assertEquals("SUPPORTED", reflexive["status"])
                assertEquals("candidate-path", reflexive["projection"])
                assertEquals(1, (reflexive["selectionCount"] as Number).toInt())
                val unknown = request("taxonomy-union", focus + _m["goal" j _m[
                    "predicate" j "subclass", "subject" j "X123", "object" j "Missing"]])
                assertEquals("UNKNOWN", unknown["status"])
                assertEquals("candidate-path-not-found", unknown["projection"])
                assertEquals("positive-reviewed-candidate-slice", unknown["coverage"])
                val deep = request("taxonomy-union", focus + _m["goal" j _m[
                    "predicate" j "subclass", "subject" j "D0", "object" j "D61"]])
                assertEquals("UNKNOWN", deep["status"])
                assertEquals("candidate-path-exceeds-proof-depth", deep["projection"])
                assertEquals(60, (deep["selectionCount"] as Number).toInt())
                exerciseChain(focus, token, ::request)
                assertEquals(largeWal, Files.size(docs.resolve("review.wal")))
            }
            corpus.put("test", "source.kif", "(subclass A C)".encodeToByteArray(), "text/plain")
            request("taxonomy-plan", goal, status = 409)
            if (native != null) request("taxonomy-query", query, status = 409)
        } finally {
            withContext(NonCancellable) {
                try { wire?.drain() } finally {
                    try { wire?.close(); books.drain() } finally { owner.cancelAndJoin() }
                }
            }
        }
    }

    /** Uses the actual authenticated native owner and bounded visual evaluator; no supplied result callback. */
    private suspend fun exerciseNativeQuads(corpus: InMemoryProjectCorpus, cas: CasStore, record: DocumentCurationRecord,
        token: String, wal: Path, call: suspend (String, Map<String, Any?>, String?, Int) -> Map<String, Any?>) {
        suspend fun request(body: Map<String, Any?>, auth: String? = token, status: Int = 200) = call("taxonomy-query", body, auth, status)
        val text = "(instance Tenant Person)\n(subclass Person Agent)\n(subclass Unrelated Other)\n"
        corpus.put("test", "native-proof.kif", text.encodeToByteArray(), "text/plain")
        val source = DocumentCurationLegos.projectSource(cas, corpus, requireNotNull(corpus.document("test", "native-proof.kif")))
        val receipt = cas.put(DocumentCuratorCodec.encode(record.copy(source = source)))
        val ids = DocumentTaxonomyCandidate.projection(source).map { it["id"] as String }
        val snapshot = call("snapshot", _m["kind" j "taxonomy", "receiptCid" j receipt.value, "selection" j ids], token, 200)
        val approved = call("decision", _m["snapshotCid" j snapshot["snapshotCid"], "expectedRevision" j snapshot["revision"], "action" j "approve"], token, 200)
        val body = _m["snapshotCid" j snapshot["snapshotCid"], "expectedRevision" j approved["revision"],
            "goal" j _m["predicate" j "instance", "subject" j "Tenant", "object" j "Agent"]]
        val walSize = Files.size(wal)
        watchedDocument = "native-proof.kif"; watchedReads = 0; watchedMutation = Int.MAX_VALUE
        try {
            val plain = request(body); val nativeReads = watchedReads
            assertEquals("SUPPORTED", plain["status"]); assertFalse("quadQuery" in plain)
            assertEquals(plain, request(body), "No-program path retains the original payload")
            val bindings = NativeHornQueryView.starter(plain, bindings = true)
            val premises = NativeHornQueryView.starter(plain, bindings = false)
            fun program(starter: NativeHornQueryView.Starter) = reifyMap(LcncProgramConfix.toJson(starter.program))
            val before = watchedReads
            val bindingResponse = request(body + _m["program" j program(bindings)])
            val quadReads = watchedReads - before
            assertEquals(nativeReads + 4, quadReads, "Two additional reviewed reads check the original and extracted revision before/after visual evaluation")
            val bindingRows = NativeHornQueryView.answer(plain, bindingResponse, bindings).rows
            assertEquals(_l["?x", "?y", "?z"], bindingRows.map { (it["variable"] as Map<*, *>)["value"] })
            assertEquals(_l["Tenant", "Person", "Agent"], bindingRows.map { (it["value"] as Map<*, *>)["value"] })
            val premiseResponse = request(body + _m["program" j program(premises)])
            val premiseRows = NativeHornQueryView.answer(plain, premiseResponse, premises).rows
            assertEquals(_l["0", "1"], premiseRows.map { (it["ordinal"] as Map<*, *>)["value"] })
            assertEquals(_l["(instance Tenant Person)", "(subclass Person Agent)"], premiseRows.map { (it["form"] as Map<*, *>)["value"] })
            assertEquals(3, (plain["sourceEvidence"] as Map<*, *>).size, "Unrelated approved evidence remains bound to the projection context")
            request(body + _m["program" j program(bindings)], auth = null, status = 401)
            request(body + _m["program" j null], status = 400)
            request(body + _m["program" j (program(bindings) + _m["controls" j _m["inspectionOnly" j false]])], status = 400)
            val stale = bindings.program.copy(nodes = bindings.program.nodes α { node ->
                if (node.type == SparqlGraph.PATTERN) node.copy(params = node.params + _m["graph" j "<urn:wrong:old-frame>"]) else node
            })
            val refused = request(body + _m["program" j reifyMap(LcncProgramConfix.toJson(stale))], status = 409)
            assertEquals("native_horn_projection_changed", refused["code"]); assertFalse("quadQuery" in refused)
            assertEquals(walSize, Files.size(wal))
            // The last reviewed read occurs after SPARQL; amend the actual ProjectCorpus at that read.
            watchedMutation = watchedReads + quadReads - 1
            val changed = request(body + _m["program" j program(premises)], status = 409)
            assertTrue((changed["error"] as String).contains("current")); assertFalse("quadQuery" in changed)
            assertEquals(watchedMutation, watchedReads)
            assertEquals(walSize, Files.size(wal))
            request(body, status = 409)
        } finally { watchedDocument = null; watchedReads = 0; watchedMutation = 9 }
    }

    private suspend fun exerciseChain(focus: Map<String, Any?>, token: String,
        call: suspend (String, Map<String, Any?>, String?, Int) -> Map<String, Any?>) {
        for ((predicate, subject) in _l[_l["subclass", "D0"], _l["instance", "DeepThing"]]) {
            val body = focus + _m["goal" j _m["predicate" j predicate, "subject" j subject, "object" j "D61"]]
            call("taxonomy-chain", body, null, 401)
            val result = call("taxonomy-chain", body, token, 200)
            assertEquals("SUPPORTED", result["status"])
            assertEquals("reviewed-kif-composite-result/1", result["kind"])
            assertEquals(4, (result["segmentCount"] as Number).toInt())
            val proof = result["proof"] as Map<*, *>
            assertEquals("reviewed-kif-native-chain/1", proof["kind"])
            val segments = proof["segments"] as List<*>
            assertEquals(4, segments.size)
            assertEquals(predicate, ((segments.first() as Map<*, *>)["query"] as List<*>).first())
            assertTrue(segments.all { segment ->
                val node = segment as Map<*, *>
                node["proof"] != null && node["inputCid"] != null && (node["sourceEvidence"] as Map<*, *>).isNotEmpty()
            })
        }
        val missing = focus + _m["goal" j _m["predicate" j "subclass", "subject" j "D0", "object" j "Missing"]]
        call("taxonomy-chain", missing, token, 409)
    }

    private suspend fun exerciseWhitespace(corpus: InMemoryProjectCorpus, cas: CasStore, record: DocumentCurationRecord,
        token: String, call: suspend (String, Map<String, Any?>, String?, Int) -> Map<String, Any?>) {
        val text = "(subclass\u000bAlpha\u00a0Beta)\r(instance item ; comment\r Alpha)\r"
        corpus.put("test", "whitespace.kif", text.encodeToByteArray(), "text/plain")
        val source = DocumentCurationLegos.projectSource(cas, corpus, requireNotNull(corpus.document("test", "whitespace.kif")))
        val receipt = cas.put(DocumentCuratorCodec.encode(record.copy(source = source)))
        val page = _m["receiptCid" j receipt.value, "offset" j 0, "limit" j 64]
        call("taxonomy-candidates", page, null, 401)
        val candidates = call("taxonomy-candidates", page, token, 200)
        assertEquals(2, (candidates["total"] as Number).toInt())
        val rows = candidates["selections"] as List<*>
        val ids = rows.map { (it as Map<*, *>)["id"] as String }
        assertEquals(_l["(subclass\u000bAlpha\u00a0Beta)", "(instance item ; comment\r Alpha)"],
            rows.map { (it as Map<*, *>)["label"] })
        val snapshot = call("snapshot", _m["kind" j "taxonomy", "receiptCid" j receipt.value, "selection" j ids], token, 200)
        val approval = call("decision", _m["snapshotCid" j snapshot["snapshotCid"], "expectedRevision" j snapshot["revision"],
            "action" j "approve"], token, 200)
        val goal = _m["snapshotCid" j snapshot["snapshotCid"], "expectedRevision" j approval["revision"],
            "goal" j _m["predicate" j "instance", "subject" j "item", "object" j "Beta"]]
        val result = call("taxonomy-plan", goal, token, 200)
        assertEquals("SUPPORTED", result["status"])
    }

    private suspend fun exercisePartitions(corpus: InMemoryProjectCorpus, cas: CasStore, record: DocumentCurationRecord,
        native: NativeHornQuery?, token: String,
        call: suspend (String, Map<String, Any?>, String?, Int) -> Map<String, Any?>) {
        suspend fun request(suffix: String, body: Map<String, Any?>, auth: String? = token, status: Int = 200): Map<String, Any?> =
            call(suffix, body, auth, status)
        val pagedText = buildString { repeat(300) {
            if (it == 31) append("(subclass Y0 X256)\n")
            else append("(subclass X").append(it).append(" Y").append(it).append(")\n")
        } }
        corpus.put("test", "paged.kif", pagedText.encodeToByteArray(), "text/plain")
        val pagedSource = DocumentCurationLegos.projectSource(cas, corpus, requireNotNull(corpus.document("test", "paged.kif")))
        val pagedReceipt = cas.put(DocumentCuratorCodec.encode(record.copy(source = pagedSource)))
        val pageRequest = _m["receiptCid" j pagedReceipt.value, "offset" j 256, "limit" j 8]
        request("taxonomy-candidates", pageRequest, auth = null, status = 401)
        val page = request("taxonomy-candidates", pageRequest)
        assertEquals(300, (page["total"] as Number).toInt())
        val pageRows = page["selections"] as List<*>
        assertEquals(8, pageRows.size)
        assertEquals("(subclass X256 Y256)", (pageRows.first() as Map<*, *>)["label"])
        val laterId = (pageRows.first() as Map<*, *>)["id"] as String
        val firstPage = request("taxonomy-candidates", pageRequest + _m["offset" j 0, "limit" j 32])
        val firstRows = firstPage["selections"] as List<*>
        val firstIds = _l[(firstRows[0] as Map<*, *>)["id"] as String, (firstRows[31] as Map<*, *>)["id"] as String]
        val partitionBody = _m["kind" j DocumentTaxonomyCandidate.PARTITION_KIND, "receiptCid" j pagedReceipt.value]
        request("snapshot", partitionBody + _m["selection" j (firstIds + laterId)], status = 409)
        val firstPartition = request("snapshot", partitionBody + _m["selection" j firstIds])
        val firstApproval = request("decision", _m["snapshotCid" j firstPartition["snapshotCid"],
            "expectedRevision" j firstPartition["revision"], "action" j "approve"])
        val secondPartition = request("snapshot", partitionBody + _m["selection" j _l[laterId]])
        val secondApproval = request("decision", _m["snapshotCid" j secondPartition["snapshotCid"],
            "expectedRevision" j secondPartition["revision"], "action" j "approve"])
        request("release", _m["snapshotCid" j firstPartition["snapshotCid"], "approvalCid" j firstApproval["decisionCid"],
            "expectedRevision" j firstApproval["revision"]], status = 409)
        val references = _l[_m["snapshotCid" j firstPartition["snapshotCid"], "expectedRevision" j firstApproval["revision"]],
            _m["snapshotCid" j secondPartition["snapshotCid"], "expectedRevision" j secondApproval["revision"]]]
        val union = _m["references" j references, "goal" j _m["predicate" j "subclass", "subject" j "X0", "object" j "Y256"]]
        request("taxonomy-union", union, auth = null, status = 401)
        val unionResult = request("taxonomy-union", union)
        assertEquals(if (native == null) "UNAVAILABLE" else "SUPPORTED", unionResult["status"])
        if (native != null) {
            assertEquals(3, (unionResult["selectionCount"] as Number).toInt())
            assertEquals(2, (unionResult["reads"] as List<*>).size)
            assertEquals("reviewed-read-only-not-a-sink-permit", unionResult["authority"])
            request("taxonomy-union", union + _m["references" j _l[references[0], references[0]]], status = 409)

            corpus.put("test", "racy.kif", pagedText.encodeToByteArray(), "text/plain")
            val racySource = DocumentCurationLegos.projectSource(cas, corpus, requireNotNull(corpus.document("test", "racy.kif")))
            val racyReceipt = cas.put(DocumentCuratorCodec.encode(record.copy(source = racySource)))
            val racyBody = partitionBody + _m["receiptCid" j racyReceipt.value]
            val racyFirst = request("snapshot", racyBody + _m["selection" j firstIds])
            val racyFirstApproval = request("decision", _m["snapshotCid" j racyFirst["snapshotCid"],
                "expectedRevision" j racyFirst["revision"], "action" j "approve"])
            val racySecond = request("snapshot", racyBody + _m["selection" j _l[laterId]])
            val racySecondApproval = request("decision", _m["snapshotCid" j racySecond["snapshotCid"],
                "expectedRevision" j racySecond["revision"], "action" j "approve"])
            val racyRefs = _l[_m["snapshotCid" j racyFirst["snapshotCid"], "expectedRevision" j racyFirstApproval["revision"]],
                _m["snapshotCid" j racySecond["snapshotCid"], "expectedRevision" j racySecondApproval["revision"]]]
            watchedDocument = "racy.kif"; watchedReads = 0
            request("taxonomy-union", union + _m["references" j racyRefs], status = 409)
            assertEquals(9, watchedReads, "Source changes after native execution must reject the result")
            watchedDocument = null
        }
        val firstId = DocumentTaxonomyCandidate.page(pagedSource, 0, 1).a.single()["id"] as String
        val combined = request("snapshot", _m["kind" j "taxonomy", "receiptCid" j pagedReceipt.value,
            "selection" j _l[firstId, laterId]])
        val combinedRows = (combined["snapshot"] as Map<*, *>)["selections"] as List<*>
        assertEquals(_l[firstId, laterId], combinedRows.map { (it as Map<*, *>)["id"] })
        val combinedApproval = request("decision", _m["snapshotCid" j combined["snapshotCid"],
            "expectedRevision" j combined["revision"], "action" j "approve"])
        if (native != null) {
            val combinedRef = _m["snapshotCid" j combined["snapshotCid"], "expectedRevision" j combinedApproval["revision"]]
            request("taxonomy-union", union + _m["references" j _l[references[0], combinedRef]], status = 409)
        }
        val replacement = request("snapshot", partitionBody + _m["selection" j _l[firstId]])
        request("decision", _m["snapshotCid" j replacement["snapshotCid"],
            "expectedRevision" j replacement["revision"], "action" j "approve"])
        if (native != null) request("taxonomy-union", union, status = 409)
        request("taxonomy-candidates", pageRequest + _m["offset" j 301], status = 409)
        request("taxonomy-candidates", pageRequest + _m["limit" j 65], status = 409)
        val badText = pagedText + "(subclass truncated"
        val badCid = cas.put(badText.encodeToByteArray())
        assertFailsWith<IllegalArgumentException> {
            DocumentTaxonomyCandidate.page(pagedSource.copy(originalCid = badCid, extractedTextCid = badCid, text = badText), 0, 1)
        }
        corpus.put("test", "paged.kif", "(subclass Changed Source)".encodeToByteArray(), "text/plain")
        request("taxonomy-candidates", pageRequest, status = 409)
        if (native != null) request("taxonomy-union", union + _m["references" j _l[references[1]]], status = 409)
        corpus.put("test", "corrected.kif", pagedText.encodeToByteArray(), "text/plain")
        val correctedSource = DocumentCurationLegos.projectSource(cas, corpus, requireNotNull(corpus.document("test", "corrected.kif")))
        val correctedReceipt = cas.put(DocumentCuratorCodec.encode(record.copy(source = correctedSource)))
        val correctedBody = partitionBody + _m["receiptCid" j correctedReceipt.value]
        val correctedFirst = request("snapshot", correctedBody + _m["selection" j _l[firstId]])
        val correctedSecond = request("snapshot", correctedBody + _m["selection" j _l[laterId]])
        val correctedApproval = request("decision", _m["snapshotCid" j correctedSecond["snapshotCid"],
            "expectedRevision" j correctedSecond["revision"], "action" j "approve"])
        request("decision", _m["snapshotCid" j correctedFirst["snapshotCid"],
            "expectedRevision" j correctedFirst["revision"], "action" j "correct"])
        if (native != null) request("taxonomy-union", union + _m["references" j _l[_m[
            "snapshotCid" j correctedSecond["snapshotCid"], "expectedRevision" j correctedApproval["revision"]]]], status = 409)
        request("snapshot", correctedBody + _m["selection" j _l[firstId]], status = 409)
    }

}
