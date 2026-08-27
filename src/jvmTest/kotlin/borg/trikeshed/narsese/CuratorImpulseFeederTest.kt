package borg.trikeshed.narsese

import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import borg.trikeshed.memory.MemoryStore
import borg.trikeshed.memory.content
import borg.trikeshed.job.CasStore
import borg.trikeshed.couch.CouchStoreFactory
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end guard for the curator-impulse feeding pipeline: a REAL ledger
 * JSONL + REAL sqlite transcript db (temp dirs, no mocks) must flow through
 * [CuratorImpulseFeeder] → assess → bank SUMO/KIF → mint bag signals.
 * NEUTRAL transcripts (no outcome marker) must mint nothing — the feeder
 * never fabricates a verdict.
 */
class CuratorImpulseFeederTest {

    /** One bag settle discipline shared with CausalityReteElementTest. */
    private suspend fun BeliefBagElement.settle() {
        var quiet = 0
        var spins = 0
        while (spins++ < 400 && quiet < 3) {
            kotlinx.coroutines.delay(10)
            if (intake.isEmpty) quiet++ else quiet = 0
        }
    }

    private fun writeLedger(dir: java.io.File): java.io.File {
        val skills = java.io.File(dir, "skills").apply { mkdirs() }
        return java.io.File(skills, ".curator_ledger.jsonl").apply {
            writeText(
                """
                {"id":"a1","ts":"2026-08-26T00:00:00Z","actor":"curator","action":"create","skill":"ledger-skill-a","evidence":{"session_id":"20260826_000000_feed01"}}
                {"id":"a2","ts":"2026-08-26T00:01:00Z","actor":"curator","action":"patch","skill":"ledger-skill-b","evidence":{"session_id":"20260826_000000_feed02"}}
                {"id":"a3","ts":"2026-08-26T00:02:00Z","actor":"curator","action":"write_file","skill":"ledger-skill-b","evidence":{"session_id":"20260826_000000_feed02","file_path":"SKILL.md"}}
                """.trimIndent(),
            )
        }
    }

