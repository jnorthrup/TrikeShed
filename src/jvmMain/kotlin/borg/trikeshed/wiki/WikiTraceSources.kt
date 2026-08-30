package borg.trikeshed.wiki

import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.sql.DriverManager

/**
 * Where a WikiSkill execution trace comes from, in the daemon.
 *
 * The raw/ layer of the paper's three-layer structure is the CAS: the
 * transcript snapshots [borg.trikeshed.narsese.CuratorImpulseFeeder] writes
 * (`/corpus/hermes/transcripts/<session>/<rowId>.md`), addressed by the
 * SHA-256 of the snapshot body.
 *
 * A cid the live daemon never wrote (an M1 driver ran the same lane in its own
 * in-memory store) is still resolvable: rebuild every session's snapshot body
 * from the SAME hermes profile with the SAME serialization, and accept only
 * the one whose SHA-256 EQUALS the requested cid. The cid is the proof — the
 * bytes are byte-identical to the corpus entry or they are not returned. A
 * verified rebuild is then landed in CAS so the next read is a plain hit.
 */
object WikiTraceSources {

    /** The snapshot body CuratorImpulseFeeder.followOnce writes, verbatim. */
    internal fun snapshotBody(sessionId: String, turns: List<Pair<String, String>>): String = buildString {
        append("---\nkind: hermes-transcript\nsession: ").append(sessionId).append("\n---\n\n")
        for ((role, text) in turns) append(role).append(": ").append(text).append('\n')
    }

    private fun sessionIds(db: File): List<String> = runCatching {
        DriverManager.getConnection("jdbc:sqlite:${db.absolutePath}").use { conn ->
            conn.prepareStatement("SELECT DISTINCT session_id FROM messages WHERE session_id IS NOT NULL").use { ps ->
                ps.executeQuery().use { rs ->
                    val out = ArrayList<String>()
                    while (rs.next()) rs.getString(1)?.let { out.add(it) }
                    out
                }
            }
        }
    }.getOrDefault(emptyList())

    /** Role is lowercased exactly as ScenarioTranscripts.scenario shapes it. */
    private fun turns(db: File, sessionId: String, maxTurns: Int): List<Pair<String, String>> = runCatching {
        DriverManager.getConnection("jdbc:sqlite:${db.absolutePath}").use { conn ->
            conn.prepareStatement(
                "SELECT role, content FROM messages WHERE session_id = ? AND content IS NOT NULL ORDER BY id",
            ).use { ps ->
                ps.setString(1, sessionId)
                ps.executeQuery().use { rs ->
                    val out = ArrayList<Pair<String, String>>()
                    while (rs.next() && out.size < maxTurns) {
                        out.add((rs.getString(1) ?: "").lowercase() to (rs.getString(2) ?: ""))
                    }
                    out
                }
            }
        }
    }.getOrDefault(emptyList())

    /**
     * CAS first; on a miss, a cid-VERIFIED rebuild from [profileDir]'s
     * `state.db`. Returns null when neither yields bytes whose SHA-256 is the
     * requested cid — a trace is never approximated.
     */
    fun loader(cas: CasStore, profileDir: File, maxTurns: Int = 400): WikiNodes.WikiTraceLoader =
        WikiNodes.WikiTraceLoader { raw ->
            val cid = if (raw.startsWith("sha256:")) raw else "sha256:$raw"
            withContext(Dispatchers.IO) {
                val hit = runCatching { cas.get(ContentId(cid)) }.getOrNull()
                if (hit != null) return@withContext WikiNodes.WikiTrace(cid, hit.decodeToString(), "cas")
                val db = File(profileDir, "state.db")
                if (!db.isFile) return@withContext null
                for (sid in sessionIds(db)) {
                    val body = snapshotBody(sid, turns(db, sid, maxTurns))
                    if (ContentId.of(body.encodeToByteArray()).value != cid) continue
                    runCatching { cas.put(body.encodeToByteArray()) }
                    return@withContext WikiNodes.WikiTrace(cid, body, "hermes-rebuild:$sid")
                }
                null
            }
        }
}
