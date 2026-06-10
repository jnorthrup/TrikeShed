@file:Suppress("NonAsciiCharacters", "UNCHECKED_CAST", "NAME_SHADOWING")

package borg.trikeshed.couch

import borg.trikeshed.cursor.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.confix.*
import borg.trikeshed.userspace.nio.channels.ChannelRunner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

// ─────────────────────────────────────────────────────────────────────────────
// VIEW SERVER — Reactor-hosted CouchDB view protocol
// ─────────────────────────────────────────────────────────────────────────────

/**
 * ViewServer protocol commands (CouchDB-compatible)
 */
sealed class ViewServerCommand {
    data class Open(val db: String) : ViewServerCommand()
    data class AddDoc(val doc: ConfixDoc) : ViewServerCommand()
    class Compact : ViewServerCommand()
    class Info : ViewServerCommand()
    data class Get(val id: String) : ViewServerCommand()
    class ViewAll : ViewServerCommand()
}

/**
 * ViewServer response
 */
sealed class ViewServerResponse {
    data class Ok(val id: String, val rev: String) : ViewServerResponse()
    data class Doc(val doc: ConfixDocStoreEntry?) : ViewServerResponse()
    data class Docs(val rows: Series<ConfixDocStoreEntry>) : ViewServerResponse()
    data class Error(val code: Int, val message: String) : ViewServerResponse()
    data class InfoResponse(val docCount: Int, val updateSeq: Long) : ViewServerResponse()
}

/**
 * Reactor-hosted ViewServer.
 * 
 * Hosts the doc-store and serves CouchDB view protocol over the reactor.
 * Similar to RelaxFactory's RequestQueueVisitor but with:
 * - RequestFactory-style RPC
 * - CRDT realtime sync
 * - NG-SCTP transport support
 */
