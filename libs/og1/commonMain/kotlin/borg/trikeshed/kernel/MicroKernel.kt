@file:Suppress("NonAsciiCharacters", "UNCHECKED_CAST", "NAME_SHADOWING")

package borg.trikeshed.kernel

import borg.trikeshed.cursor.*
import borg.trikeshed.lib.*
import java.lang.ref.WeakReference
import kotlin.coroutines.*

// ─────────────────────────────────────────────────────────────────────────────
// BOUNDED BUFFER — Finite-capacity buffer with auto-eviction
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Finite-capacity buffer. Auto-evicts oldest entries when full.
 * 
 * Ported from onegood1/kernel/bounded.py BoundedBuffer
 */
class BoundedBuffer<T>(
    val maxlen: Int,
) {
    private val deque = ArrayDeque<T>(maxlen)
    
    /**
     * Add item. Returns evicted item if at capacity.
     */
    fun add(item: T): T? {
        var evicted: T? = null
        if (deque.size >= maxlen) {
            evicted = deque.removeFirst()
            (evicted as? AutoCloseable)?.close()
        }
        deque.addLast(item)
        return evicted
    }
    
    /**
     * Get item at index without removing.
     */
    fun get(index: Int): T? {
        return if (index < deque.size) deque.elementAt(index) else null
    }
    
    /**
     * Get all items as Series.
     */
    fun asSeries(): Series<T> = deque.size j { deque.elementAt(it) }
    
    /**
     * Clear all items.
     */
    fun clear() {
        for (item in deque) {
            (item as? AutoCloseable)?.close()
        }
        deque.clear()
    }
    
    /**
     * Peek at the oldest item without removing.
     */
    fun peekFirst(): T? = deque.firstOrNull()
    
    /**
     * Peek at the newest item without removing.
     */
    fun peekLast(): T? = deque.lastOrNull()
    
    /**
     * Remove and return the oldest item.
     */
    fun removeFirst(): T? = if (deque.isNotEmpty()) deque.removeFirst() else null
    
    /**
     * Remove and return the newest item.
     */
    fun removeLast(): T? = if (deque.isNotEmpty()) deque.removeLast() else null
    
    val size: Int get() = deque.size
    val isEmpty: Boolean get() = deque.isEmpty()
    val isFull: Boolean get() = deque.size >= maxlen
    
    operator fun iterator(): Iterator<T> = deque.iterator()
}

// ─────────────────────────────────────────────────────────────────────────────
// WEAK REGISTRY — Non-owning object registry
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Non-owning registry with weak references. Auto-removes dead references.
 * 
 * Ported from onegood1/kernel/bounded.py WeakRegistry
 */
class WeakRegistry<V> {
    private val refs = mutableMapOf<String, WeakReference<V>>()
    
    /**
     * Register object with weak reference.
     * Returns a cleanup callback.
     */
    fun register(name: String, value: V): () -> Unit {
        val ref = WeakReference(value)
        refs[name] = ref
        return { refs.remove(name) }
    }
    
    /**
     * Get object if alive, else null.
     */
    fun get(name: String): V? = refs[name]?.get()
    
    /**
     * Remove a registration.
     */
    fun unregister(name: String) {
        refs.remove(name)
    }
    
    /**
     * Get all alive entries.
     */
    fun aliveEntries(): Map<String, V> {
        @Suppress("UNCHECKED_CAST")
        return refs.mapNotNull { (k, v) -> v.get()?.let { k to it } }.toMap() as Map<String, V>
    }
    
    val size: Int get() = refs.size
    
