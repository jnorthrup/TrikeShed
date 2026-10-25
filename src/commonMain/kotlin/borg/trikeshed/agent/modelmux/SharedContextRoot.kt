package borg.trikeshed.agent.modelmux

import borg.trikeshed.cas.KuboContextBlockExchange
import borg.trikeshed.cas.KuboNode
import borg.trikeshed.job.CasStore
import borg.trikeshed.userspace.nio.channels.spi.ProcessOperations

/**
 * One Kubo node to read blocks from, named explicitly. Absent settings mean LOCAL: nothing is ever fetched from anyone.
 * The rules are the adapter's own ([KuboNode]), applied when the setting is made, so a bad value fails at
 * configuration time and not at the first request: the repo is an absolute path, and the API, when given, is on
 * loopback. A remote API is deliberately not expressible here; that approval belongs to a human at the adapter.
 */
class PeerSettings(
    val repo: String,
    val api: String? = null,
    val binary: String = "ipfs",
    val timeoutSeconds: Int = 30,
) {
    init {
        KuboNode(repo, api)
        require(binary.isNotBlank() && binary.none { it < ' ' || it == '\u007f' }) { "peer binary must name an executable" }
        require(timeoutSeconds in 1..3_600) { "peer timeout must be 1..3600 seconds" }
    }
}

/**
 * What a root decides about shared context, once. Scopes are mandatory because nothing is served by default;
 * publication is not part of these settings at all, so no setting here can export anything.
 */
class SharedContextSettings(
    val scopes: Set<String>,
    val peer: PeerSettings? = null,
    val parallelism: Int = 4,
    val capacity: Int = 16,
) {
    init {
        require(scopes.isNotEmpty()) { "name at least one scope: nothing is served by default" }
        for (scope in scopes) require(isScopeName(scope)) { "a scope is 1..256 printable characters without surrounding whitespace" }
        require(parallelism in 1..64) { "parallelism must be 1..64" }
        require(capacity in 1..1_024) { "capacity must be 1..1024" }
    }

    val mode: SharedContextMode get() = if (peer == null) SharedContextMode.LOCAL else SharedContextMode.PEER

    companion object {
        fun isScopeName(value: String): Boolean =
            value.length in 1..256 && value == value.trim() && value.none { it < ' ' || it == '\u007f' }
    }
}

/**
 * Builds the one [SharedContextElement] a root owns. Every child of that root (a service, a tool call, a task) reads
 * through it, so identical misses become one transfer. The root drains it; children never do.
 */
object SharedContextRoot {
    const val SCOPES = "OROBOROS_CONTEXT_SCOPES"
    const val KUBO_REPO = "OROBOROS_CONTEXT_KUBO_REPO"
    const val KUBO_API = "OROBOROS_CONTEXT_KUBO_API"
    const val KUBO_BIN = "OROBOROS_CONTEXT_KUBO_BIN"
    const val TIMEOUT_SECONDS = "OROBOROS_CONTEXT_TIMEOUT_S"

    /** Scopes served when the environment names none: one run-sized scope per reel, and nothing else. */
    const val DEFAULT_SCOPES = "reel/*"

    /**
     * Reads the settings from [env], which is whatever mapping the caller chooses to pass: nothing is discovered, no
     * dotenv is read and no other repository is guessed. [KUBO_REPO] present means PEER; absent means LOCAL.
     */
    fun settings(env: (String) -> String?): SharedContextSettings {
        val scopes = (env(SCOPES) ?: DEFAULT_SCOPES).split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val repo = env(KUBO_REPO)?.takeIf { it.isNotBlank() }
        val peer = if (repo == null) {
            require(env(KUBO_API).isNullOrBlank()) { "$KUBO_API needs $KUBO_REPO: a node is named by its repository" }
            null
        } else {
            PeerSettings(
                repo = repo,
                api = env(KUBO_API)?.takeIf { it.isNotBlank() },
                binary = env(KUBO_BIN)?.takeIf { it.isNotBlank() } ?: "ipfs",
                timeoutSeconds = env(TIMEOUT_SECONDS)?.takeIf { it.isNotBlank() }
                    ?.let { it.toIntOrNull() ?: throw IllegalArgumentException("$TIMEOUT_SECONDS must be a whole number of seconds") } ?: 30,
            )
        }
        return SharedContextSettings(scopes, peer)
    }

    /**
     * The breadcrumb contexts of a root. Their searches rank through the facet memory this root's shared context answers
     * projections with, so a record the agent kept and a document a peer pinned that have the same bytes are one document
     * facet and one set of line features. [store] persists the sessions; [cas] holds their originals and stays the
     * caller's. Ranking is by `search` over the agent's own records only: nothing is added from a peer and nothing leaves.
     */
    fun breadcrumbs(shared: SharedContextElement, cas: CasStore, store: BreadcrumbStore? = null): BreadcrumbElement =
        BreadcrumbElement(store = store, cas = cas, facets = shared.context.facets)

    /** Opens the element owned by the calling coroutine's Job over [cas], which stays the caller's to close. */
    suspend fun open(id: String, cas: CasStore, settings: SharedContextSettings, process: ProcessOperations): SharedContextElement {
        val exchange = settings.peer?.let { KuboContextBlockExchange(process, KuboNode(it.repo, it.api), it.binary, it.timeoutSeconds) }
        return SharedContextElement.open(
            id, cas, exchange,
            ContextReadPolicy(mode = settings.mode, allowedScopes = settings.scopes),
            settings.parallelism, settings.capacity,
        )
    }
}
