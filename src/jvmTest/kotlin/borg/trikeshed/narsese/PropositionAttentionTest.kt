package borg.trikeshed.narsese

import borg.trikeshed.couch.isam.DurableAppendLog
import borg.trikeshed.cursor.BudgetCoord
import borg.trikeshed.forge.server.BeliefWire
import borg.trikeshed.job.*
import borg.trikeshed.lcnc.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.*
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** Explicit manual KG fixtures exercise attention ownership; their claims are not authenticated truth. */
class PropositionAttentionTest {
    @org.junit.jupiter.api.io.TempDir lateinit var directory: Path
    val text = "  (pays Tenant Landlord)\r\n"

    class Store : CasStore(), BoundedCasReader {
        val blobs = mutableMapOf<ContentId, ByteArray>()
        val reads = mutableListOf<Join<ContentId, Int>>()
        val puts = mutableListOf<ContentId>()
        val unavailable = mutableSetOf<ContentId>()
        var servedBytes = 0L
        override fun put(bytes: ByteArray): ContentId = ContentId.of(bytes).also { blobs[it] = bytes.copyOf(); puts.add(it) }
        override fun get(cid: ContentId): ByteArray? = if (cid in unavailable) null else blobs[cid]?.copyOf()
        override fun getBounded(cid: ContentId, maxBytes: Int): ByteArray? {
            reads.add(cid j maxBytes)
            if (cid in unavailable) return null
            return blobs[cid]?.also { require(it.size <= maxBytes); servedBytes += it.size }?.copyOf()
        }
    }

    suspend fun <T> owner(cas: Store, wal: DurableAppendLog? = null, floor: Float = 0f,
        decay: (BudgetCoord) -> BudgetCoord = { it }, body: suspend (BeliefBagElement) -> T): T {
        val bag = BeliefBagElement(cas = cas, wal = wal, flushEvery = 1000, priorityFloor = floor,
            decayFn = decay, parentJob = currentCoroutineContext()[Job])
        bag.open()
        try { return body(bag) } finally {
            bag.drain()
            assertTrue(bag.worker?.isCompleted == true && bag.supervisor.isCompleted)
        }
    }

    suspend fun imported(bag: BeliefBagElement): SemanticSignal {
        val source = ContentId.of(text.encodeToByteArray())
        bag.ingestKg(text)
        return assertNotNull(bag.signalOf(KgNalBridge.bridge(text).single().signal(source.value).angular))
    }

    @Test fun exactAttentionResolvesPartialBudgetInFifoOrderAndPreservesAllSignalAndSourceBytes(): Unit = runBlocking {
        withTimeout(10_000) {
            val cas = Store()
            owner(cas) { bag ->
                val signal = imported(bag)
                val pid = ContentId(assertNotNull(signal.propositionCid))
                val source = ContentId.of(text.encodeToByteArray())
                val bytes = SignalCodec.encode(signal)
                val definition = assertNotNull(cas.get(pid))
                val puts = cas.puts.size
                val queued = BudgetCoord(.2f, .9f, .3f)
                bag.intake.send(BeliefIntake.Attend(signal.angular, queued))
                val result = bag.attendProposition(signal.angular, pid, p = .8f)
                assertEquals(BudgetCoord(.8f, queued.df, queued.qf), result.budget)
                assertEquals(result.budget, bag.budgetOf(signal.angular), "The acknowledgement follows processing")
                assertEquals(true, result.report()["processed"])
                assertEquals("processing-only-no-flush-requested", result.report()["durability"])
                assertEquals(source.value, result.identity["sourceContext"])
                assertEquals("verified-retained-kg", result.identity["sourceContextStatus"])
                assertContentEquals(bytes, SignalCodec.encode(assertNotNull(bag.signalOf(signal.angular))))
                assertContentEquals(definition, cas.get(pid))
                assertContentEquals(text.encodeToByteArray(), cas.get(source))
                assertEquals(puts, cas.puts.size, "Attention cannot mint receipts, definitions or source bytes")
                assertFalse(bag.attendProposition(signal.angular, pid).changed)
                val clamped = bag.attendProposition(signal.angular, pid, p = 2f, q = -1f)
                assertEquals(BudgetCoord(1f, queued.df, 0f), clamped.budget)
                bag.ingestKg(text)
                assertContentEquals(bytes, SignalCodec.encode(assertNotNull(bag.signalOf(signal.angular))),
                    "Attention cannot reset duplicate-observation accounting")
            }
        }
    }

