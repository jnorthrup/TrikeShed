package borg.trikeshed.narsese

import borg.trikeshed.narsese.legacy.CuratorImpulseFeeder
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import java.nio.file.Files
import java.sql.DriverManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Executable proof of the complete live path:
 *
 * real Hermes curator ledger + SQLite transcript
 *   -> explicit hindsight verdict
 *   -> receipted NARS belief
 *   -> term-preserving live Rete projection
 *   -> discounted, receipted causal consequent.
 *
 * This deliberately starts the Rete empty and admits the rule after open, as
 * OroborosDaemon does. The neutral control proves that a transcript without an
 * explicit outcome marker cannot enter the Rete or manufacture causality.
 */
class CuratorBeliefPromotionReteProofTest {

    private suspend fun BeliefBagElement.awaitSignal(angular: Long): SemanticSignal =
        withTimeout(4_000L) {
            while (true) {
                signalOf(angular)?.let { return@withTimeout it }
                delay(5L)
            }
            error("unreachable")
        }

    private fun writeHermesEvidence(
        profile: java.io.File,
        sessionId: String,
        subject: String,
        outcome: String,
    ) {
        java.io.File(profile, "skills").apply { mkdirs() }
        java.io.File(profile, "skills/.curator_ledger.jsonl").writeText(
            """{"id":"proof-1","ts":"2026-09-05T00:00:00Z","actor":"curator","action":"create","skill":"$subject","evidence":{"session_id":"$sessionId"}}""",
        )
        val db = java.io.File(profile, "state.db")
        DriverManager.getConnection("jdbc:sqlite:${db.absolutePath}").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL, role TEXT NOT NULL, content TEXT)",
                )
            }
            connection.prepareStatement(
                "INSERT INTO messages (session_id, role, content) VALUES (?, ?, ?)",
            ).use { statement ->
                statement.setString(1, sessionId)
                statement.setString(2, "assistant")
                statement.setString(3, outcome)
                statement.executeUpdate()
            }
        }
    }

    @Test
    fun explicitCuratorEvidencePromotesThroughNarsIntoReteCausality() = runBlocking {
        val profile = Files.createTempDirectory("curator-rete-proof").toFile()
        val sessionId = "20260905_000000_causal_proof"
        val subject = "causal-proof"
        val antecedent = CuratorImpulse(CuratorImpulseKind.CREATE, subject, "").term()
        val consequent = "curation_is_rete_actionable"
        writeHermesEvidence(profile, sessionId, subject, "verified result [pass]")

        val bag = BeliefBagElement(capacity = 64)
        val rete = CausalityReteElement(bag, emptyList<EternalRule>().toSeries(), discount = 0.5f)
        val curator = CuratorImpulseElement(bag, rete = rete)
        try {
            bag.open()
            rete.open()
            curator.open()

            val ruleProvenance = ContentId.of("curator-promotion-proof-rule".encodeToByteArray())
            val rule = EternalRule(
                antecedent = antecedent,
                consequent = consequent,
                copula = NalCopula.IMPLICATION,
                evidence = EvidenceCoord(10 * Nal.UNIT, 0L),
                provenanceCid = ruleProvenance.value,
            )
            assertEquals(1, rete.admit(listOf(rule).toSeries()), "the daemon-shaped empty Rete must admit the eternal rule")

            val landed = CuratorImpulseFeeder(profile).backfill(curator)
            assertEquals(1, landed.size, "one explicit [pass] observation must mint one curator belief")
            val expectedAngular = AngularCodec.encode(
                relation = RelationKind.MATCH,
                taxonomyKey = "curator/create",
                subjectTerm = antecedent,
                objectTerm = "scenario_$sessionId",
            )
            assertEquals(expectedAngular, landed[0].a, "curation must preserve the deterministic angular identity")

            val curated = bag.awaitSignal(expectedAngular)
            assertEquals(RelationKind.MATCH, curated.relation)
            assertEquals(EvidenceCoord(Nal.UNIT, 0L), curated.evidence, "the promoted belief is one direct observation")
            val curatedReceipt = ContentId(assertNotNull(curated.provenanceCid))
            assertNotEquals(ruleProvenance, curatedReceipt, "observation and rule provenance must remain distinct")
            assertTrue(
                curator.queryBank("(=> (verdict $antecedent SUPPORTED) ?outcome)").isNotEmpty(),
                "the same verdict must be banked as queryable KIF, not only emitted as a signal",
            )

            val projection = rete.projectLive()
            val assertion = (0 until projection.size)
                .map { projection[it] }
                .single { it.angular == expectedAngular }
            assertEquals(antecedent, assertion.subject, "Rete must receive the curator's named term, not guess from a hash")
            assertEquals("scenario_$sessionId", assertion.obj)
            assertEquals(curated.evidence, assertion.evidence)

            val fired = rete.fireLive()
            assertEquals(1, fired.size, "the promoted curator belief must activate its matching alpha node")
            assertEquals("$antecedent ==> $consequent", fired[0].b)

            val causal = bag.awaitSignal(fired[0].a)
            assertEquals(RelationKind.CAUSALITY, causal.relation)
            assertEquals(EvidenceCoord(5 * Nal.UNIT, 0L), causal.evidence, "derived causality must carry the Rete discount")
            assertEquals(ContentId.of(antecedent.encodeToByteArray()).value, causal.subjectCid)
            assertEquals(ContentId.of(consequent.encodeToByteArray()).value, causal.objectCid)
            ContentId(assertNotNull(causal.provenanceCid))
            assertEquals(consequent, assertNotNull(rete.termsOf(causal.angular)).a, "the consequence must be registered for chaining")
            assertEquals("$antecedent ==> $consequent", rete.glossOf(causal.angular))
            assertEquals(0, rete.fireLive().size, "Rete refraction must prevent duplicate causal evidence")
        } finally {
            curator.drain()
            rete.drain()
            bag.drain()
            profile.deleteRecursively()
        }
    }

    @Test
    fun transcriptWithoutVerdictCannotPromoteOrCause() = runBlocking {
        val profile = Files.createTempDirectory("curator-rete-neutral-proof").toFile()
        val sessionId = "20260905_000000_neutral_control"
        val subject = "neutral-control"
        val antecedent = CuratorImpulse(CuratorImpulseKind.CREATE, subject, "").term()
        writeHermesEvidence(profile, sessionId, subject, "work completed without an outcome marker")

        val bag = BeliefBagElement(capacity = 64)
        val rete = CausalityReteElement(
            bag,
            listOf(EternalRule(antecedent, "must_not_exist", NalCopula.IMPLICATION, EvidenceCoord(Nal.UNIT, 0L))).toSeries(),
        )
        val curator = CuratorImpulseElement(bag, rete = rete)
        try {
            bag.open()
            rete.open()
            curator.open()

            assertEquals(0, CuratorImpulseFeeder(profile).backfill(curator).size)
            assertEquals(0, rete.projectLive().size, "neutral curation must not become a Rete assertion")
            assertEquals(0, rete.fireLive().size, "without promoted evidence there can be no causal firing")
            assertEquals(0, bag.size)
        } finally {
            curator.drain()
            rete.drain()
            bag.drain()
            profile.deleteRecursively()
        }
    }
}
