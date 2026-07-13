@file:Suppress("NonAsciiCharacters", "UNCHECKED_CAST", "NAME_SHADOWING")

package borg.trikeshed.couch

import borg.trikeshed.cursor.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.confix.*

// ─────────────────────────────────────────────────────────────────────────────
// COUCH PIPELINE — DocStore → ViewStore
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Couch pipeline — integrates doc store, view store, and transport.
 */
class CouchPipeline(
    val store: ConfixDocStore = ConfixDocStoreFactory.create(),
    val transport: borg.trikeshed.couch.htx.HtxCouchTransport? = null,
) {
    private val _views: ViewStore by lazy { ViewStore(store) }

    val views: ViewStore get() = _views

    sealed class State {
        data object Empty : State()
        data object Loaded : State()
        data object Indexed : State()
        data object Synced : State()
    }

    private var _state: State = State.Empty
    val state: State get() = _state

    // ── DocStore → ViewStore ──────────────────────────────────────

    fun index(): CouchPipeline {
        _views.defineTaxonomyViews()
        _views.reindex()
        _state = State.Indexed
        return this
    }

    // ── View Queries ────────────────────────────────────────────────

    @Suppress("UNCHECKED_CAST")
    operator fun get(name: String): ViewIndex<*, *, *>? = _views[name]

    @Suppress("UNCHECKED_CAST")
    fun byOwner(owner: String): ViewResult<String, String, Int>? {
        return (_views["_design/taxonomy/_view/by_owner"] as? ViewIndex<String, String, Int>)?.forKey(owner)
    }

    @Suppress("UNCHECKED_CAST")
    fun byKind(kind: Int): ViewResult<String, String, Int>? {
        return (_views["_design/taxonomy/_view/by_kind"] as? ViewIndex<String, String, Int>)?.forKey(kind.toString())
    }

    @Suppress("UNCHECKED_CAST")
    fun byPool(poolId: Int): ViewResult<String, String, Int>? {
        return (_views["_design/taxonomy/_view/by_pool"] as? ViewIndex<String, String, Int>)?.forKey(poolId.toString())
    }

    @Suppress("UNCHECKED_CAST")
    fun allDocs(): ViewResult<String, String, Int>? {
        return (_views["_design/taxonomy/_view/all"] as? ViewIndex<String, String, Int>)?.all()
    }

    // ── Transport Sync ────────────────────────────────────────────

    suspend fun sync(remoteDb: String): CouchPipeline {
        val tp = transport ?: error("No transport configured")
        tp.createDb(remoteDb)
        for (i in 0 until store.size) {
            val entry = store.entries[i]
            tp.put(remoteDb, entry.id, entry.doc, entry.rev)
        }
        _state = State.Synced
        return this
    }

    suspend fun pull(remoteDb: String): CouchPipeline {
        val tp = transport ?: error("No transport configured")
        tp.view(remoteDb, "taxonomy", "all")
        _state = State.Indexed
        return this
    }

    override fun toString(): String =
        "CouchPipeline(state=$_state, docs=${store.size}, views=${_views.size})"
}

object CouchPipelineFactory {
    fun create(): CouchPipeline = CouchPipeline()

    fun createWithTransport(transport: borg.trikeshed.couch.htx.HtxCouchTransport): CouchPipeline =
        CouchPipeline(transport = transport)
}
