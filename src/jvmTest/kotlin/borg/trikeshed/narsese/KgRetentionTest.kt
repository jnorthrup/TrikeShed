package borg.trikeshed.narsese

import borg.trikeshed.cursor.BudgetCoord
import borg.trikeshed.forge.server.BeliefWire
import borg.trikeshed.job.*
import borg.trikeshed.lcnc.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.reify
import kotlinx.coroutines.*
import kotlin.test.*

/** Synthetic fixture only: bounds are checked on stored bytes before copying on read. */
open class KgRetentionFixtureCas : CasStore(), BoundedCasReader {
    private val values = HashMap<ContentId, ByteArray>()
    val puts = mutableListOf<ContentId>()
    var syncs = 0
    override fun put(bytes: ByteArray): ContentId = ContentId.of(bytes).also {
        values[it] = bytes.copyOf(); puts.add(it)
    }
    override fun sync() { syncs++ }
    override fun get(cid: ContentId): ByteArray? = getBounded(cid, Int.MAX_VALUE - 1)
    override fun getBounded(cid: ContentId, maxBytes: Int): ByteArray? {
        val bytes = values[cid] ?: return null
        require(bytes.size <= maxBytes) { "Synthetic bounded read limit" }
        check(ContentId.of(bytes) == cid)
        return bytes.copyOf()
    }
}

class KgRetentionTest {
    private val text = "  (pays Tenant Landlord)\r\n"
    private fun mapped(value: String = text) = KgNalBridge.bridge(value).single()
    private suspend fun bag(cas: CasStore?, floor: Float = 0f, body: suspend (BeliefBagElement) -> Unit) {
        val bag = BeliefBagElement(cas = cas, priorityFloor = floor, parentJob = currentCoroutineContext()[Job])
        bag.open()
        try { body(bag) } finally {
            bag.drain(); assertTrue(bag.worker?.isCompleted == true && bag.supervisor.isCompleted)
        }
    }
    private suspend fun refused(bag: BeliefBagElement, issue: KgRetentionIssue, action: suspend () -> Unit) {
        val failure = try { action(); null } catch (failure: KgRetentionRefused) { failure }
        assertEquals(issue, assertNotNull(failure).issue)
        bag.awaitProcessed(); assertTrue(bag.worker?.isActive == true)
    }

    @Test fun originalSourceAndExactExistingDefinitionResolveBeforeAdmissionAndAfterArchive(): Unit = runBlocking {
        withTimeout(10_000) {
            val cas = KgRetentionFixtureCas()
            bag(cas, 1f) { owner ->
                val source = ContentId.of(text.encodeToByteArray())
                val statement = mapped()
                val pid = statement.propositionCid(source.value)
                // These are the actual immutable-v59 counterexample identities, not new schema IDs.
                assertEquals("sha256:19e5e36915cddb4b02111ec3b9a1f36cf87bf5122f59b6982933533d749fad3c", source.value)
                assertEquals("sha256:36b9c42d9eee916faf1300ac295675303d1921fc54b557ada6d15822e8457f2c", pid.value)
                val report = owner.ingestKg(text)
                assertContentEquals(text.encodeToByteArray(), cas.getBounded(source, KgRetention.MAX_ARTIFACT_BYTES))
                assertContentEquals(statement.propositionBytes(source.value), cas.getBounded(pid, KgRetention.MAX_ARTIFACT_BYTES))
                val definition = CanonicalCbor.decodeMap(requireNotNull(cas.get(pid)))
                assertEquals("pays", definition["predicate"])
                assertFalse(definition.containsKey("confidence") || definition.containsKey("evidence"))
                assertEquals(pid, ContentId.of(statement.copy(triplet = statement.triplet.copy(confidence = .1f)).propositionBytes(source.value)))
                assertEquals(source.value, report["sourceCid"])
                assertEquals(_l[pid.value], report["propositionCids"])
                assertEquals("VOLATILE", (report["retention"] as Map<*, *>)["storageDurability"])
                assertEquals("storage-dependent", (report["retention"] as Map<*, *>)["durabilityGuarantee"])
                assertFalse((report["retention"] as Map<*, *>).containsKey("durable"))
                val original = requireNotNull(owner.signalOf(statement.signal(source.value).angular))
                owner.ingestKg(text)
                assertEquals(original, owner.signalOf(original.angular), "Same source replay cannot increase evidence")
                owner.intake.send(BeliefIntake.DecayTick); owner.awaitProcessed()
                assertEquals(0, owner.size)
                assertNotNull(cas.get(ContentId.of(SignalCodec.encode(original))))
                assertNotNull(cas.get(source)); assertNotNull(cas.get(pid))
                owner.ingestKg(text)
                assertEquals(original, owner.signalOf(original.angular), "Archive replay must not invent evidence")
            }
        }
    }

