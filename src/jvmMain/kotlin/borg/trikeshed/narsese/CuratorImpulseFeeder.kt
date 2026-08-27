package borg.trikeshed.narsese

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.emptySeriesOf
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.memory.MemoryStore
import borg.trikeshed.memory.memoryFile
import borg.trikeshed.parse.json.JsonSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.sql.DriverManager

/**
 * CuratorImpulseFeeder — jvmMain backfill adapter [CuratorImpulseElement]
 * from the real hermes ground truth:
 *
 *  - impulses: `<profile>/skills/.curator_ledger.jsonl` (one JSON record per
 *    curation action, evidence session_id naming the transcript)
 *  - transcripts: `<profile>/state.db` `messages` table (role, content) in
 *    dialogue order — the hindsight replay source
 *
 * Pure projections ([CuratorLedger], [ScenarioTranscripts]) do the shaping;
 * this class only owns retrieval. All blocking work (file read, sqlite) is
 * dispatched to IO — never blocks the reactor.
 *
 * The verdict stays mechanical: transcript markers only. A session with no
 * `[pass]`/`[fail]`-family marker is NEUTRAL and mints nothing.
 */
class CuratorImpulseFeeder(
    private val profileDir: File,
    private val maxTurnsPerScenario: Int = 400,
) {

    /** Frozen follow cursor: `(sessionId\u0000subject) → last message row id`, as Series algebra. */
    data class FollowCheckpoint(val sessions: Series<Join<String, Long>>) {
        fun last(key: String): Long {
            for (i in 0 until sessions.size) if (sessions[i].a == key) return sessions[i].b
            return 0L
        }
        companion object { fun empty() = FollowCheckpoint(emptySeriesOf()) }
    }

    data class FollowResult(
        val checkpoint: FollowCheckpoint,
        val landed: List<Join<Long, String>>,
        val transcriptCids: Series<Join<String, borg.trikeshed.job.ContentId>>,
    )

    /** Parse the curator ledger into impulses. Empty when the ledger is absent. */
    suspend fun loadImpulses(): Series<CuratorImpulse> = withContext(Dispatchers.IO) {
        val ledger = File(profileDir, "skills/.curator_ledger.jsonl")
        if (!ledger.isFile) return@withContext emptySeriesOf()
        val records = ArrayList<Map<String, Any?>>()
        for (line in ledger.readLines()) {
            if (line.isBlank()) continue
            val parsed = runCatching { JsonSupport.parse(line) }.getOrNull() as? Map<*, *> ?: continue
            @Suppress("UNCHECKED_CAST")
            records.add(parsed as Map<String, Any?>)
        }
        CuratorLedger.impulses(records.size j { i: Int -> records[i] })
    }

    /**
     * Replay every distinct evidence session the impulses name. Sessions the
     * state.db does not carry are skipped (a vanished transcript is not a
     * verdict). Returns scenarios in ledger order, deduped by session.
     */
    suspend fun loadScenarios(impulses: Series<CuratorImpulse>): Series<ReplayScenario> = withContext(Dispatchers.IO) {
        val db = File(profileDir, "state.db")
        if (!db.isFile || impulses.size == 0) return@withContext emptySeriesOf()
        val seen = HashSet<String>()
        val scenarios = ArrayList<ReplayScenario>()
        for (i in 0 until impulses.size) {
            val imp = impulses[i]
            val sid = imp.proposalCid ?: continue
            if (sid.isEmpty() || !seen.add(sid)) continue
            val turns = readTurns(db, sid) ?: continue
            scenarios.add(ScenarioTranscripts.scenario(sid, imp.subject, turns))
        }
        scenarios.size j { i: Int -> scenarios[i] }
    }

    private fun readTurns(db: File, sessionId: String): Series<ScenarioTranscripts.Turn>? =
        runCatching {
            DriverManager.getConnection("jdbc:sqlite:${db.absolutePath}").use { conn ->
                conn.prepareStatement(
                    "SELECT role, content FROM messages WHERE session_id = ? AND content IS NOT NULL ORDER BY id",
                ).use { ps ->
                    ps.setString(1, sessionId)
                    ps.executeQuery().use { rs ->
                        val turns = ArrayList<ScenarioTranscripts.Turn>()
                        while (rs.next() && turns.size < maxTurnsPerScenario) {
                            turns.add(ScenarioTranscripts.Turn(rs.getString(1) ?: "", rs.getString(2) ?: ""))
                        }
                        if (turns.isEmpty()) null else turns.size j { i: Int -> turns[i] }
                    }
                }
            }
        }.getOrNull()

    /**
     * I2 incremental follow pass. Every `(session, subject)` whose MAX(messages.id) advanced is
     * replayed in full (last marker still wins), taught, and written to the transcript corpus.
     * The returned checkpoint is frozen Series data owned by the caller; no daemon registry.
     */
    suspend fun followOnce(
        element: CuratorImpulseElement,
        store: MemoryStore,
        checkpoint: FollowCheckpoint = FollowCheckpoint.empty(),
    ): FollowResult {
        val impulses = loadImpulses()
        val db = File(profileDir, "state.db")
        if (!db.isFile || impulses.size == 0) {
            return FollowResult(checkpoint, emptyList(), emptySeriesOf())
        }

        data class Changed(val key: String, val rowId: Long, val impulse: CuratorImpulse, val scenario: ReplayScenario)
        val changed = withContext(Dispatchers.IO) {
            val out = ArrayList<Changed>()
            val seen = HashSet<String>()
            for (i in 0 until impulses.size) {
                val impulse = impulses[i]
                val sid = impulse.proposalCid ?: continue
                val key = "$sid\u0000${impulse.subject}"
                if (!seen.add(key)) continue
                val maxId = maxMessageId(db, sid)
                if (maxId <= checkpoint.last(key)) continue
                val turns = readTurns(db, sid) ?: continue
                out.add(Changed(key, maxId, impulse, ScenarioTranscripts.scenario(sid, impulse.subject, turns)))
            }
            out
        }
        if (changed.isEmpty()) return FollowResult(checkpoint, emptyList(), emptySeriesOf())

        val changedImpulses = changed.size j { i: Int -> changed[i].impulse }
        val changedScenarios = changed.size j { i: Int -> changed[i].scenario }
        val landed = element.teach(changedImpulses, changedScenarios)
        val transcriptCids = withContext(Dispatchers.IO) {
            val written = ArrayList<Join<String, borg.trikeshed.job.ContentId>>(changed.size)
            for (i in changed.indices) {
                val scenario = changed[i].scenario
                val body = buildString {
                    append("---\nkind: hermes-transcript\nsession: ").append(scenario.scenarioId).append("\n---\n\n")
                    for (t in 0 until scenario.turns.size) {
                        append(scenario.turns[t].role).append(": ").append(scenario.turns[t].text).append('\n')
                    }
                }
                val safe = scenario.scenarioId.replace(Regex("[^A-Za-z0-9._-]"), "_")
                // Versioned path: transcript snapshots are append-only CAS citizens. Reusing one
                // Couch id would leave the old head in stores that require explicit revisions.
                val path = "/corpus/hermes/transcripts/$safe/${changed[i].rowId}.md"
                written.add(path j store.put(memoryFile(path, "Hermes transcript ${scenario.scenarioId} through row ${changed[i].rowId}", body), "hermes-follower", "transcript"))
            }
            val frozen = written.toList()
            frozen.size j { i: Int -> frozen[i] }
        }

        // Carry old entries not superseded, then append changed entries; freeze as a Series view.
        val next = ArrayList<Join<String, Long>>(checkpoint.sessions.size + changed.size)
        for (i in 0 until checkpoint.sessions.size) {
            val old = checkpoint.sessions[i]
            var superseded = false
            for (c in changed) if (c.key == old.a) { superseded = true; break }
            if (!superseded) next.add(old)
        }
        for (c in changed) next.add(c.key j c.rowId)
        val frozenNext = next.toList()
        return FollowResult(FollowCheckpoint(frozenNext.size j { i: Int -> frozenNext[i] }), landed, transcriptCids)
    }

    private fun maxMessageId(db: File, sessionId: String): Long = runCatching {
        DriverManager.getConnection("jdbc:sqlite:${db.absolutePath}").use { conn ->
            conn.prepareStatement("SELECT COALESCE(MAX(id), 0) FROM messages WHERE session_id = ?").use { ps ->
                ps.setString(1, sessionId)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L }
            }
        }
    }.getOrDefault(0L)

    /**
     * One historical backfill pass: ledger → impulses, state.db → scenarios,
     * [CuratorImpulseElement.teach] banks SUMO/KIF and mints bag signals.
     */
    suspend fun backfill(element: CuratorImpulseElement): List<Join<Long, String>> {
        val impulses = loadImpulses()
        if (impulses.size == 0) return emptyList()
        val scenarios = loadScenarios(impulses)
        return element.teach(impulses, scenarios)
    }
}
