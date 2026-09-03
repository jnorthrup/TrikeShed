package borg.trikeshed.jules

import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The files whose change means "Hermes' world moved": its `state.db` (and the
 * WAL/SHM sidecars SQLite actually writes to), `auth.json`, and `.env`.
 * [stamp] is the newest mtime among them — cheap enough to ask on every call.
 */
class HermesWatch(val files: List<File>) {
    fun stamp(): Long = files.maxOfOrNull { runCatching { it.lastModified() }.getOrDefault(0L) } ?: 0L

    companion object {
        fun default(hermesHome: String = HermesModelUsage.hermesHome()): HermesWatch = HermesWatch(
            listOf("state.db", "state.db-wal", "state.db-shm", "auth.json", ".env").map { File(hermesHome, it) },
        )
    }
}

/** Rebuilds [build]'s value whenever [watch] moves; otherwise hands back the last one. */
class LiveHolder<T : Any>(
    private val watch: HermesWatch,
    initial: T? = null,
    private val build: suspend () -> T,
) {
    private val gate = Mutex()
    @Volatile private var stamp: Long = if (initial != null) watch.stamp() else Long.MIN_VALUE
    @Volatile private var value: T? = initial

    suspend fun current(): T {
        val s = watch.stamp()
        val v = value
        if (v != null && s == stamp) return v
        return gate.withLock {
            val again = value
            if (again != null && stamp == s) again
            else build().also { value = it; stamp = s }
        }
    }

    fun peek(): T? = value
}

/**
 * A BrainClient that follows Hermes. Every call goes to the client built from
 * Hermes' CURRENT session row; when `state.db`, `auth.json`, or `.env` change,
 * the next call rebuilds first. [pin] is the account of the pin in force — the
 * `hermes.lastUsed` node reports it. The non-suspending views (roster, last
 * model, …) answer from the last build; they cannot rebuild without a call.
 */
class LiveBrainClient(
    watch: HermesWatch,
    build: suspend () -> Pair<BrainClient, Map<String, Any?>>,
    initial: Pair<BrainClient, Map<String, Any?>>,
) : BrainClient() {
    @Volatile private var account: Map<String, Any?> = initial.second
    @Volatile private var last: BrainClient = initial.first
    private val holder = LiveHolder(watch, initial.first) { build().also { account = it.second }.first }

    val pin: Map<String, Any?> get() = account

    private suspend fun live(): BrainClient = holder.current().also { last = it }

    override fun hasEndpoints(): Boolean = last.hasEndpoints()
    override fun endpointSummaries(): List<EndpointSpec> = last.endpointSummaries()
    override fun lastModel(): String? = last.lastModel()
    override fun modelMux(): modelmux.ModelMux = last.modelMux()
    override fun providerRoster(): List<EndpointSpec> = last.providerRoster()
    override suspend fun quotaStandings(nowMs: Long): List<modelmux.QuotaStanding> = live().quotaStandings(nowMs)
    override suspend fun rosterStatus(): List<Map<String, Any>> = live().rosterStatus()
    override suspend fun chat(
        messages: List<Pair<String, String>>,
        maxTokens: Int,
        temperature: Double,
        contextId: String?,
    ): String = live().chat(messages, maxTokens, temperature, contextId)
    override suspend fun chatSeat(
        messages: List<Pair<String, String>>,
        maxTokens: Int,
        temperature: Double,
        contextId: String?,
        preferredModel: String?,
    ): Pair<String, String> = live().chatSeat(messages, maxTokens, temperature, contextId, preferredModel)
}
