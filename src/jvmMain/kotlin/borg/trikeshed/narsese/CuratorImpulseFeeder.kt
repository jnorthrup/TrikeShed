package borg.trikeshed.narsese

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.emptySeriesOf
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.parse.json.JsonSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.sql.DriverManager

/**
 * CuratorImpulseFeeder — jvmMain adapter that feeds [CuratorImpulseElement]
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
     * One full training pass: ledger → impulses, state.db → scenarios,
     * [CuratorImpulseElement.train] banks SUMO/KIF and mints bag signals.
     */
    suspend fun train(element: CuratorImpulseElement): List<Join<Long, String>> {
        val impulses = loadImpulses()
        if (impulses.size == 0) return emptyList()
        val scenarios = loadScenarios(impulses)
        return element.train(impulses, scenarios)
    }
}
