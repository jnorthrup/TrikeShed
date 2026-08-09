package borg.trikeshed.memory

import borg.trikeshed.couch.CouchStore
import borg.trikeshed.couch.isam.Stringpool
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.view
import borg.trikeshed.memory.MemoryStore
import kotlin.concurrent.Volatile

/**
 * ISAM-indexed retrieval route layer for the memory store (Prong 2).
 *
 * Subscribes to [MemoryStore] mutation events and maintains multiple ISAM
 * routes over the same CAS blobs. Each route is a lazy projection keyed by
 * a different facet — the paper's "numerous layers of blackboard and ISAM
 * routes that can branch off from couch blobs."
 *
 * Routes:
 *   - [TaxonomyRoute]: indexed by memory file path hierarchy (the paper's
 *     taxonomy — folders + filenames).
 *   - [TemporalRoute]: indexed by the Couch document's sequence field (the
 *     paper's temporal ordering — "when something happened is part of the fact").
 *   - [ProvenanceRoute]: indexed by agentId (which management agent wrote
 *     each memory — the paper's RQ3 model-capability axis).
 *
 * All routes are projections over the same underlying [MemoryStore]; no blob
 * duplication. Updates are eventually-consistent: a mutation event triggers
 * a route rebuild for the affected path.
 */

/** The kind of ISAM route branching off the CAS blobs. */
sealed class IndexKind {
    data object Taxonomy : IndexKind()
    data object Temporal : IndexKind()
    data object Provenance : IndexKind()
    /** Raw Git/worktree Couch attachments, kept separate from memory documents. */
    data object RepositoryTaxonomy : IndexKind()
}

data class CouchIndexEntry(
    val path: String,
    val hash: ContentId,
    val taxonomy: Series<String>,
    val timestamp: Long,
)

/**
 * One ISAM route: a kind paired with an index map.
 * The index maps a key string to a list of memory file paths.
 */
data class MemoryIndexRoute(
    val kind: IndexKind,
    private val index: MutableMap<String, MutableList<String>> = mutableMapOf(),
) {
    /** Look up paths by route key. */
    fun lookup(key: String): Series<String> {
        val paths = index[key] ?: emptyList()
        return paths.size j { i -> paths[i] }
    }

    /** All keys in this route. */
    val keys: Series<String> get() {
        val ks = index.keys.toList()
        return ks.size j { i -> ks[i] }
    }

    /** Add a path under a key. */
    internal fun add(key: String, path: String) {
        index.getOrPut(key) { mutableListOf() }
        if (path !in index[key]!!) index[key]!!.add(path)
    }

    /** Remove a path from all keys. */
    internal fun remove(path: String) {
        for ((_, paths) in index) paths.remove(path)
        index.entries.removeAll { it.value.isEmpty() }
    }

    internal fun removePrefix(prefix: String) {
        for ((_, paths) in index) paths.removeAll { it.startsWith(prefix) }
        index.entries.removeAll { it.value.isEmpty() }
    }

    /** Total indexed entries across all keys. */
    val entryCount: Int get() = index.values.sumOf { it.size }
}

/**
 * Index manager: holds multiple routes, subscribes to [MemoryStore] mutations,
 * and rebuilds routes incrementally on each mutation event.
 */
class MemoryIndexLayer(store: MemoryStore) {

    private val taxonomyRoute = MemoryIndexRoute(IndexKind.Taxonomy)
    private val temporalRoute = MemoryIndexRoute(IndexKind.Temporal)
    private val provenanceRoute = MemoryIndexRoute(IndexKind.Provenance)
    private val repositoryTaxonomyRoute = MemoryIndexRoute(IndexKind.RepositoryTaxonomy)

    /** The mutation subscription handle — call to unsubscribe. */
    private val unsubscribe: () -> Unit

    @Volatile
    var totalMutations: Int = 0
        private set

    init {
        // Subscribe to mutations and rebuild routes incrementally.
        unsubscribe = store.subscribeMutations { event ->
            totalMutations++
            when (event) {
                is CouchStore.MutationEvent.Inserted -> handleMutation(event.doc, isNew = true)
                is CouchStore.MutationEvent.Updated -> handleMutation(event.doc, isNew = false)
                is CouchStore.MutationEvent.Deleted -> handleDelete(event.docId)
            }
        }
    }

