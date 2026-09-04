package borg.trikeshed.jules

import java.io.File
import java.sql.DriverManager

/**
 * What Hermes ACTUALLY ran, from Hermes' own ledger.
 *
 * `$HERMES_HOME/state.db` carries a `session_model_usage` table — model,
 * billing provider, base url, task, call count, tokens, cost,
 * first_seen/last_seen — written by Hermes every time a model answers. It is
 * the only record on this machine of a model call that SUCCEEDED, and the
 * panel reads it next to `mux.meta` for exactly that reason: the pin is
 * intent, this is outcome.
 *
 * WHICH ledger: Hermes keeps one `state.db` per profile, and the profile is
 * `$HERMES_HOME` — the same rule [keymux.defaultHermesHome] gives the KeyMux,
 * so the daemon (which runs under the operator's `src-trikeshed` profile)
 * reads the ledger of the Hermes it shares credentials with. Reading a fixed
 * `~/.hermes` read a different profile's history.
 *
 * This is reporting only. The model instance the daemon borrows comes from
 * [HermesActiveSession] — the session row Hermes itself would resume — not
 * from ranking this table.
 *
 * Read-only, and defensive: an absent db, a locked db, or a schema that has
 * moved on all answer empty rather than throwing into a panel refresh.
 */
object HermesModelUsage {

    data class Usage(
        val model: String,
        val provider: String,
        val baseUrl: String,
        /** Empty for a main conversational turn; a name (`title_generation`, `approval`, …) for a side task. */
        val task: String,
        val calls: Int,
        val inputTokens: Long,
        val outputTokens: Long,
        val lastSeenEpochSeconds: Double,
        /** The `state.db` this row was read from. */
        val ledger: String = "",
    )

    /**
     * The hermes home this process shares with Hermes: `$HERMES_HOME` (the
     * active profile — `~/.hermes/profiles/src-trikeshed` for the daemon) else
     * `~/.hermes`, with `~` expanded.
     */
    fun hermesHome(getenv: (String) -> String? = { System.getenv(it) }): String =
        expandHome(keymux.defaultHermesHome(getenv))

    fun stateDb(hermesHome: String = hermesHome()): File = File(hermesHome, "state.db")

    /** Most recently used first — [limit] rows, newest `last_seen` wins. */
    fun recent(db: File = stateDb(), limit: Int = 16): List<Usage> = runCatching {
        if (!db.isFile) return emptyList()
        val props = org.sqlite.SQLiteConfig().apply {
            setReadOnly(true)
            setBusyTimeout(2_000)
        }.toProperties()
        DriverManager.getConnection("jdbc:sqlite:${db.absolutePath}", props).use { conn ->
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
                                ledger = db.absolutePath,
                            ),
                        )
                    }
                    out
                }
            }
        }
    }.getOrElse { emptyList() }

    /** The single most recent row that answered, or null if the ledger is empty. */
    fun lastUsed(db: File = stateDb()): Usage? = recent(db, limit = 1).firstOrNull()

    /** The ledger as [modelmux.LedgerRow]s for [modelmux.QuotaLegion.fromLedger] — every row Hermes wrote, newest first. */
    fun ledgerRows(db: File = stateDb(), limit: Int = 4096): List<modelmux.LedgerRow> =
        recent(db, limit).map { u ->
            modelmux.LedgerRow(
                provider = u.provider, model = u.model,
                inputTokens = u.inputTokens, outputTokens = u.outputTokens,
                lastSeenMs = (u.lastSeenEpochSeconds * 1000.0).toLong(), calls = u.calls,
            )
        }

    /**
     * Distinct endpoints that have ever produced a completion, newest first.
     *
     * A base url in here has demonstrably answered on this machine, and one
     * that is not in here has not, however confidently it is configured.
     */
    fun provenEndpoints(db: File = stateDb()): List<Pair<String, String>> =
        recent(db, limit = 256)
            .filter { it.baseUrl.isNotEmpty() }
            .map { it.provider to it.baseUrl }
            .distinct()

    internal fun expandHome(path: String): String {
        val home = System.getProperty("user.home")
        return when {
            path == "~" -> home
            path.startsWith("~/") -> home + path.substring(1)
            else -> path
        }
    }
}
