package borg.trikeshed.lcnc

import borg.trikeshed.couch.isam.DurableAppendLog
import borg.trikeshed.job.*
import borg.trikeshed.lib.*
import borg.trikeshed.narsese.*
import borg.trikeshed.ontology.CorpusOntology
import borg.trikeshed.parse.jsonOf
import kotlinx.coroutines.*
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import java.lang.System
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Optional real native integration; configured explicitly by an operator in development/CI. */
@org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "trikeshed.native.mlnix", matches = ".+")
class NativeHornQueryTest {
    private class Store : CasStore(), BoundedCasReader {
        private val blobs = mutableMapOf<ContentId, ByteArray>()
        var afterRead: ((ContentId) -> Unit)? = null
        override fun put(bytes: ByteArray): ContentId = ContentId.of(bytes).also { blobs[it] = bytes.copyOf() }
        override fun get(cid: ContentId): ByteArray? = blobs[cid]?.copyOf()
        override fun getBounded(cid: ContentId, maxBytes: Int): ByteArray? = get(cid)?.also {
            require(it.size <= maxBytes)
            afterRead?.invoke(cid)
        }
    }
    private class Log : DurableAppendLog {
        val frames = mutableListOf<Pair<Long, ByteArray>>()
        override fun append(sequence: Long, payload: ByteArray): Long { frames.add(sequence to payload.copyOf()); return sequence }
        override fun flush() = Unit
        override suspend fun replay(onFrame: suspend (Long, ByteArray) -> Unit): Long {
            for ((sequence, bytes) in frames) onFrame(sequence, bytes.copyOf())
            return frames.lastOrNull()?.first ?: 0
        }
        override fun injectCorruptionAfter(sequence: Long) = error("Not used")
    }
    private class Fixture {
        val cas = Store()
        val log = Log()
        val text = "A subclass B."
        val textCid = cas.put(text.encodeToByteArray())
        val source = DocumentSource(textCid, textCid, text, "taxonomy", "text/plain", "synthetic-fixture")
        val ontology = CorpusOntology.retain(cas, "fixture", "v1", "(subclass A B)", emptyMap())
        val receipt = BookCurationReceipt.retain(cas, "taxonomy", source, ontology,
            s_[BookClause(NormClause("A", "generic", true, "subclass", "B", null, text, null), 0, text.length, 0)], "manual-fixture")
        val selection = ((BookCurationReceipt.read(cas, receipt)["relationships"] as List<*>).single() as Map<*, *>)["id"] as String
        var current = true
        var currentReads = 0
        var failAtRead = Int.MAX_VALUE
        val review = CurationReview(cas, log, resolve = { kind, cid, ids ->
            require(kind == "book")
            BookCurationReceipt.candidateVerified(cas, cid, ids)
        }, current = { candidate -> (++currentReads < failAtRead) && current && BookCurationReceipt.verified(cas, receipt).matches(candidate) }, clock = { 123L })
        val actor = ReviewPrincipal("synthetic:reviewer", synthetic = true)
        fun cid(value: Map<String, Any?>, key: String) = ContentId(value.getValue(key) as String)
        fun revision(value: Map<String, Any?>) = (value.getValue("revision") as Number).toLong()
        suspend fun approve(): Map<String, Any?> {
            val snapshot = review.snapshot("book", receipt, listOf(selection))
            val id = cid(snapshot, "snapshotCid")
            return withContext(actor) {
                val draft = review.assess(id, revision(snapshot), listOf(ConditionAssessment(id, selection, ConditionAssessmentState.UNCONDITIONAL)))
                review.decide(id, revision(draft), "approve", expectedAssessmentCid = cid(draft, "assessmentCid"))
            }
        }
    }


    internal fun profile(): NativeHornProfile = NativeHornProfile.parse(jsonOf(_m[
        "binary" j System.getProperty("trikeshed.native.mlnix"),
        "binarySha256" j System.getProperty("trikeshed.native.mlnix.sha256"),
        "adapter" j System.getProperty("trikeshed.native.adapter"),
        "datalogDirectory" j System.getProperty("trikeshed.native.datalog")])).also {
            assertTrue(it.environment.isEmpty(), "Parsed operator profile must work without constructor-only environment")
        }