    /** Stop indexing. */
    fun close() = unsubscribe()

    /**
     * Mutation observers update routes synchronously inside CouchStore.put(),
     * so returning from this suspend boundary guarantees index parity.
     */
    suspend fun flush() = Unit

    /** Get a route by kind. */
    fun route(kind: IndexKind): MemoryIndexRoute = when (kind) {
        IndexKind.Taxonomy -> taxonomyRoute
        IndexKind.Temporal -> temporalRoute
        IndexKind.Provenance -> provenanceRoute
        IndexKind.RepositoryTaxonomy -> repositoryTaxonomyRoute
    }

    /** Replace one reconciled repository partition without MemoryStore writes. */
    fun replaceRepositoryBatch(prefix: String, entries: Series<CouchIndexEntry>) {
        repositoryTaxonomyRoute.removePrefix(prefix)
        for (entry in entries.view) {
            val key = entry.taxonomy.view.joinToString("/").ifEmpty { "root" }
            repositoryTaxonomyRoute.add(key, entry.path)
        }
    }

    fun queryRepositoryPath(prefix: String): Series<String> {
        val results = mutableListOf<String>()
        for (i in 0 until repositoryTaxonomyRoute.keys.size) {
            val key = repositoryTaxonomyRoute.keys[i]
            val paths = repositoryTaxonomyRoute.lookup(key)
            for (j in 0 until paths.size) {
                val path = paths[j]
                if (path.startsWith(prefix)) results.add(path)
            }
        }
        return results.size j { i -> results[i] }
    }

    /**
     * Query the taxonomy route by directory prefix.
     * Returns all paths under the given directory.
     */
    fun queryByPath(prefix: String): Series<String> {
        val results = mutableListOf<String>()
        for (i in 0 until taxonomyRoute.keys.size) {
            val key = taxonomyRoute.keys[i]
            if (key.startsWith(prefix)) {
                val paths = taxonomyRoute.lookup(key)
                for (j in 0 until paths.size) results.add(paths[j])
            }
        }
        return results.size j { i -> results[i] }
    }

    /**
     * Query the temporal route by sequence range.
     * Returns all paths written between [minSeq] and [maxSeq] inclusive.
     */
    fun queryBySequence(minSeq: Long, maxSeq: Long): Series<String> {
        val results = mutableListOf<String>()
        for (i in 0 until temporalRoute.keys.size) {
            val key = temporalRoute.keys[i]
            val seq = key.toLongOrNull() ?: continue
            if (seq in minSeq..maxSeq) {
                val paths = temporalRoute.lookup(key)
                for (j in 0 until paths.size) results.add(paths[j])
            }
        }
        return results.size j { i -> results[i] }
    }

    /**
     * Query the provenance route by agent ID.
     * Returns all paths written by [agentId].
     */
    fun queryByAgent(agentId: String): Series<String> = provenanceRoute.lookup(agentId)

    // ── Internal mutation handlers ──────────────────────────────────

    private fun handleMutation(doc: borg.trikeshed.couch.Document, isNew: Boolean) {
        val path = doc.id
        if (!isNew) {
            // On update, remove old index entries for this path.
            taxonomyRoute.remove(path)
            temporalRoute.remove(path)
            provenanceRoute.remove(path)
        }

        // Extract fields from the Couch document.
        // Raw Git/worktree attachment documents share this CouchStore but do
        // not belong in memory routes. MemoryStore documents are identified by
        // their explicit `kind` field.
        if (doc.fields.none { it.name == "kind" }) return
        val agentId = doc.fields.find { it.name == "agentId" }?.value as? String ?: "system"
        val seqStr = doc.fields.find { it.name == "sequence" }?.value as? String

        // Taxonomy route: key = parent directory.
        val dir = path.substringBeforeLast('/', "/memories")
        taxonomyRoute.add(dir, path)

        // Temporal route: key = sequence (if present).
        if (seqStr != null) {
            temporalRoute.add(seqStr, path)
        }

        // Provenance route: key = agentId.
        provenanceRoute.add(agentId, path)
    }

    private fun handleDelete(docId: String) {
        taxonomyRoute.remove(docId)
        temporalRoute.remove(docId)
        provenanceRoute.remove(docId)
    }
}