    @Test fun mismatchedUnknownLegacyAndEvictedIdentitiesRefuseWithoutRevivalAndTheWriterRecovers(): Unit = runBlocking {
        withTimeout(10_000) {
            val cas = Store()
            owner(cas, floor = .5f) { bag ->
                val signal = imported(bag); val pid = ContentId(assertNotNull(signal.propositionCid))
                val before = SignalCodec.encode(signal); val budget = bag.budgetOf(signal.angular)
                val wrong = ContentId.of("another proposition".encodeToByteArray())
                val mismatch = assertFailsWith<BeliefAdmissionRefused> { bag.attendProposition(signal.angular, wrong, p = 1f) }
                assertEquals(BeliefAdmissionIssue.PROPOSITION_MISMATCH, mismatch.issue)
                val missing = assertFailsWith<BeliefAdmissionRefused> { bag.attendProposition(999L, pid, p = 1f) }
                assertEquals(BeliefAdmissionIssue.RESIDENT_REQUIRED, missing.issue)
                val invalid = assertFailsWith<BeliefAdmissionRefused> { bag.attendProposition(signal.angular, pid, p = Float.NaN) }
                assertEquals(BeliefAdmissionIssue.INVALID_BUDGET, invalid.issue)
                assertEquals(budget, bag.budgetOf(signal.angular)); assertContentEquals(before, SignalCodec.encode(assertNotNull(bag.signalOf(signal.angular))))
                val legacy = signal.copy(angular = signal.angular xor 1L, propositionCid = null)
                bag.submit(BeliefIntake.Mint(legacy, BudgetCoord(.8f, .5f, .5f)))
                val unresolved = assertFailsWith<BeliefAdmissionRefused> { bag.attendProposition(legacy.angular, pid) }
                assertEquals(BeliefAdmissionIssue.UNRESOLVED_PROPOSITION, unresolved.issue)
                // Hermes still uses the original angular+budget command, including absent no-ops.
                bag.intake.send(BeliefIntake.Attend(legacy.angular, BudgetCoord.zero()))
                bag.intake.send(BeliefIntake.Attend(999L, BudgetCoord.full()))
                bag.awaitProcessed()
                assertEquals(BudgetCoord.zero(), bag.budgetOf(legacy.angular)); assertNull(bag.signalOf(999L))
                bag.attendProposition(signal.angular, pid, p = .1f)
                bag.intake.send(BeliefIntake.DecayTick); bag.awaitProcessed()
                assertNull(bag.signalOf(signal.angular))
                val evicted = assertFailsWith<BeliefAdmissionRefused> { bag.attendProposition(signal.angular, pid, p = 1f) }
                assertEquals(BeliefAdmissionIssue.RESIDENT_REQUIRED, evicted.issue)
                assertNull(bag.signalOf(signal.angular)); assertNotNull(cas.get(ContentId.of(before)))
                bag.submit(BeliefIntake.Mint(legacy.copy(angular = 101L), BudgetCoord.full()))
                assertNotNull(bag.signalOf(101L)); assertTrue(bag.worker?.isActive == true)
            }
        }
    }