    /**
     * Clean up dead references.
     */
    fun cleanup() {
        refs.entries.removeIf { it.value.get() == null }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// MICRO KERNEL BUFFERS — Kernel-level bounded state
// ─────────────────────────────────────────────────────────────────────────────

/**
 * All state bounded. No unbounded growth.
 * 
 * Ported from onegood1/kernel/memory.py MicroKernelBuffers
 */
class MicroKernel(
    // Fixed limits
    val maxThoughts: Int = 200,
    val maxToolCalls: Int = 1000,
    val maxMetrics: Int = 10000,
    val maxEditorActions: Int = 1000,
) {
    // Bounded buffers
    val thoughts = BoundedBuffer<Thought>(maxThoughts)
    val toolCalls = BoundedBuffer<ToolCall>(maxToolCalls)
    val sessions = WeakRegistry<Any>()
    val metrics = BoundedBuffer<Metric>(maxMetrics)
    val editorActionQueue = ArrayDeque<EditorAction>(maxEditorActions)
    
    // Lock for editor action queue (for thread safety)
    private val editorActionLock = Any()
    
    /**
     * Record an agent thought (bounded).
     */
    fun recordThought(thought: String) {
        val entry = Thought(
            ts = System.currentTimeMillis(),
            thought = thought,
        )
        thoughts.add(entry)
    }
    
    /**
     * Record a tool call (bounded).
     */
    fun recordToolCall(tool: String, args: String, resultType: String) {
        val entry = ToolCall(
            ts = System.currentTimeMillis(),
            tool = tool,
            args = args.take(200),
            resultType = resultType,
        )
        toolCalls.add(entry)
    }
    
    /**
     * Register a non-owning session.
     */
    fun registerSession(sessionId: String, sessionObj: Any): () -> Unit {
        return sessions.register(sessionId, sessionObj)
    }
    
    /**
     * Enqueue an editor action.
     */
    fun enqueueEditorAction(action: EditorAction): EditorActionResult {
        if (action.actionId == null) {
            return EditorActionResult(error = "editor_action missing action_id")
        }
        synchronized(editorActionLock) {
            if (editorActionQueue.size >= maxEditorActions) {
                editorActionQueue.removeFirst()
            }
            editorActionQueue.addLast(action)
        }
        return EditorActionResult(ok = true, queued = true)
    }
    
    /**
     * Peek at queued editor actions without consuming.
     */
    fun peekEditorActions(): List<EditorAction> {
        return synchronized(editorActionLock) {
            editorActionQueue.toList()
        }
    }
    
    /**
     * Consume and return queued editor actions.
     */
    fun fetchEditorActions(): List<EditorAction> {
        return synchronized(editorActionLock) {
            val actions = editorActionQueue.toList()
            editorActionQueue.clear()
            actions
        }
    }
    
    /**
     * Get buffer utilization stats.
     */
    fun getStats(): KernelStats {
        return KernelStats(
            thoughts = BufferStats(count = thoughts.size, max = maxThoughts),
            toolCalls = BufferStats(count = toolCalls.size, max = maxToolCalls),
            editorActions = BufferStats(count = editorActionQueue.size, max = maxEditorActions),
            activeSessions = sessions.size,
            metrics = BufferStats(count = metrics.size, max = maxMetrics),
        )
    }
    
    /**
     * Clear all buffers.
     */
    fun clear() {
        thoughts.clear()
        toolCalls.clear()
        metrics.clear()
        synchronized(editorActionLock) {
            editorActionQueue.clear()
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// DATA TYPES
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Agent thought entry.
 */
data class Thought(
    val ts: Long,
    val thought: String,
)

/**
 * Tool call entry.
 */
data class ToolCall(
    val ts: Long,
    val tool: String,
    val args: String,
    val resultType: String,
)

/**
 * Metric entry.
 */
data class Metric(
    val ts: Long,
    val name: String,
    val value: Double,
    val tags: Map<String, String> = emptyMap(),
)

/**
 * Editor action for IDE plugin communication.
 */
data class EditorAction(
    val actionId: String,
    val actionType: String? = null,
    val filePath: String? = null,
    val content: String? = null,
    val cursorPosition: Int? = null,
)

/**
 * Result of editor action enqueue.
 */
data class EditorActionResult(
    val ok: Boolean? = null,
    val queued: Boolean? = null,
    val error: String? = null,
)

/**
 * Buffer utilization stats.
 */
data class BufferStats(
    val count: Int,
    val max: Int,
)

/**
 * Overall kernel stats.
 */
data class KernelStats(
    val thoughts: BufferStats,
    val toolCalls: BufferStats,
    val editorActions: BufferStats,
    val activeSessions: Int,
    val metrics: BufferStats,
)

// ─────────────────────────────────────────────────────────────────────────────
// KERNEL SERVER — JSON-over-UNIX socket RPC API
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Kernel server for TrikeShed.
 * 
 * Maintains MicroKernel and exposes a simple JSON-over-UNIX socket RPC API.
 * Similar to oneogood1/kernel_server.py KernelServer.
 */
class KernelServer(
    val socketPath: String = "/tmp/trikeshed_kernel.sock",
) {
    val kernel = MicroKernel()
    
    // Models cache
    var modelsCache: List<String> = emptyList()
    
    // Start time
    val startTime: Long = System.currentTimeMillis()
    
    /**
     * Handle an RPC request.
     */
    suspend fun handleRpc(request: RpcRequest): RpcResponse {
        val method = request.method
        val params = request.params ?: emptyMap()
        
        return try {
            val result = when (method) {
                "record_thought" -> {
                    val thought = params["thought"] as? String ?: return error("missing thought")
                    kernel.recordThought(thought)
                    mapOf("ok" to true)
                }
                "record_tool_call" -> {
                    val tool = params["tool"] as? String ?: return error("missing tool")
                    val args = params["args"] as? String ?: ""
                    val resultType = params["resultType"] as? String ?: "Unknown"
                    kernel.recordToolCall(tool, args, resultType)
                    mapOf("ok" to true)
                }
                "enqueue_editor_action" -> {
                    val actionId = params["actionId"] as? String ?: return error("missing action_id")
                    val action = EditorAction(actionId = actionId)
                    kernel.enqueueEditorAction(action)
                    mapOf("ok" to true, "queued" to true)
                }
                "fetch_editor_actions" -> {
                    mapOf("actions" to kernel.fetchEditorActions())
                }
                "get_stats" -> {
                    kernel.getStats().toMap()
                }
                "clear" -> {
                    kernel.clear()
                    mapOf("ok" to true)
                }
                else -> return error("unknown method: $method")
            }
            RpcResponse(result = result)
        } catch (e: Exception) {
            RpcResponse(error = "${e.message}")
        }
    }
    
    private fun error(message: String): RpcResponse {
        return RpcResponse(error = message)
    }
    
    private fun KernelStats.toMap(): Map<String, Any> = mapOf(
        "thoughts" to mapOf("count" to thoughts.count, "max" to thoughts.max),
        "tool_calls" to mapOf("count" to toolCalls.count, "max" to toolCalls.max),
        "editor_actions" to mapOf("count" to editorActions.count, "max" to editorActions.max),
        "active_sessions" to activeSessions,
        "metrics" to mapOf("count" to metrics.count, "max" to metrics.max),
    )
}

/**
 * RPC request.
 */
data class RpcRequest(
    val method: String,
    val params: Map<String, Any?>? = null,
)

/**
 * RPC response.
 */
data class RpcResponse(
    val result: Any? = null,
    val error: String? = null,
)