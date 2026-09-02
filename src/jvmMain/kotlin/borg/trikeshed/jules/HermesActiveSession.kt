package borg.trikeshed.jules

import borg.trikeshed.parse.json.JsonSupport
import java.io.File
import java.sql.DriverManager

/**
 * The Hermes session that is live right now, and the model instance it runs
 * on — read the way Hermes reads them, from `$HERMES_HOME/state.db`.
 *
 * Hermes keeps one row per conversation in `sessions` (`hermes_state.py`).
 * Two of its rules are mirrored here so this answers what `hermes sessions`
 * and `hermes resume` would:
 *
 *  - WHICH session — user-visible rows only: roots (`parent_session_id IS
 *    NULL`) and `/branch` children (a `_branched_from` marker in
 *    `model_config`); never a delegate subagent run (`_delegate_from` in
 *    `model_config`) nor a compression continuation (a parented row without
 *    the branch marker). Ranked by `COALESCE(last_activity_at, started_at)
 *    DESC` — Hermes' #82616 fix: ranking on `started_at` alone resurrected
 *    days-old zombie rows over the live conversation. An open row (`ended_at
 *    IS NULL`) outranks any ended one.
 *  - WHICH runtime — `SessionDB.session_gateway_runtime`: the `gateway_runtime`
 *    dict inside `model_config` (written by `/model` persist and the gateway's
 *    model sync), else the top-level `provider`/`base_url`/`api_mode` keys the
 *    TUI writes, else the `billing_provider` column stamped on the session's
 *    first accounted API call — unless it is a bare bucket (`auto`, `custom`).
 *
 * Credentials are deliberately NOT resolved here. Hermes does that in
 * `resolve_api_key_provider_credentials(provider)`: the provider's env vars
 * (`.env` preferred over the shell), then its credential pool. On this side
 * that is the KeyMux `llm.<provider>.key` lane — [keymux.HarnessSource] for
 * the env vars, [keymux.HermesCredentialSource] for the pool — which the
 * daemon asks with the [Runtime.provider] this object hands it.
 *
 * Read-only and defensive: an absent, locked, or schema-shifted db answers
 * empty rather than throwing into a daemon boot.
 */
object HermesActiveSession {

    /** Hermes' `_BARE_BILLING_PROVIDERS`: billing buckets that are not routable identities. */
    internal val BARE_BILLING_PROVIDERS = setOf("auto", "custom")

    /** The resolved route a session ran on; any field may be unknown. */
    data class Runtime(val provider: String?, val baseUrl: String?, val apiMode: String?) {
        /** BrainClient posts `/chat/completions`; a session on another wire protocol is not borrowable. */
        val speaksChatCompletions: Boolean get() = apiMode == null || apiMode == "chat_completions"
    }

    data class Session(
        val id: String,
        val source: String,
        val model: String?,
        val runtime: Runtime,
        val startedAt: Double,
        val endedAt: Double?,
        val lastActivityAt: Double?,
        val messageCount: Int,
        val apiCallCount: Int,
        val cwd: String?,
        /** The `state.db` this row was read from. */
        val ledger: String,
    ) {
        val isOpen: Boolean get() = endedAt == null

        /** Hermes' recency: `COALESCE(last_activity_at, started_at)`, epoch seconds. */
        val recencyEpochSeconds: Double get() = lastActivityAt ?: startedAt
    }

    fun stateDb(hermesHome: String = HermesModelUsage.hermesHome()): File = File(hermesHome, "state.db")

    /**
     * The live session: the most recent OPEN user-visible row, else the most
     * recent ended one (what `hermes resume` would reopen), else null.
     */
    fun current(db: File = stateDb()): Session? = recent(db, limit = 1).firstOrNull()

    /** User-visible sessions, open ones first, then by Hermes' recency. */
    fun recent(db: File = stateDb(), limit: Int = 8): List<Session> = runCatching {
        if (!db.isFile) return emptyList()
        val props = org.sqlite.SQLiteConfig().apply {
            setReadOnly(true)
            setBusyTimeout(2_000)
        }.toProperties()
        DriverManager.getConnection("jdbc:sqlite:${db.absolutePath}", props).use { conn ->
            conn.prepareStatement(
                """
                SELECT id, source, model, model_config, parent_session_id,
                       billing_provider,
                       started_at, ended_at, last_activity_at,
                       message_count, api_call_count, cwd
                  FROM sessions
                 ORDER BY (ended_at IS NULL) DESC,
                          COALESCE(last_activity_at, started_at) DESC
                 LIMIT ?
                """.trimIndent(),
            ).use { ps ->
                // Delegates and continuations are filtered after the fetch (their
                // markers live inside model_config JSON), so over-fetch.
                ps.setInt(1, (limit * 8).coerceAtLeast(64))
                ps.executeQuery().use { rs ->
                    val out = ArrayList<Session>()
                    while (rs.next() && out.size < limit) {
                        val id = rs.getString(1) ?: continue
                        val modelConfig = parseModelConfig(rs.getString(4))
                        if (!listable(rs.getString(5), modelConfig)) continue
                        out.add(
                            Session(
                                id = id,
                                source = rs.getString(2) ?: "",
                                model = str(rs.getString(3)) ?: str(modelConfig?.get("model")),
                                runtime = runtime(modelConfig, rs.getString(6)),
                                startedAt = rs.getDouble(7),
                                endedAt = rs.getObject(8)?.let { rs.getDouble(8) },
                                lastActivityAt = rs.getObject(9)?.let { rs.getDouble(9) },
                                messageCount = rs.getInt(10),
                                apiCallCount = rs.getInt(11),
                                cwd = str(rs.getString(12)),
                                ledger = db.absolutePath,
                            ),
                        )
                    }
                    out
                }
            }
        }
    }.getOrElse { emptyList() }

    /**
     * Hermes' listing predicate (`list_sessions_rich`, `include_children=False`):
     * roots and `/branch` children are user-visible; delegate subagent runs
     * (`_delegate_from`) and compression continuations (parented, unmarked) are not.
     */
    internal fun listable(parentSessionId: String?, modelConfig: Map<*, *>?): Boolean {
        if (modelConfig?.get("_delegate_from") != null) return false
        if (parentSessionId == null) return true
        return modelConfig?.get("_branched_from") != null
    }

    /**
     * Mirror of `SessionDB.session_gateway_runtime`: `gateway_runtime` (when it
     * names a provider), else the top-level route keys (any of them), else the
     * `billing_provider` column ALONE — Hermes returns `{"provider": …}` there
     * and lets provider resolution supply the base url — bare buckets excluded.
     */
    internal fun runtime(modelConfig: Map<*, *>?, billingProvider: String?): Runtime {
        val gateway = modelConfig?.get("gateway_runtime") as? Map<*, *>
        if (gateway != null && str(gateway["provider"]) != null) {
            return Runtime(str(gateway["provider"]), str(gateway["base_url"]), str(gateway["api_mode"]))
        }
        val top = Runtime(str(modelConfig?.get("provider")), str(modelConfig?.get("base_url")), str(modelConfig?.get("api_mode")))
        if (top.provider != null || top.baseUrl != null || top.apiMode != null) return top
        val billing = str(billingProvider)?.takeIf { it.lowercase() !in BARE_BILLING_PROVIDERS }
        return Runtime(billing, null, null)
    }

    internal fun parseModelConfig(json: String?): Map<*, *>? {
        if (json.isNullOrBlank()) return null
        return runCatching { JsonSupport.parse(json) as? Map<*, *> }.getOrNull()
    }

    private fun str(v: Any?): String? = (v as? String)?.trim()?.takeIf { it.isNotEmpty() }
}