    @Test fun missingCasAndUnboundedCasRefuseBeforeWritesWhileDirectMintStillWorks(): Unit = runBlocking {
        withTimeout(10_000) {
            val unbounded = object : CasStore() {
                var writes = 0
                override fun put(bytes: ByteArray): ContentId { writes++; return super.put(bytes) }
            }
            for ((cas, issue) in s_[null j KgRetentionIssue.MISSING_CAS, unbounded j KgRetentionIssue.BOUNDED_READER_UNAVAILABLE].view) {
                bag(cas) { owner ->
                    refused(owner, issue) { owner.ingestKg(text) }
                    assertEquals(0, owner.size)
                    val signal = mapped().signal(ContentId.of(text.encodeToByteArray()).value)
                    owner.submit(BeliefIntake.Mint(signal, BudgetCoord(.5f, .5f, .5f)))
                    assertEquals(signal, owner.signalOf(signal.angular))
                }
            }
            assertEquals(0, unbounded.writes)
        }
    }

    @Test fun everyArtifactMustReadBackBeforeAnyImportedMintAndRefusalKeepsActorHealthy(): Unit = runBlocking {
        withTimeout(10_000) {
            val input = "(pays Tenant Landlord) (owes Tenant Agent)"
            lateinit var owner: BeliefBagElement
            var fail = true
            var reads = 0
            val cas = object : KgRetentionFixtureCas() {
                override fun getBounded(cid: ContentId, maxBytes: Int): ByteArray? {
                    reads++
                    if (fail) {
                        assertEquals(0, owner.size, "No assertion before ALL retention readbacks")
                        if (reads == 3) return null
                    }
                    return super.getBounded(cid, maxBytes)
                }
            }
            bag(cas) { current ->
                owner = current
                refused(owner, KgRetentionIssue.READBACK) { owner.ingestKg(input) }
                assertEquals(0, owner.size); assertEquals(3, cas.puts.size); assertEquals(1, cas.syncs)
                fail = false
                assertEquals(2, owner.ingestKg(input)["processedAssertions"])
                assertEquals(2, owner.size)
            }
        }
    }

    @Test fun wrongPutSyncAndCorruptReadbackNeverMintOrStopTheOwner(): Unit = runBlocking {
        withTimeout(10_000) {
            val broken = s_[
                object : KgRetentionFixtureCas() {
                    override fun put(bytes: ByteArray) = ContentId.of("wrong acknowledgement".encodeToByteArray())
                } j KgRetentionIssue.PUT,
                object : KgRetentionFixtureCas() { override fun sync() { error("Synthetic sync failure") } } j KgRetentionIssue.SYNC,
                object : KgRetentionFixtureCas() {
                    override fun getBounded(cid: ContentId, maxBytes: Int) = "wrong readback".encodeToByteArray()
                } j KgRetentionIssue.READBACK,
            ]
            for ((cas, issue) in broken.view) bag(cas) { owner ->
                refused(owner, issue) { owner.ingestKg(text) }; assertEquals(0, owner.size)
            }
        }
    }