    @Test fun observationWallLimitIncludesReviewedSourcePreparation(): Unit = runBlocking {
        val f = Fixture(); f.review.open(this)
        try {
            val approved = f.approve()
            val reads = AtomicInteger()
            f.cas.afterRead = { if (it == f.textCid) { reads.incrementAndGet(); Thread.sleep(600) } }
            val limited = NativeHornProfile(Path.of("/unavailable/mlnix"), "0".repeat(64),
                Path.of("/unavailable/adapter"), Path.of("/unavailable/datalog"), timeoutMillis = 250)
            val failure = assertFailsWith<IllegalStateException> {
                withContext(Dispatchers.Default + f.actor) {
                    NativeHornQuery(limited).report(f.review, f.cas, f.cid(approved, "snapshotCid"),
                        f.revision(approved), "needsMapping")
                }
            }
            assertContains(failure.message.orEmpty(), "overall wall limit")
            assertEquals(1, reads.get())
        } finally { f.review.drain() }
    }

    @Test fun observationWallLimitIncludesPostQueryRecheck(): Unit = runBlocking(JvmFileOperations()) {
        val f = Fixture(); f.review.open(this)
        try {
            val approved = f.approve()
            val good = profile()
            val limited = NativeHornProfile(good.binary, good.binarySha256, good.adapter,
                good.datalogDirectory, good.environment, timeoutMillis = 5_000)
            val reads = AtomicInteger()
            f.cas.afterRead = {
                if (it == f.textCid && reads.incrementAndGet() == 2) Thread.sleep(5_500)
            }
            val before = f.log.frames.size
            val failure = assertFailsWith<IllegalStateException> {
                withContext(Dispatchers.Default + f.actor) {
                    NativeHornQuery(limited).report(f.review, f.cas, f.cid(approved, "snapshotCid"),
                        f.revision(approved), "needsMapping")
                }
            }
            assertContains(failure.message.orEmpty(), "overall wall limit")
            // The second source read happens only after the real pinned solver returns.
            assertEquals(2, reads.get(), "The query must reach its post-solver source recheck")
            assertEquals(before, f.log.frames.size, "A timed-out recheck cannot append or spend authority")
        } finally { f.review.drain() }
    }

    @Test fun realNativeReviewQueryAndNoProofKeepWorldUnknown(): Unit = runBlocking(JvmFileOperations()) {
        val f = Fixture(); f.review.open(this)
        try {
            val approved = f.approve()
            val before = f.log.frames.size
            withContext(f.actor) {
                val query = NativeHornQuery(profile())
                val supported = query.report(f.review, f.cas, f.cid(approved, "snapshotCid"), f.revision(approved), "needsMapping")
                assertEquals("SUPPORTED", supported["status"])
                assertEquals("UNKNOWN", supported["worldObligationApplicability"])
                assertNotNull(supported["proof"])
                val absent = query.report(f.review, f.cas, f.cid(approved, "snapshotCid"), f.revision(approved), "containsMandatorySelection")
                assertEquals("UNKNOWN", absent["status"])
                assertNull(absent["proof"])
                assertEquals(before, f.log.frames.size)
                // The next first report checks twice; the post-execution report's third read sees withdrawal.
                f.failAtRead = f.currentReads + 3
                val stale = assertFailsWith<IllegalArgumentException> {
                    query.report(f.review, f.cas, f.cid(approved, "snapshotCid"), f.revision(approved), "needsMapping")
                }
                assertTrue(stale.message.orEmpty().contains("no longer current"))
                assertEquals(f.failAtRead, f.currentReads)
                assertEquals(before, f.log.frames.size, "Stale execution cannot append or spend authority")
            }
        } finally { f.review.drain() }
    }
    @Test fun incorrectExecutablePinRefusesExecution(): Unit = runBlocking(JvmFileOperations()) {
        val good = profile()
        val bad = NativeHornProfile(good.binary, "0".repeat(64), good.adapter, good.datalogDirectory, good.environment)
        assertFailsWith<IllegalArgumentException> { bad.check(checkNotNull(coroutineContext[borg.trikeshed.userspace.nio.file.spi.FileOperations])) }
    }