    private fun writeTranscriptDb(dir: java.io.File, sessionId: String, turns: List<Pair<String, String>>) {
        val db = java.io.File(dir, "state.db")
        DriverManager.getConnection("jdbc:sqlite:${db.absolutePath}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL, role TEXT NOT NULL, content TEXT)")
                stmt.execute("DELETE FROM messages")
                for ((role, content) in turns) {
                    val ps = conn.prepareStatement("INSERT INTO messages (session_id, role, content) VALUES (?, ?, ?)")
                    ps.setString(1, sessionId)
                    ps.setString(2, role)
                    ps.setString(3, content)
                    ps.executeUpdate()
                    ps.close()
                }
            }
        }
    }

    private fun appendTurn(dir: java.io.File, sessionId: String, role: String, content: String) {
        val db = java.io.File(dir, "state.db")
        DriverManager.getConnection("jdbc:sqlite:${db.absolutePath}").use { conn ->
            conn.prepareStatement("INSERT INTO messages (session_id, role, content) VALUES (?, ?, ?)").use { ps ->
                ps.setString(1, sessionId); ps.setString(2, role); ps.setString(3, content); ps.executeUpdate()
            }
        }
    }

    @Test
    fun supportedVerdictBanksKifAndMintsBagSignal() = runBlocking {
        val dir = Files.createTempDirectory("feeder-pos").toFile()
        writeLedger(dir)
        // one SUPPORTED transcript: explicit [pass] marker in the dialogue
        writeTranscriptDb(
            dir,
            "20260826_000000_feed01",
            listOf(
                "user" to "consolidate the dupes in ledger-skill-a",
                "assistant" to "did it. [pass] merged both variants into one surface",
            ),
        )
        val bag = BeliefBagElement(capacity = 64)
        bag.open()
        val rete = CausalityReteElement(bag, emptyList<EternalRule>().toSeries())
        rete.open()
        val curator = CuratorImpulseElement(bag, rete = rete).also { it.open() }

        val feeder = CuratorImpulseFeeder(dir)
        val impulses = feeder.loadImpulses()
        assertEquals(3, impulses.size, "all three ledger records are impulses")

        val scenarios = feeder.loadScenarios(impulses)
        assertEquals(1, scenarios.size, "only feed01 has a transcript; feed02/feed03 sessions are absent and skipped")

        val landed = feeder.backfill(curator)
        bag.settle()

        assertTrue(landed.size > 0, "a SUPPORTED verdict must mint at least one signal")
        assertEquals(landed.size, bag.snapshot().size.coerceAtLeast(landed.size), "minted signals ride the intake")
        assertTrue(curator.knowledgeBank.asserts().size > 0, "verdicts must bank as KIF assertions")

        bag.drain()
    }

    @Test
    fun neutralTranscriptMintsNothing() = runBlocking {
        val dir = Files.createTempDirectory("feeder-neutral").toFile()
        writeLedger(dir)
        // NO outcome markers anywhere — hindsight must stay NEUTRAL
        writeTranscriptDb(
            dir,
            "20260826_000000_feed01",
            listOf(
                "user" to "consolidate the dupes in ledger-skill-a",
                "assistant" to "done, changes are in place",
            ),
        )
        val bag = BeliefBagElement(capacity = 64)
        bag.open()
        val rete = CausalityReteElement(bag, emptyList<EternalRule>().toSeries())
        rete.open()
        val curator = CuratorImpulseElement(bag, rete = rete).also { it.open() }

        val landed = CuratorImpulseFeeder(dir).backfill(curator)
        assertEquals(0, landed.size, "NEUTRAL transcripts mint nothing — no fabricated evidence")
        bag.drain()
    }

    @Test
    fun missingLedgerAndMissingTranscriptsAreNotErrors() = runBlocking {
        val dir = Files.createTempDirectory("feeder-empty").toFile() // nothing written
        val bag = BeliefBagElement(capacity = 64)
        bag.open()
        val rete = CausalityReteElement(bag, emptyList<EternalRule>().toSeries())
        rete.open()
        val curator = CuratorImpulseElement(bag, rete = rete).also { it.open() }

        val feeder = CuratorImpulseFeeder(dir)
        assertEquals(0, feeder.loadImpulses().size, "absent ledger is empty, not an error")
        assertEquals(0, feeder.backfill(curator).size, "nothing to backfill lands nothing")
        bag.drain()
    }

    @Test
    fun replayTargetsProjectSubjectToEvidenceSession() {
        val impulses = listOf(
            CuratorImpulse(CuratorImpulseKind.CREATE, "s", rationale = "r", proposalCid = "sess-9"),
        ).toSeries()
        val targets = CuratorLedger.replayTargets(impulses)
        assertEquals(1, targets.size)
        assertEquals("sess-9", targets[0].b, "the evidence session id rides Join.b for retrieval")
    }

    @Test
    fun incrementalFollowTeachesOnlyChangedRowsAndIngestsTranscriptSpines() = runBlocking {
        val dir = Files.createTempDirectory("feeder-follow").toFile()
        writeLedger(dir)
        val sid = "20260826_000000_feed01"
        writeTranscriptDb(
            dir,
            sid,
            listOf(
                "user" to "create ledger-skill-a",
                "assistant" to "created and verified [pass]",
            ),
        )
        val bag = BeliefBagElement(capacity = 64).also { it.open() }
        val rete = CausalityReteElement(bag, emptyList<EternalRule>().toSeries()).also { it.open() }
        val curator = CuratorImpulseElement(bag, rete = rete).also { it.open() }
        val memory = MemoryStore(CasStore.inMemory(), CouchStoreFactory.inMemory())
        val feeder = CuratorImpulseFeeder(dir)
        try {
            val first = feeder.followOnce(curator, memory)
            assertEquals(1, first.transcriptCids.size, "first observed session lands once")
            assertTrue(first.landed.isNotEmpty(), "[pass] transcript teaches")
            val firstCid = first.transcriptCids[0].b
            val path = first.transcriptCids[0].a
            assertNotNull(memory.get(path))
            assertNotNull(memory.spineCidOf(path), "transcript is a Line-CAS terrain citizen")

            val unchanged = feeder.followOnce(curator, memory, first.checkpoint)
            assertEquals(0, unchanged.transcriptCids.size, "unchanged MAX(messages.id) is a no-op")
            assertEquals(0, unchanged.landed.size)

            appendTurn(dir, sid, "assistant", "later verification regressed [fail]")
            val changed = feeder.followOnce(curator, memory, unchanged.checkpoint)
            assertEquals(1, changed.transcriptCids.size, "new row advances the checkpoint")
            assertTrue(changed.transcriptCids[0].b != firstCid, "appended row changes transcript cid")
            val changedPath = changed.transcriptCids[0].a
            assertTrue(changedPath != path, "snapshot path includes the row checkpoint")
            assertTrue(memory.get(changedPath)!!.content.decodeToString().contains("[fail]"), "full changed transcript re-landed")
            assertTrue(changed.checkpoint.last("$sid\u0000ledger-skill-a") > first.checkpoint.last("$sid\u0000ledger-skill-a"))
        } finally {
            bag.drain()
        }
    }
}
