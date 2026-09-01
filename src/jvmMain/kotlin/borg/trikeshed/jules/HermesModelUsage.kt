package borg.trikeshed.jules

import java.io.File
import java.sql.DriverManager

/**
 * What Hermes ACTUALLY ran, from Hermes' own ledger.
 *
 * `~/.hermes/state.db` carries a `session_model_usage` table — model, billing
 * provider, base url, task, call count, tokens, cost, first_seen/last_seen —
 * written by Hermes every time a model answers. It is the only record on this
 * machine of a model call that SUCCEEDED.
 *
 * That matters more than telemetry. `mux.meta` reports `selection: null` and
 * `brain-errors.jsonl` is 14 attempts with zero successes, all against the
 * pinned `GLM_BASE_URL`. Meanwhile this table shows models answering through
 * entirely different endpoints. The pin and the evidence disagree, and only one
 * of them has ever produced a completion.
 *
 * Read-only, and defensive: an absent db, a locked db, or a schema that has
 * moved on all answer empty rather than throwing into a panel refresh.
 */
object HermesModelUsage {

    data class Usage(
        val model: String,
        val provider: String,
        val baseUrl: String,
        val task: String,
        val calls: Int,
        val inputTokens: Long,
        val outputTokens: Long,
        val lastSeenEpochSeconds: Double,
    )

    fun stateDb(hermesHome: String = System.getProperty("user.home") + "/.hermes"): File =
        File(hermesHome, "state.db")

    /**
     * Most recently used first. [limit] rows, newest `last_seen` wins — which is
     * the "last used model" a panel wants at the top.
     */
    fun recent(db: File = stateDb(), limit: Int = 16): List<Usage> = runCatching {
        if (!db.isFile) return emptyList()
        DriverManager.getConnection("jdbc:sqlite:${db.absolutePath}").use { conn ->
            conn.prepareStatement(
                """
                SELECT model, billing_provider, billing_base_url, task,
                       api_call_count, input_tokens, output_tokens, last_seen
                  FROM session_model_usage
                 ORDER BY last_seen DESC
                 LIMIT ?
                """.trimIndent(),
            ).use { ps ->
                ps.setInt(1, limit)
                ps.executeQuery().use { rs ->
                    val out = ArrayList<Usage>()
                    while (rs.next()) {
                        out.add(
                            Usage(
                                model = rs.getString(1) ?: continue,
                                provider = rs.getString(2) ?: "",
                                baseUrl = rs.getString(3) ?: "",
                                task = rs.getString(4) ?: "",
                                calls = rs.getInt(5),
                                inputTokens = rs.getLong(6),
                                outputTokens = rs.getLong(7),
                                lastSeenEpochSeconds = rs.getDouble(8),
                            ),
                        )
                    }
                    out
                }
            }
        }
    }.getOrElse { emptyList() }

    /** The single most recent model that answered, or null if the ledger is empty. */
    fun lastUsed(db: File = stateDb()): Usage? = recent(db, limit = 1).firstOrNull()

    /**
     * Distinct endpoints that have ever produced a completion, newest first.
     *
     * This is the list worth reading next to the pin: a base url in here has
     * demonstrably answered on this machine, and one that is not in here has
     * not, however confidently it is configured.
     */
    fun provenEndpoints(db: File = stateDb()): List<Pair<String, String>> =
        recent(db, limit = 256)
            .filter { it.baseUrl.isNotEmpty() }
            .map { it.provider to it.baseUrl }
            .distinct()
}
