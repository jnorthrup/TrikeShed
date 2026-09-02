package borg.trikeshed.jules

import java.io.File

/**
 * The model instances Hermes KNOWS on this machine — the listbox the mux is
 * filled from, instead of a hand-typed roster.
 *
 * Two Hermes tables, both in `$HERMES_HOME/state.db`, are the source:
 *
 *  - `sessions` — the runtime each conversation ran on
 *    ([HermesActiveSession]: model + `gateway_runtime` provider/base_url/api_mode);
 *  - `session_model_usage` — every (model, provider, base_url) that ANSWERED
 *    ([HermesModelUsage]).
 *
 * Union, newest first, one row per (provider, model, base url). A row is kept
 * only when the mux could route it: a base url on record, a provider id the
 * KeyMux `llm.<provider>.key` path can name (one KeyPath segment — `custom:…`
 * ids carry dots and resolve through Hermes' `custom_providers[].key_env`,
 * which this side does not read yet), and a wire protocol the mux speaks
 * (`chat_completions`; a session that says its provider runs `responses` or
 * `anthropic_messages` rules that provider's usage rows out too).
 *
 * These become ModelMux cards ahead of the static roster, so
 * `mux.models#models[].id` — the live picklist `prompt.chat` already declares —
 * offers what Hermes actually runs, and the brain's roster fallback tries
 * proven endpoints before guessed ones. Nothing here is chosen; it is listed.
 */
object HermesInstances {

    data class Instance(
        val model: String,
        val provider: String,
        val baseUrl: String,
        val apiMode: String?,
        /** `session:<id>` or `usage:<task|main>` — where Hermes recorded it. */
        val source: String,
        val lastSeenEpochSeconds: Double,
    )

    /** A provider id `llm.<provider>.key` can name: non-blank, one KeyPath segment, not a bare billing bucket. */
    internal fun routableProvider(provider: String?): Boolean =
        !provider.isNullOrBlank() && '.' !in provider && provider.lowercase() !in HermesActiveSession.BARE_BILLING_PROVIDERS

    /** Everything Hermes has run or been answered by, newest first, routable rows only. */
    fun known(db: File = HermesModelUsage.stateDb(), sessions: Int = 64, usage: Int = 256): List<Instance> =
        merge(HermesActiveSession.recent(db, sessions), HermesModelUsage.recent(db, usage))

    /** Pure merge of the two tables' rows — see the object doc for the rules. */
    internal fun merge(sessions: List<HermesActiveSession.Session>, usage: List<HermesModelUsage.Usage>): List<Instance> {
        // A provider a session says does not speak chat_completions is out
        // wholesale — the usage ledger does not record the wire protocol.
        val nonChat = sessions
            .filter { it.runtime.provider != null && !it.runtime.speaksChatCompletions }
            .mapTo(HashSet()) { it.runtime.provider!! }
        val candidates = ArrayList<Instance>()
        for (s in sessions) {
            val model = s.model?.takeIf { it.isNotBlank() } ?: continue
            val provider = s.runtime.provider ?: continue
            val baseUrl = s.runtime.baseUrl?.takeIf { it.isNotBlank() } ?: continue
            candidates += Instance(model, provider, baseUrl, s.runtime.apiMode, "session:${s.id}", s.recencyEpochSeconds)
        }
        for (u in usage) {
            if (u.model.isBlank() || u.baseUrl.isBlank()) continue
            candidates += Instance(u.model, u.provider, u.baseUrl, null, "usage:${u.task.ifEmpty { "main" }}", u.lastSeenEpochSeconds)
        }
        val out = LinkedHashMap<Triple<String, String, String>, Instance>()
        for (c in candidates.sortedByDescending { it.lastSeenEpochSeconds }) {
            if (!routableProvider(c.provider)) continue
            if (c.provider in nonChat) continue
            if (c.apiMode != null && c.apiMode != "chat_completions") continue
            val baseUrl = c.baseUrl.trimEnd('/')
            val key = Triple(c.provider, c.model, baseUrl)
            if (key !in out) out[key] = c.copy(baseUrl = baseUrl)
        }
        return out.values.toList()
    }

    /** The same rows as endpoint specs the daemon's card builder already consumes. */
    fun specs(instances: List<Instance>): List<BrainClient.EndpointSpec> =
        instances.map {
            BrainClient.EndpointSpec(name = "hermes:${it.provider}", envVar = "", base = it.baseUrl, model = it.model, provider = it.provider)
        }
}