    @Test fun realNativeManualRoleJoinKeepsSourcesBindingsAndNegativeControlsSeparate(): Unit = runBlocking(JvmFileOperations()) {
        val query = NativeHornQuery(profile())
        suspend fun run(text: String, goal: String = "(mayRead Lark Harbor)") =
            query.manualTheory(1, manualRoleSource(text), goal)
        // Supplied S5 and S6 are separate immutable theories. Their facts are not auto-unioned.
        val reversed = run(manualRoleS5)
        val missing = run(manualRoleS6)
        for (result in s_[reversed, missing].view) {
            assertEquals("UNKNOWN", result["status"])
            assertNull(result["proof"])
            assertNull(result["checkedProof"])
            assertEquals("not-established", result["reviewAuthority"])
        }
        // NEW authored control, new source/input/frame, not a new label for original S6.
        val positive = run(manualRolePositive)
        assertEquals("SUPPORTED", positive["status"])
        assertEquals("declared-host-manual-assumptions-only", positive["authority"])
        assertEquals("UNKNOWN", positive["worldObligationApplicability"])
        assertNotEquals(missing["inputCid"], positive["inputCid"])
        assertNotEquals(missing["frameId"], positive["frameId"])
        val checked = positive["checkedProof"] as Map<*, *>
        assertEquals(positive["inputCid"], checked["inputCid"])
        assertEquals(positive["frameId"], checked["frameId"])
        val steps = (checked["steps"] as List<*>).map { it as Map<*, *> }
        val root = steps.single { (it["path"] as List<*>).isEmpty() }
        assertEquals("mayRead#1", root["rule"])
        assertEquals(_l["(assignedTo Lark Harbor)", "(authorized Lark Access)"],
            _l[_l[0], _l[1]].map { path -> steps.single { it["path"] == path }["normalForm"] })
        val bindings = (root["bindings"] as List<*>).map { it as Map<*, *> }.associateBy { it["variable"] }
        assertEquals(_m["kind" j "constant", "token" j "Lark"], bindings.getValue("?operator")["term"])
        assertEquals(_m["kind" j "constant", "token" j "Harbor"], bindings.getValue("?resource")["term"])
        val catalog = checked["sources"] as Map<*, *>
        val ruleSource = catalog[(root["ruleSourceNodeIds"] as List<*>).single()] as Map<*, *>
        assertEquals(manualRoleRule, ruleSource["text"])
        assertEquals(manualRoleSource(manualRolePositive).sourceCid.value, ruleSource["sourceCid"])
        val forms = (positive["sourceForms"] as List<*>).map { it as Map<*, *> }
        assertTrue(catalog.values.all { source -> forms.any { it["id"] == (source as Map<*, *>)["id"] } })

        val repeated = run(manualRolePositive + "(authorized Lark Access)\n")
        assertEquals("SUPPORTED", repeated["status"])
        val repeatSteps = ((repeated["checkedProof"] as Map<*, *>)["steps"] as List<*>).map { it as Map<*, *> }
        val repeatLeaf = repeatSteps.single { it["normalForm"] == "(authorized Lark Access)" }
        assertEquals("any-one-asserted-source", repeatLeaf["witnessMode"])
        assertEquals(2, (repeatLeaf["sourceNodeIds"] as List<*>).distinct().size)
        assertEquals(2, (repeatSteps.single { (it["path"] as List<*>).isEmpty() }["premises"] as List<*>).size)
        assertEquals("SUPPORTED", run(manualRolePositive)["status"], "Removing one repeat retains actual support")
        assertEquals("UNKNOWN", run(manualRoleS6)["status"], "Removing last authorization changes the theory, not proof strength")
        assertEquals("UNKNOWN", run(manualRolePositive.replace("(authorized Lark Access)", "(authorized Harbor Access)"))["status"])
        assertEquals("UNKNOWN", run(manualRolePositive, "(mayRead Harbor Lark)")["status"])
    }
}