    @Test fun countByteAggregateAndIdentityLimitsRefuseBeforeWrites(): Unit = runBlocking {
        withTimeout(10_000) {
            assertEquals(KgRetentionIssue.LIMIT, assertFailsWith<KgRetentionRefused> {
                KgRetention.prepare(" ".repeat(KgRetention.MAX_SOURCE_CHARS + 1), emptyList())
            }.issue)
            val one = "x".encodeToByteArray()
            val source = KgRetention.Artifact(ContentId.of(one), one)
            assertEquals(KgRetentionIssue.LIMIT, assertFailsWith<KgRetentionRefused> {
                KgRetention.Batch(source, (KgRetention.MAX_DEFINITIONS + 1) j { _: Int -> error("Oversized oracle must not execute") })
            }.issue)
            val oversized = ByteArray(KgRetention.MAX_ARTIFACT_BYTES + 1)
            assertEquals(KgRetentionIssue.LIMIT, assertFailsWith<KgRetentionRefused> {
                KgRetention.Artifact(ContentId.of(oversized), oversized)
            }.issue)
            val cas = KgRetentionFixtureCas()
            bag(cas) { owner ->
                val bad = KgRetention.Artifact(ContentId.of("different".encodeToByteArray()), one)
                refused(owner, KgRetentionIssue.IDENTITY) { owner.retainKg(KgRetention.Batch(bad, emptySeriesOf())) }
                val many = (0 until KgRetention.MAX_DEFINITIONS).toSeries() α { i ->
                    val bytes = ("$i:" + "x".repeat(2048)).encodeToByteArray()
                    KgRetention.Artifact(ContentId.of(bytes), bytes)
                }
                refused(owner, KgRetentionIssue.LIMIT) { owner.retainKg(KgRetention.Batch(source, many)) }
                assertTrue(cas.puts.isEmpty()); assertEquals(0, owner.size)
            }
        }
    }

    @Test fun httpAndLcncUseTheSameRetentionAndIdentityReadSurfaces(): Unit = runBlocking {
        withTimeout(10_000) {
            val cas = KgRetentionFixtureCas()
            bag(cas) { owner ->
                val review = TurnReviewElement(owner); review.open()
                try {
                    val wire = BeliefWire(owner, review, null)
                    val response = requireNotNull(wire.route("POST", "/api/beliefs/kg", "POST /api/beliefs/kg HTTP/1.1\r\n\r\n$text", null))
                    assertEquals(200, response.status)
                    val report = reify(response.body) as Map<*, *>
                    val pid = (report["propositionCids"] as List<*>).single() as String
                    assertNotNull(cas.get(ContentId(report["sourceCid"] as String)))
                    assertNotNull(cas.get(ContentId(pid)))
                    val lcnc = requireNotNull(KgNodes.registry { owner.ingestKg(it) }["kg.ingest"])
                        .run(LcncNode("import", "kg.ingest"), _m["text" j text])["report"] as Map<*, *>
                    assertEquals(report["sourceCid"], lcnc["sourceCid"])
                    assertEquals(report["propositionCids"], lcnc["propositionCids"])
                    val rows = (reify(requireNotNull(wire.route("GET", "/api/beliefs", "", null)).body) as Map<*, *>)["beliefs"] as List<*>
                    assertEquals(pid, (rows.single() as Map<*, *>)["propositionCid"])
                    assertEquals(1, owner.size)
                } finally { review.drain() }
            }
            bag(null) { owner ->
                val review = TurnReviewElement(owner); review.open()
                try {
                    val response = requireNotNull(BeliefWire(owner, review, null).route("POST", "/api/beliefs/kg", text, null))
                    assertEquals(503, response.status)
                    val report = reify(response.body) as Map<*, *>
                    assertEquals("MISSING_CAS", (report["retention"] as Map<*, *>)["issue"])
                    assertEquals(0, owner.size)
                } finally { review.drain() }
            }
        }
    }