    @Test fun productionWalReplaysProcessedAttentionAndDecayWithoutChangingEvidence(): Unit = runBlocking {
        withTimeout(10_000) {
            val cas = Store(); val path = directory.resolve("attention.wal")
            val decay: (BudgetCoord) -> BudgetCoord = { BudgetCoord(it.pf * .5f, it.df, it.qf) }
            lateinit var signal: SemanticSignal
            var expected = BudgetCoord.zero()
            val first = DocumentAppendLog.open(path.toString(), directory.toString())
            try { owner(cas, first, decay = decay) { bag ->
                signal = imported(bag)
                val pid = ContentId(assertNotNull(signal.propositionCid))
                bag.attendProposition(signal.angular, pid, p = .9f, d = .2f)
                val after = Files.size(path)
                assertFalse(bag.attendProposition(signal.angular, pid).changed)
                assertEquals(after, Files.size(path), "An unchanged budget appends no attention frame")
                bag.intake.send(BeliefIntake.DecayTick); bag.awaitProcessed()
                expected = assertNotNull(bag.budgetOf(signal.angular))
                assertEquals(BudgetCoord(.9f, .2f, .6f).q, expected.q)
                assertContentEquals(SignalCodec.encode(signal), SignalCodec.encode(assertNotNull(bag.signalOf(signal.angular))))
            } } finally { first.close() }
            val reopened = DocumentAppendLog.open(path.toString(), directory.toString())
            try { owner(cas, reopened, decay = decay) { bag ->
                assertEquals(expected, bag.budgetOf(signal.angular))
                assertContentEquals(SignalCodec.encode(signal), SignalCodec.encode(assertNotNull(bag.signalOf(signal.angular))))
                val pid = ContentId(assertNotNull(signal.propositionCid))
                assertEquals("verified-retained-kg", bag.attendProposition(signal.angular, pid, p = .7f).identity["sourceContextStatus"])
            } } finally { reopened.close() }
        }
    }

    @Test fun lcncExactAndLegacyAttentionAcknowledgeProcessingAndBothResonanceModesExposeIdentity(): Unit = runBlocking {
        withTimeout(10_000) {
            val cas = Store()
            owner(cas) { bag ->
                val signal = imported(bag); val pid = assertNotNull(signal.propositionCid)
                val runner = BeliefsNodes.attendRunner(bag)
                val node = LcncNode("attention", "nal.attend", _m["p" j ".95"])
                val result = runner.run(node, _m["angular?" j signal.angular.toString(), "propositionCid?" j pid])["attended"] as Map<*, *>
                assertEquals(true, result["processed"]); assertEquals("exact-resident-proposition", result["identityScope"])
                assertEquals(BudgetCoord(.95f, .4f, .6f), bag.budgetOf(signal.angular))
                assertEquals(pid, result["propositionCid"])
                val legacy = runner.run(node.copy(params = _m["angular" j signal.angular.toString(), "d" j ".8"]), emptyMap())["attended"] as Map<*, *>
                assertEquals(true, legacy["processed"]); assertEquals("legacy-angular", legacy["identityScope"])
                assertEquals(BudgetCoord(.95f, .8f, .6f), bag.budgetOf(signal.angular))
                for (invalid in s_["NaN", "Infinity", "garbage"].view) assertFailsWith<IllegalArgumentException> {
                    runner.run(node.copy(params = _m["p" j invalid]), _m["angular" j signal.angular.toString(), "propositionCid" j pid])
                }
                for (mode in s_["hamming", "whitened"].view) {
                    val output = BeliefsNodes.resonateRunner(bag).run(LcncNode("resonance", "beliefs.resonate", _m["goal" j "Tenant", "mode" j mode]), emptyMap())
                    val rows = (output["synonyms"] as List<*>) + (output["antonyms"] as List<*>)
                    assertTrue(rows.isNotEmpty())
                    for (raw in rows) {
                        val row = raw as Map<*, *>
                        assertEquals(pid, row["propositionCid"])
                        assertEquals(ContentId.of(text.encodeToByteArray()).value, row["sourceContext"])
                        assertEquals("verified-retained-kg", row["sourceContextStatus"])
                    }
                }
                val contract = assertNotNull(LcncContracts.find("nal.attend"))
                assertTrue("propositionCid?" in contract.inputs)
            }
        }
    }

