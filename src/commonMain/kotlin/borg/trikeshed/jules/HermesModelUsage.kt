package borg.trikeshed.jules

import borg.trikeshed.common.File
import borg.trikeshed.platform.HostSystem
import modelmux.LedgerRow

/**
 * What Hermes ACTUALLY ran, from Hermes' own ledger (ported to commonMain).
 *
 * `$HERMES_HOME/state.db` carries a `session_model_usage` table — model,
 * billing provider, base url, task, call count, tokens, cost, first_seen/
 * last_seen — written by Hermes every time a model answers. Read-only and
 * defensive: an absent/locked/moved-on schema answers empty, never throwing
 * into a panel refresh.
 *
 * The SQL itself is platform IO: it lives behind [reader], supplied by the
 * platform actual at bootstrap (JVM: jdbc:sqlite via sqlite-jdbc). Common
 * code never names java.*; when no reader is installed every query answers
 * empty — honest "no data", not a crash.
 */
object HermesModelUsage {

    /** SQLite query execution: SQL in, rows as column-indexed strings/numbers out. */
    fun interface Reader {
        fun query(db: File, sql: String, params: List<Any?>): List<List<Any?>>
    }

    /** Installed by the platform bootstrap (JVM: jdbc:sqlite). Null = no data source. */
    @Volatile
    var reader: Reader? = null

    data class Usage(
        val model: String,
        val provider: String,
        val baseUrl: String,
        val task: String,
        val calls: Int,
        val inputTokens: Long,
        val outputTokens: Long,
        val lastSeenEpochSeconds: Double,
        val ledger: String,
    )

    fun hermesHome(getenv: (String) -> String? = { borg.trikeshed.platform.HostSystem.getenv(it) }): String =
        getenv("HERMES_HOME")?.takeIf { it.isNotBlank() }
            ?: (getenv("HOME")?.let { home -> home + "/.hermes" }
                ?: (HostSystem.getProperty("user.home")?.let { it + "/.hermes" } ?: ""))

    fun stateDb(hermesHome: String = hermesHome()): File = File(hermesHome).resolve("state.db")

    internal fun expandHome(path: String): String {
        if (!path.startsWith("~")) return path
        val home = borg.trikeshed.platform.HostSystem.getenv("HOME") ?: HostSystem.getProperty("user.home") ?: return path
        return home + path.substring(1)
    }

    fun recent(db: File = stateDb(), limit: Int = 16): List<Usage> = runCatching {
        val r = reader ?: return emptyList()
        if (!db.exists() || !db.isFile()) return emptyList()
        r.query(db, """
            SELECT model, billing_provider, billing_base_url, task,
                   api_call_count, input_tokens, output_tokens, last_seen
              FROM session_model_usage
             ORDER BY last_seen DESC
             LIMIT ?
        """.trimIndent(), listOf(limit)).map { row ->
            Usage(
                model = row[0] as? String ?: return@map null,
                provider = row[1] as? String ?: "",
                baseUrl = row[2] as? String ?: "",
                task = row[3] as? String ?: "",
                calls = (row[4] as? Number)?.toInt() ?: 0,
                inputTokens = (row[5] as? Number)?.toLong() ?: 0L,
                outputTokens = (row[6] as? Number)?.toLong() ?: 0L,
                lastSeenEpochSeconds = (row[7] as? Number)?.toDouble() ?: 0.0,
                ledger = db.absolutePath,
            )
        }.filterNotNull()
    }.getOrElse { emptyList() }

    /** The single most recent row that answered, or null if the ledger is empty. */
    fun lastUsed(db: File = stateDb()): Usage? = recent(db, limit = 1).firstOrNull()

    /** The ledger as LedgerRows for QuotaLegion.fromLedger — every row, newest first. */
    fun ledgerRows(db: File = stateDb(), limit: Int = 4096): List<LedgerRow> =
        recent(db, limit).map { u ->
            LedgerRow(
                provider = u.provider,
                model = u.model,
                inputTokens = u.inputTokens,
                outputTokens = u.outputTokens,
                lastSeenMs = (u.lastSeenEpochSeconds * 1000).toLong(),
                calls = u.calls,
            )
        }

    fun provenEndpoints(db: File = stateDb()): List<Pair<String, String>> =
        recent(db, limit = 4096)
            .filter { it.baseUrl.isNotBlank() }
            .map { it.model to it.baseUrl }
            .distinct()
}