    @Test fun malformedUnicodeRefusesBeforeRetentionAndSourceWordsAreNeverNormalized(): Unit = runBlocking {
        withTimeout(10_000) {
            val cas = KgRetentionFixtureCas()
            bag(cas) { owner ->
                assertFailsWith<CharacterCodingException> { owner.ingestKg("(labels Tenant \"\uD800\")") }
                assertTrue(cas.puts.isEmpty()); assertEquals(0, owner.size)
                val rdf = " \r\n<urn:s> <urn:p> \"thirty (60); 📚\"@en-US .\r\n"
                val report = owner.ingestKg(rdf)
                val source = ContentId(report["sourceCid"] as String)
                assertContentEquals(rdf.encodeToByteArray(), cas.get(source))
                val pid = ContentId((report["propositionCids"] as List<*>).single() as String)
                val definition = CanonicalCbor.decodeMap(requireNotNull(cas.get(pid)))
                assertEquals("\"thirty (60); 📚\"@en-US", definition["object"])
            }
        }
    }

    @Test fun lfFramedHttpKeepsTheEntireBodyBeforeAnEmbeddedCrLfSeparator(): Unit = runBlocking {
        withTimeout(10_000) {
            val input = "  (pays Tenant Landlord)\r\n\r\n(labels Tenant \"record\")\r\n"
            val cas = KgRetentionFixtureCas()
            bag(cas) { owner ->
                val review = TurnReviewElement(owner); review.open()
                try {
                    val response = requireNotNull(BeliefWire(owner, review, null).route("POST", "/api/beliefs/kg",
                        "POST /api/beliefs/kg HTTP/1.1\nContent-Length: ${input.encodeToByteArray().size}\n\n$input", null))
                    assertEquals(200, response.status)
                    val report = reify(response.body) as Map<*, *>
                    val cid = ContentId(report["sourceCid"] as String)
                    assertEquals(ContentId.of(input.encodeToByteArray()), cid)
                    assertContentEquals(input.encodeToByteArray(), cas.get(cid))
                    assertEquals(2, (report["processedAssertions"] as Number).toInt())
                } finally { review.drain() }
            }
        }
    }

    @Test fun drainWaitsForAdmittedRetentionAndImmutablePayloadsKeepTheirBytes(): Unit = runBlocking {
        withTimeout(10_000) {
            val entered = CompletableDeferred<Unit>()
            val release = java.util.concurrent.CountDownLatch(1)
            val cas = object : KgRetentionFixtureCas() {
                override fun sync() {
                    entered.complete(Unit)
                    check(release.await(3, java.util.concurrent.TimeUnit.SECONDS)) { "Synthetic sync gate expired" }
                    super.sync()
                }
            }
            val input = text.encodeToByteArray()
            val source = KgRetention.Artifact(ContentId.of(input), input)
            input.fill(0)
            val definitionBytes = mapped().propositionBytes(source.cid.value)
            val first = KgRetention.Artifact(ContentId.of(definitionBytes), definitionBytes)
            var later = first
            val batch = KgRetention.Batch(source, 1 j { _: Int -> later })
            later = KgRetention.Artifact(ContentId.of("different".encodeToByteArray()), "different".encodeToByteArray())
            val owner = BeliefBagElement(cas = cas, parentJob = currentCoroutineContext()[Job]); owner.open()
            try {
                val retention = async { owner.retainKg(batch) }
                entered.await()
                val draining = async { owner.drain() }
                yield()
                assertFalse(draining.isCompleted)
                release.countDown()
                retention.await(); draining.await()
                assertContentEquals(text.encodeToByteArray(), cas.get(source.cid))
                assertContentEquals(definitionBytes, cas.get(first.cid))
                assertNull(cas.get(later.cid), "Changing the caller's oracle cannot replace admitted artifacts")
                assertTrue(owner.worker?.isCompleted == true && owner.supervisor.isCompleted)
            } finally { release.countDown(); owner.drain() }
        }
    }
}