    @Test fun httpAttentionRejectsInvalidOrWrongIdentityAndKeepsActualAngularCollision409(): Unit = runBlocking {
        withTimeout(10_000) {
            val cas = Store()
            owner(cas) { bag ->
                val review = TurnReviewElement(bag); review.open()
                try {
                    val wire = BeliefWire(bag, review, null)
                    val signal = imported(bag); val pid = assertNotNull(signal.propositionCid)
                    suspend fun request(body: String) = assertNotNull(wire.route("POST", "/api/beliefs/attend", body, null))
                    val body = _m["angular" j signal.angular.toString(), "propositionCid" j pid, "p" j .9]
                    val response = request(jsonOf(body)); assertEquals(200, response.status)
                    assertEquals(true, ((reifyMap(response.body)["attended"] as Map<*, *>)["processed"]))
                    assertEquals(400, request(jsonOf(body - "propositionCid")).status)
                    assertEquals(409, request(jsonOf(body + _m["propositionCid" j ContentId.of("wrong".encodeToByteArray()).value])).status)
                    assertEquals(400, request("{\"angular\":\"1\",\"angular\":\"2\",\"propositionCid\":\"$pid\"}").status)
                    assertEquals(400, request(" ".repeat(4097)).status)
                    for (mode in s_["hamming", "whitened"].view) {
                        val resonance = assertNotNull(wire.route("POST", "/api/beliefs/resonate", jsonOf(_m["goal" j "Tenant", "mode" j mode]), null))
                        val result = reifyMap(resonance.body)
                        val rows = (result["synonymPeaks"] as List<*>) + (result["antonymPeaks"] as List<*>)
                        assertTrue(rows.isNotEmpty()); assertTrue(rows.all { (it as Map<*, *>)["propositionCid"] == pid })
                    }
                    val collision = assertNotNull(wire.route("POST", "/api/beliefs/kg", "(pays Clerk Owner) (owes Clerk Owner)", null))
                    assertEquals(409, collision.status)
                    assertEquals("PROPOSITION_MISMATCH", (reifyMap(collision.body)["admission"] as Map<*, *>)["issue"])
                    assertEquals(200, request(jsonOf(body)).status)
                } finally { review.drain() }
            }
        }
    }

    @Test fun sourceMetadataIsBoundedAndDoesNotGuessWhenSourceOrRolesDiffer(): Unit = runBlocking {
        withTimeout(10_000) {
            val cas = Store()
            owner(cas) { bag ->
                val signal = imported(bag); val pid = ContentId(assertNotNull(signal.propositionCid))
                val source = ContentId.of(text.encodeToByteArray())
                val inspect = bag.sourceContexts(); cas.reads.clear()
                repeat(64) { assertEquals(source.value, inspect(signal)["sourceContext"]) }
                assertEquals(1, cas.reads.count { it.a == source }); assertEquals(1, cas.reads.count { it.a == pid })
                assertEquals("inspection-limit", inspect(signal)["sourceContextStatus"])
                assertTrue(cas.reads.sumOf { cas.blobs[it.a]?.size ?: 0 } <= KgRetention.MAX_TOTAL_BYTES)
                assertNull(bag.sourceContexts()(signal.copy(subjectCid = ContentId.of("wrong role".encodeToByteArray()).value))["sourceContext"])
                cas.unavailable.add(source)
                val result = bag.attendProposition(signal.angular, pid, p = .9f)
                assertEquals("unavailable-or-unverified", result.identity["sourceContextStatus"])
                assertNull(result.identity["sourceContext"]); assertEquals(pid.value, result.identity["propositionCid"])
                assertContentEquals(SignalCodec.encode(signal), SignalCodec.encode(assertNotNull(bag.signalOf(signal.angular))))
                val bounded = bag.sourceContexts()
                val served = cas.servedBytes
                val statuses = mutableListOf<Any?>()
                repeat(64) { index ->
                    val large = " ".repeat(65_500) + "(labels Clerk$index Record)"
                    val sourceCid = cas.put(large.encodeToByteArray())
                    val mapped = KgNalBridge.bridge(large).single()
                    cas.put(mapped.propositionBytes(sourceCid.value))
                    statuses.add(bounded(mapped.signal(sourceCid.value))["sourceContextStatus"])
                }
                assertTrue("inspection-limit" in statuses)
                assertTrue(cas.servedBytes - served <= KgRetention.MAX_TOTAL_BYTES, "One response has a total source-byte budget")
            }
        }
    }
}