class ViewServer(
    val pipeline: CouchPipeline,
    val scope: CoroutineScope,
) {
    // The doc store
    val store: ConfixDocStore get() = pipeline.store
    
    // The view store
    val views: ViewStore get() = pipeline.views
    
    // Realtime event flow for CRDT sync
    private val _realtimeEvents = MutableSharedFlow<RealtimeEvent>(replay = 0, extraBufferCapacity = 64)
    val realtimeEvents: SharedFlow<RealtimeEvent> = _realtimeEvents.asSharedFlow()
    
    // Pending requests indexed by request ID
    private val pending = ConcurrentHashMap<String, CompletableDeferred<ViewServerResponse>>()
    
    // ── Reactor Entry Points ──────────────────────────────────────
    
    /**
     * Handle a ViewServer protocol command from the reactor.
     * Returns response or null for async (realtime) responses.
     */
    suspend fun handle(command: ViewServerCommand): ViewServerResponse {
        return when (command) {
            is ViewServerCommand.Open -> handleOpen(command.db)
            is ViewServerCommand.AddDoc -> handleAddDoc(command.doc)
            is ViewServerCommand.Compact -> handleCompact()
            is ViewServerCommand.Info -> handleInfo()
            is ViewServerCommand.Get -> handleGet(command.id)
            is ViewServerCommand.ViewAll -> handleViewAll()
        }
    }
    
    /**
     * Handle a raw HTTP request, routing to appropriate handler.
     */
    suspend fun handleHttp(path: String, body: ByteArray): ViewServerResponse {
        val parts = path.split("/").filter { it.isNotEmpty() }
        return when {
            parts.isEmpty() -> ViewServerResponse.Error(404, "Not Found")
            parts[0] == "_all_docs" -> handleViewAll()
            parts[0].startsWith("_") -> handleInfo() // _info, _compact, etc.
            parts.size == 1 -> handleGet(parts[0])
            parts.size == 2 && parts[1] == "_view" -> handleViewAll()
            else -> ViewServerResponse.Error(404, "Not Found")
        }
    }
    
    // ── Command Handlers ────────────────────────────────────────
    
    private fun handleOpen(db: String): ViewServerResponse {
        // Already open - just confirm
        return ViewServerResponse.Ok(db, "ok")
    }
    
    private suspend fun handleAddDoc(doc: ConfixDoc): ViewServerResponse {
        // Extract _id from the ConfixDoc - ConfixDoc is Join<ConfixIndex, Series<Byte>>
        // For now, use a simpler approach - accept id separately or use a document builder
        // The doc parameter here should actually be a ConfixDocStoreEntry with _id
        val id = (doc as? Any)?.toString() ?: return ViewServerResponse.Error(400, "_id required")
        
        // For now, create a simple doc from the input - this is a stub
        // In production, we'd parse the ConfixDoc properly
        val entry = store.put(id, doc) 
        if (entry == null) {
            return ViewServerResponse.Error(409, "Conflict")
        }
        // Emit realtime event for CRDT sync
        _realtimeEvents.emit(RealtimeEvent.DocUpdated(id, entry.rev))
        return ViewServerResponse.Ok(id, entry.rev)
    }
    
    private fun handleCompact(): ViewServerResponse {
        // In-memory compaction - just rebuild indexes
        pipeline.index()
        return ViewServerResponse.Ok("_compact", "ok")
    }
    
    private fun handleInfo(): ViewServerResponse {
        // Access sequence through public API - get current sequence number
        val seqNum = 0L // store.seq is private, use 0 for now
        return ViewServerResponse.InfoResponse(store.size, seqNum)
    }
    
    private fun handleGet(id: String): ViewServerResponse {
        val entry = store[id]
        return ViewServerResponse.Doc(entry)
    }
    
    private fun handleViewAll(): ViewServerResponse {
        return ViewServerResponse.Docs(store.entries)
    }
    
    // ── RequestFactory-style RPC ────────────────────────────────
    
    /**
     * Invoke a method via RequestFactory-style RPC.
     */
    suspend fun <T> rpcInvoke(method: String, vararg args: Any?): T {
        val requestId = java.util.UUID.randomUUID().toString()
        val deferred = CompletableDeferred<ViewServerResponse>()
        pending[requestId] = deferred
        
        try {
            // Encode and send request
            val request = encodeRpcRequest(method, args.toList())
            handleHttp("/rpc/$method", request.toByteArray())
            
            // Wait for response
            val response = deferred.await()
            return when (response) {
                is ViewServerResponse.Doc -> response.doc?.doc as? T 
                    ?: throw IllegalStateException("No result")
                is ViewServerResponse.Ok -> Unit as T
                else -> throw IllegalStateException("RPC error: $response")
            }
        } finally {
            pending.remove(requestId)
        }
    }
    
    private fun encodeRpcRequest(method: String, args: List<Any?>): String {
        // Simple JSON-RPC style encoding (replace with actual RequestFactory if needed)
        return """{"method":"$method","args":${args.map { "\"$it\"" }}}"""
    }
    
    // ── CRDT Realtime Sync ───────────────────────────────────
    
    /**
     * Subscribe to realtime changes.
     */
    fun subscribe(callback: suspend (RealtimeEvent) -> Unit) {
        // Use scope directly with a simple launch pattern
        GlobalScope.launch {
            realtimeEvents.collect { event ->
                callback(event)
            }
        }
    }
}

/**
 * Realtime event for CRDT sync.
 */
sealed class RealtimeEvent {
    abstract val docId: String
    
    data class DocUpdated(override val docId: String, val rev: String) : RealtimeEvent()
    data class DocDeleted(override val docId: String) : RealtimeEvent()
    data class ViewUpdated(val viewName: String) : RealtimeEvent() {
        override val docId: String = "" // Required but not used for ViewUpdated
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// VIEW SERVER FACTORY
// ─────────────────────────────────────────────────────────────────────────────

object ViewServerFactory {
    /**
     * Create a ViewServer with the given pipeline.
     */
    fun create(pipeline: CouchPipeline, scope: CoroutineScope): ViewServer {
        return ViewServer(pipeline, scope)
    }
    
    /**
     * Create a ViewServer with default pipeline.
     */
    fun create(scope: CoroutineScope): ViewServer {
        val pipeline = CouchPipeline()
        return create(pipeline, scope)
    }
}