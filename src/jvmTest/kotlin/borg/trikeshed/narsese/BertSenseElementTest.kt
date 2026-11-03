package borg.trikeshed.narsese

import borg.trikeshed.lib.*
import borg.trikeshed.userspace.nio.file.spi.InMemoryFileOperations
import kotlinx.coroutines.*
import kotlin.test.*

class BertSenseElementTest {
    @Test
    fun captureWaitsForBlockedSendAndCancellationReleasesItsPermit(): Unit = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val thirdCaptured = CompletableDeferred<Unit>()
        val fourthCaptured = CompletableDeferred<Unit>()
        val encoder = object : BertEncoder {
            override val model = "test"
            override suspend fun encode(tokens: Series<BertToken>): Series<BertVector> {
                entered.complete(Unit)
                release.await()
                return tokens α { BertVector(it, BertSpan.Exact, _a[1f, 0f]) }
            }
            override suspend fun drain() {}
        }
        val lane = BertSenseElement(this, { encoder }, capacity = 1)
        val labels = (0 until 7).toSeries() α { i ->
            BertSenseLabel("source-$i", "sense", BertToken("word", "sentence $i"))
        }
        val query = BertSenseQuery("query", BertToken("word", "query"), s_["sense"])
        try {
            val first = async { lane.read(s_[query], labels) }
            entered.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) { lane.read(s_[query], labels) }
            val third = async(start = CoroutineStart.UNDISPATCHED) {
                lane.read(1 j { thirdCaptured.complete(Unit); query }, labels)
            }
            assertTrue(thirdCaptured.isCompleted)
            val fourth = async(start = CoroutineStart.UNDISPATCHED) {
                lane.read(1 j { fourthCaptured.complete(Unit); query }, labels)
            }
            assertFalse(fourthCaptured.isCompleted)
            third.cancelAndJoin()
            withTimeout(5_000) { fourthCaptured.await() }
            fourth.cancelAndJoin()
            release.complete(Unit)
            assertEquals(BertSenseStatus.Supported, first.await().results[0].status)
            assertEquals(BertSenseStatus.Supported, second.await().results[0].status)
        } finally {
            release.complete(Unit)
            lane.drain()
        }
    }

    @Test
    fun queuedReadingOwnsItsQueriesMenusAndLabels() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val encoder = object : BertEncoder {
            override val model = "test"
            override suspend fun encode(tokens: Series<BertToken>): Series<BertVector> {
                entered.complete(Unit)
                release.await()
                return tokens α { BertVector(it, BertSpan.Exact, _a[1f, 0f]) }
            }
            override suspend fun drain() {}
        }
        val lane = BertSenseElement(this, { encoder }, capacity = 1)
        val labels = Array(7) { i -> BertSenseLabel("source-$i", "sense", BertToken("word", "sentence $i")) }
        val menu = _a["sense"]
        val queries = _a[BertSenseQuery("query", BertToken("word", "query"), menu.toSeries())]
        try {
            val first = async { lane.read(queries.toSeries(), labels.toSeries()) }
            entered.await()
            val queued = async(start = CoroutineStart.UNDISPATCHED) { lane.read(queries.toSeries(), labels.toSeries()) }
            menu[0] = "other"
            queries[0] = queries[0].copy(token = BertToken("changed", "query"))
            for (i in labels.indices) labels[i] = labels[i].copy(sense = "other")
            release.complete(Unit)
            first.await()
            val reading = queued.await()
            assertEquals(BertSenseStatus.Supported, reading.results[0].status)
            assertEquals("sense", reading.results[0].sense)
            assertTrue(reading.results[0].neighbors.view.all { it.evidence.sense == "sense" })
        } finally {
            release.complete(Unit)
            lane.drain()
        }
    }

    @Test
    fun indexFailureDoesNotCommitVectorsBeforeSuccessfulRetry() = runBlocking {
        var calls = 0
        val encoder = object : BertEncoder {
            override val model = "test"
            override suspend fun encode(tokens: Series<BertToken>): Series<BertVector> {
                calls++
                return tokens α { BertVector(it, BertSpan.Exact, _a[1f, 0f]) }
            }
            override suspend fun drain() {}
        }
        val lane = BertSenseElement(this, { encoder })
        try {
            val labels = (0 until 7).toSeries() α { i ->
                BertSenseLabel(if (i == 0) "" else "source-$i", "sense", BertToken("word", "sentence $i"))
            }
            val query = s_[BertSenseQuery("query", BertToken("word", "query"), s_["sense"])]
            assertNotNull(lane.read(query, labels).error)
            assertEquals(1, calls)
            val corrected = labels α { if (it.source.isEmpty()) it.copy(source = "source-0") else it }
            assertEquals(BertSenseStatus.Supported, lane.read(query, corrected).results[0].status)
            assertEquals(2, calls, "vectors from the failed index must not be cached")
        } finally { lane.drain() }
    }

    @Test
    fun invalidMenuIsReportedBeforeInferenceAndAValidRetrySucceeds() = runBlocking {
        var calls = 0
        val encoder = object : BertEncoder {
            override val model = "test"
            override suspend fun encode(tokens: Series<BertToken>): Series<BertVector> {
                calls++
                return tokens α { BertVector(it, BertSpan.Exact, _a[1f, 0f]) }
            }
            override suspend fun drain() {}
        }
        val lane = BertSenseElement(this, { encoder })
        try {
            val labels = (0 until 7).toSeries() α { i ->
                BertSenseLabel("source-$i", "sense", BertToken("word", "sentence $i"))
            }
            val token = BertToken("word", "query")
            val broken: Series<String> = 1 j { error("menu unavailable") }
            assertNotNull(lane.read(s_[BertSenseQuery("query", token, broken)], labels).error)
            assertEquals(0, calls)
            assertEquals(BertSenseStatus.Supported, lane.read(s_[BertSenseQuery("query", token, s_["sense"])], labels).results[0].status)
            assertEquals(1, calls)
        } finally { lane.drain() }
    }

    @Test
    fun lazyModelResolutionAndLaterIdentityChangeDoNotReuseOldVectors() = runBlocking {
        var calls = 0
        var identity = "unresolved"
        val encoder = object : BertEncoder {
            override val model: String get() = identity
            override suspend fun encode(tokens: Series<BertToken>): Series<BertVector> {
                calls++
                if (identity == "unresolved") identity = "model-v1"
                val values = if (identity == "model-v1") _a[1f, 0f] else _a[0f, 1f]
                return tokens α { BertVector(it, BertSpan.Exact, values.copyOf()) }
            }
            override suspend fun drain() {}
        }
        val lane = BertSenseElement(this, { encoder })
        try {
            val labels = (0 until 7).toSeries() α { i ->
                BertSenseLabel("source-$i", "sense", BertToken("word", "sentence $i"))
            }
            val query = s_[BertSenseQuery("query", BertToken("word", "query"), s_["sense"])]
            assertEquals("model-v1", lane.read(query, labels).model)
            assertEquals("model-v1", lane.read(query, labels).model)
            assertEquals(1, calls)
            identity = "model-v2"
            val reading = lane.read(query, labels)
            assertEquals("model-v2", reading.model)
            assertEquals(BertSenseStatus.Supported, reading.results[0].status)
            assertContentEquals(_a[0f, 1f], reading.results[0].neighbors[0].evidence.vector.values)
            assertEquals(2, calls, "a changed model must not reuse the prior vectors")
        } finally { lane.drain() }
    }

    @Test
    fun changedIdentityBetweenBatchesRefusesMixedReadingAndRetries() = runBlocking {
        var calls = 0
        var identity = "unresolved"
        val encoder = object : BertEncoder {
            override val model: String get() = identity
            override suspend fun encode(tokens: Series<BertToken>): Series<BertVector> {
                calls++
                if (calls == 1) identity = "model-v1"
                if (calls == 2) identity = "model-v2"
                return tokens α { BertVector(it, BertSpan.Exact, _a[1f, 0f]) }
            }
            override suspend fun drain() {}
        }
        val lane = BertSenseElement(this, { encoder })
        try {
            val labels = (0 until 130).toSeries() α { i ->
                BertSenseLabel("source-$i", "sense", BertToken("word", "sentence $i"))
            }
            val query = s_[BertSenseQuery("query", BertToken("word", "query"), s_["sense"])]
            val refused = lane.read(query, labels)
            assertNotNull(refused.error)
            assertEquals(BertSenseStatus.Unavailable, refused.results[0].status)
            val reading = lane.read(query, labels)
            assertEquals("model-v2", reading.model)
            assertEquals(BertSenseStatus.Supported, reading.results[0].status)
            assertEquals(4, calls, "the refused mixed reading must not populate the cache")
        } finally { lane.drain() }
    }

    @Test
    fun throwingModelGetterDoesNotTerminateTheLane() = runBlocking {
        var broken = true
        var calls = 0
        val encoder = object : BertEncoder {
            override val model: String get() = if (broken) error("model identity unavailable") else "test"
            override suspend fun encode(tokens: Series<BertToken>): Series<BertVector> {
                calls++
                return tokens α { BertVector(it, BertSpan.Exact, _a[1f, 0f]) }
            }
            override suspend fun drain() {}
        }
        val lane = BertSenseElement(this, { encoder })
        try {
            val labels = (0 until 7).toSeries() α { i ->
                BertSenseLabel("source-$i", "sense", BertToken("word", "sentence $i"))
            }
            val query = s_[BertSenseQuery("query", BertToken("word", "query"), s_["sense"])]
            val refused = withTimeout(5_000) { lane.read(query, labels) }
            assertNotNull(refused.error)
            assertEquals(BertSenseStatus.Unavailable, refused.results[0].status)
            broken = false
            val reading = withTimeout(5_000) { lane.read(query, labels) }
            assertEquals(BertSenseStatus.Supported, reading.results[0].status)
            assertEquals("test", reading.model)
            assertEquals(2, calls)
        } finally { lane.drain() }
    }

    @Test
    fun unresolvedSpansCannotVoteEvenWhenTheyCarryCoordinates() {
        val query = BertVector(BertToken("word", "query"), BertSpan.Exact, _a[1f, 0f])
        val labels: Series<BertSenseEvidence> = 7 j { i: Int ->
            BertSenseEvidence("source-$i", "sense", BertVector(BertToken("word", "sentence $i"),
                if (i == 0) BertSpan.Ambiguous else BertSpan.Exact, _a[1f, 0f]))
        }
        val index = BertSenseIndex("test", labels)
        assertEquals(BertSenseStatus.InsufficientEvidence, index.sense(query, "query", s_["sense"]).status)
        for (span in s_[BertSpan.Missing, BertSpan.Ambiguous, BertSpan.Truncated].view) {
            assertEquals(BertSenseStatus.NoVector, index.sense(query.copy(span = span), "query", s_["sense"]).status)
        }
    }

    @Test
    fun invalidSpanVectorIsNotCached(): Unit = runBlocking {
        var calls = 0
        val encoder = object : BertEncoder {
            override val model = "test"
            override suspend fun encode(tokens: Series<BertToken>): Series<BertVector> {
                calls++
                return tokens.size j { i: Int -> BertVector(tokens[i],
                    if (calls == 1 && i == 0) BertSpan.Truncated else BertSpan.Exact, _a[1f, 0f]) }
            }
            override suspend fun drain() {}
        }
        val lane = BertSenseElement(this, { encoder })
        try {
            val labels = (0 until 7).toSeries() α { i ->
                BertSenseLabel("source-$i", "sense", BertToken("word", "sentence $i"))
            }
            val queries = s_[BertSenseQuery("query", BertToken("word", "query"), s_["sense"])]
            assertNotNull(lane.read(queries, labels).error)
            assertEquals(BertSenseStatus.Supported, lane.read(queries, labels).results[0].status)
            assertEquals(2, calls)
        } finally { lane.drain() }
    }

    @Test
    fun selectedSenseMustAgreeWithTheUniqueProbabilityMaximum() {
        val files = InMemoryFileOperations()
        files.mkdirs("/books/book.jev.history/100")
        files.mkdirs("/books/book.jev")
        files.write("/books/book.jev.history/100/1.json", """{"senses":[
            {"lemma":"word","source":"superseded","sentence":"a word","sense":"old","probabilities":{"old":1}}
        ]}""".encodeToByteArray())
        files.write("/books/book.jev/1.json", """{"senses":[
            {"lemma":"word","source":"superseded","sentence":"a word","sense":"a","probabilities":{"a":0.2,"b":0.8}},
            {"lemma":"word","source":"wrong","sentence":"a word","sense":"a","probabilities":{"a":0.2,"b":0.8}},
            {"lemma":"word","source":"absent","sentence":"a word","sense":"missing","probabilities":{"a":1}},
            {"lemma":"word","source":"null","sentence":"a word","sense":null,"probabilities":{"a":1}},
            {"lemma":"word","source":"none","sentence":"a word","sense":"none","probabilities":{"a":1}},
            {"lemma":"word","source":"tie","sentence":"a word","sense":"a","probabilities":{"a":0.5,"b":0.5}},
            {"lemma":"word","source":"refused","sentence":"a word","sense":"a","refused":true,"probabilities":{"a":1}},
            {"lemma":"word","source":"agrees","sentence":"a word","sense":"b","probabilities":{"a":0.2,"b":0.8}},
            {"lemma":"word","source":"legacy","sentence":"a word","probabilities":{"a":0.2,"b":0.8}}
        ]}""".encodeToByteArray())
        val labels = BertSenseCorpus(files, "/books").labels(s_["book"])
        assertEquals(_l["agrees", "legacy"], labels.view.map { it.source })
        assertEquals(_l["b", "b"], labels.view.map { it.sense })
    }

    @Test
    fun laterJudgmentsSupersedeHistoricalLabelsEvenWhenTheyAbstainOrAreInvalid() {
        val files = InMemoryFileOperations()
        files.mkdirs("/books/book.jev.history/100")
        files.mkdirs("/books/book.jev.history/200")
        files.mkdirs("/books/book.jev")
        files.write("/books/book.jev.history/100/1.json", """{"senses":[
            {"lemma":"word","source":"refused","sentence":"a word","probabilities":{"old":1}},
            {"lemma":"word","source":"none","sentence":"a word","probabilities":{"old":1}},
            {"lemma":"word","source":"tie","sentence":"a word","probabilities":{"old":1}},
            {"lemma":"word","source":"invalid","sentence":"a word","probabilities":{"old":1}},
            {"lemma":"word","source":"missing","sentence":"a word","probabilities":{"old":1}},
            {"lemma":"word","source":"replaced","sentence":"a word","probabilities":{"old":1}},
            {"lemma":"word","source":"bert","sentence":"a word","probabilities":{"old":1}},
            {"lemma":"word","source":"untouched","sentence":"a word","probabilities":{"old":1}}
        ]}""".encodeToByteArray())
        files.write("/books/book.jev.history/200/1.json", """{"senses":[
            {"lemma":"word","source":"refused","refused":true},
            {"lemma":"word","source":"replaced","sentence":"a word","probabilities":{"middle":1}}
        ]}""".encodeToByteArray())
        files.write("/books/book.jev/1.json", """{"senses":[
            {"lemma":"word","source":"none","sentence":"a word","probabilities":{"none":1}},
            {"lemma":"word","source":"tie","sentence":"a word","probabilities":{"a":0.5,"b":0.5}},
            {"lemma":"word","source":"invalid","sentence":"a word","probabilities":{"a":2}},
            {"lemma":"word","source":"missing","probabilities":{"a":1}},
            {"lemma":"word","source":"replaced","sentence":"a word","probabilities":{"new":1}},
            {"lemma":"word","source":"bert","by":"bert","sentence":"a word","probabilities":{"generated":1}}
        ]}""".encodeToByteArray())
        val labels = BertSenseCorpus(files, "/books").labels(s_["book"])
        val bySource = labels.view.associate { it.source to it.sense }
        assertEquals(_m["replaced" j "new", "bert" j "old", "untouched" j "old"], bySource)
    }

    @Test
    fun conflictingSenseAtTiedCutoffAbstainsInEitherOrder() {
        val evidence: Series<BertSenseEvidence> = 8 j { i: Int ->
            BertSenseEvidence("source-$i", if (i == 7) "other" else "sense",
                BertVector(BertToken("word", "sentence $i"), BertSpan.Exact, _a[1f, 0f]))
        }
        val query = BertVector(BertToken("word", "query"), BertSpan.Exact, _a[1f, 0f])
        for (rows in s_[evidence, 8 j { i: Int -> evidence[7 - i] }].view) {
            val result = BertSenseIndex("test", rows).sense(query, "query", s_["sense", "other"])
            assertEquals(BertSenseStatus.Disagreement, result.status)
            assertEquals(7, result.neighbors.size)
            assertTrue(result.neighbors.view.any { it.evidence.sense == "other" })
            assertTrue(result.neighbors.view.any { it.evidence.sense == "sense" })
        }
    }

    @Test
    fun repeatedContextsAreNotIndependentVotes() {
        val query = BertVector(BertToken("word", "query word"), BertSpan.Exact, _a[1f, 0f])
        val duplicated: Series<BertSenseEvidence> = 7 j { i: Int ->
            BertSenseEvidence("source-$i", "sense", BertVector(BertToken("word", "same word"), BertSpan.Exact, _a[1f, 0f]))
        }
        val reading = BertSenseIndex("test", duplicated).sense(query, "query", s_["sense"])
        assertEquals(BertSenseStatus.InsufficientEvidence, reading.status)
        assertEquals(1, reading.neighbors.size)
    }

    @Test
    fun duplicateContextsDoNotLoadTheModel() = runBlocking {
        var loaded = false
        val lane = BertSenseElement(this, { loaded = true; error("model should not be needed") })
        val labels: Series<BertSenseLabel> = 7 j { i: Int ->
            BertSenseLabel("source-$i", "sense", BertToken("word", "same word"))
        }
        try {
            val query = BertSenseQuery("query", BertToken("word", "query word"), s_["sense"])
            val reading = lane.read(s_[query], labels)
            assertFalse(loaded)
            assertNull(reading.error)
            assertEquals(BertSenseStatus.InsufficientEvidence, reading.results[0].status)
        } finally { lane.drain() }
    }

    @Test
    fun conflictingLabelsForOneContextDoNotVote() {
        val query = BertVector(BertToken("word", "query word"), BertSpan.Exact, _a[1f, 0f])
        val evidence: Series<BertSenseEvidence> = 8 j { i: Int ->
            val sentence = if (i < 2) "same word" else "word $i"
            BertSenseEvidence("source-$i", if (i == 1) "other" else "sense",
                BertVector(BertToken("word", sentence), BertSpan.Exact, _a[1f, 0f]))
        }
        for (rows in s_[evidence, 8 j { i: Int -> evidence[7 - i] }].view) {
            val reading = BertSenseIndex("test", rows).sense(query, "query", s_["sense", "other"])
            assertEquals(BertSenseStatus.InsufficientEvidence, reading.status)
            assertEquals(6, reading.neighbors.size)
        }
    }

    @Test
    fun ambiguousOrInvalidDistributionsDoNotBecomeEvidence() {
        val files = InMemoryFileOperations()
        files.mkdirs("/books/book.jev.history")
        files.mkdirs("/books/book.jev")
        files.write("/books/book.jev/1.json", """{"senses":[
            {"lemma":"word","source":"tie","sentence":"a word","probabilities":{"a":0.5,"b":0.5}},
            {"lemma":"word","source":"invalid","sentence":"a word","probabilities":{"a":0.9,"b":-0.1}},
            {"lemma":"word","source":"empty","sentence":"a word","probabilities":{"a":0}},
            {"lemma":"word","source":"refused","sentence":"a word","refused":true,"probabilities":{"a":0.9,"b":0.1}},
            {"lemma":"word","source":"none","sentence":"a word","probabilities":{"a":0.1,"none":0.9}},
            {"lemma":"word","source":"valid","sentence":"a word","probabilities":{"a":0.6,"b":0.2}}
        ]}""".encodeToByteArray())
        val labels = BertSenseCorpus(files, "/books").labels(s_["book"])
        assertEquals(1, labels.size)
        assertEquals("valid", labels[0].source)
        assertEquals("a", labels[0].sense)
    }

    @Test
    fun oversizedTableNumberDoesNotInterruptEvidenceLoading() {
        val files = InMemoryFileOperations()
        files.mkdirs("/books/book.jev.history")
        files.mkdirs("/books/book.jev")
        files.write("/books/book.jev/99999999999999999999.json", "{}".encodeToByteArray())
        files.write("/books/book.jev/1.json", """{"senses":[{"lemma":"word","source":"s","sentence":"a word","probabilities":{"sense":1}}]}""".encodeToByteArray())
        val labels = BertSenseCorpus(files, "/books").labels(s_["book"])
        assertEquals(1, labels.size)
        assertEquals("sense", labels[0].sense)
    }

    @Test
    fun invalidBatchDoesNotPoisonCacheAndReceiptsDoNotExposeIt() = runBlocking {
        var calls = 0
        val encoder = object : BertEncoder {
            override val model = "test"
            override suspend fun encode(tokens: Series<BertToken>): Series<BertVector> {
                calls++
                return tokens.size j { i: Int ->
                    val token = tokens[if (calls == 1) 0 else i]
                    BertVector(token, BertSpan.Exact, _a[1f, 0f])
                }
            }
            override suspend fun drain() {}
        }
        val lane = BertSenseElement(this, { encoder })
        try {
            val labels = (0 until 7).toSeries() α { i ->
                BertSenseLabel("source-$i", "sense", BertToken("word", "sentence $i"))
            }
            val queries = s_[BertSenseQuery("query", BertToken("word", "query sentence"), s_["sense"])]
            assertNotNull(lane.read(queries, labels).error)
            val reading = lane.read(queries, labels)
            assertEquals(BertSenseStatus.Supported, reading.results[0].status)
            reading.results[0].neighbors[0].evidence.vector.values!![0] = Float.NaN
            assertEquals(BertSenseStatus.Supported, lane.read(queries, labels).results[0].status)
            val conflict = BertSenseLabel("source-0", "other", BertToken("word", "contradiction"))
            val conflicting: Series<BertSenseLabel> = 8 j { i: Int -> if (i == 7) conflict else labels[i] }
            assertEquals(BertSenseStatus.InsufficientEvidence, lane.read(queries, conflicting).results[0].status)
            assertEquals(2, calls)
        } finally {
            lane.drain()
        }
    }
}
