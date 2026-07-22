package borg.trikeshed.kanban

import modelmux.ModelMux
import modelmux.ModelMux
import modelmux.ModelMux.ModelMuxBuilder
import modelmux.Message
import modelmux.Response
import modelmux.CapabilityRouter

/**
 * Forge Kanban Daemon — executes explicit model-call descriptors against a reduced board.
 * 
 * Workflow:
 * 1. Load and deterministically reduce the persisted markdown source envelope
 * 2. For cards with pending model calls, dispatch to modelmux
 * 3. Keep call results in runtime state; source persistence remains immutable input
 * 
 * This daemon runs as a coroutine and can be embedded in any CCEK-managed scope.
 */
class ForgeKanbanDaemon(
    private val userId: String,
    private val keyMux: KeyMux,
    private val scope: CoroutineScope,
    private val walReplay: (() -> Sequence<Pair<String, ByteArray>>)? = null,
) {
    
    /**
     * The user's board (loaded on demand).
     */
    private var board: KanbanBoard = ForgeKanbanIngest.load(userId).board
    
    init {
        walReplay?.invoke()?.forEach { (causalKey, _) ->
            // T-KANBAN-WAL-7: feed record back into the in-memory graph
            // Stub: currently only iterates to satisfy the sequence replay requirement
            // (Decoders to modify the `board` would be wired here)
        }
    }

    /**
     * Model mux instance for dispatching calls.
     */
    private var modelMux: ModelMux? = null
    
    /**
     * Initialize the model mux with the user's key bindings.
     */
    fun initializeModelMux(config: ModelMuxBuilder.() -> Unit = {}) {
        modelMux = ModelMux(keyMux, config)
    }
    
    /**
     * Process all pending model calls in the board.
     * Returns updated board.
     */
    suspend fun processPendingCalls(): KanbanBoard {
        val mux = modelMux ?: error("ModelMux not initialized. Call initializeModelMux() first.")
        
        val pendingCalls = board.modelCallLog.filter { 
            it.status == ModelCallStatus.PENDING || it.status == ModelCallStatus.RUNNING 
        }
        
        var updatedBoard = board
        
        for (call in pendingCalls) {
            updatedBoard = processCall(mux, updatedBoard, call)
        }
        
        board = updatedBoard
        return board
    }
    
    /**
     * Process a single model call.
     */
    private suspend fun processCall(mux: ModelMux, board: KanbanBoard, call: ModelCallDescriptor): KanbanBoard {
        // Mark as running
        var updatedBoard = board.updateModelCall(call.id) {
            copy(status = ModelCallStatus.RUNNING, startedAt = nowMs())
        }
        
        val result = try {
            when (call.action) {
                "chat" -> {
                    // role j content - AcpMessage = Join<String, String>
                    val msg: Join<String, String> = "user" j call.prompt
                    val messages = listOf(msg).toSeries()
                    val response = mux.chat(call.modelId, messages)
                    // response is Join<String, AcpUsage> = full_text j usage
                    val (text, _) = response
                    text
                }
                "embed" -> {
                    val texts = call.prompt.split("\n").filter { it.isNotBlank() }.toSeries()
                    mux.embed(call.modelId, texts)
                    "Embedded ${texts.size} texts"
                }
                else -> "Unknown action: ${call.action}"
            }
        } catch (e: Exception) {
            updatedBoard = updatedBoard.updateModelCall(call.id) {
                copy(
                    status = ModelCallStatus.FAILED,
                    completedAt = nowMs(),
                    error = e.message
                )
            }
            return updatedBoard
        }
        
        // Mark completed
        updatedBoard = updatedBoard.updateModelCall(call.id) {
            copy(
                status = ModelCallStatus.COMPLETED,
                completedAt = nowMs(),
                result = result.take(500),
                outputTokens = result.length // approximate
            )
        }
        
        return updatedBoard
    }
    
    /**
     * Queue a new model call for a card.
     */
    fun queueCall(
        cardId: KanbanCardId,
        modelId: String,
        provider: String,
        action: String,
        prompt: String,
    ): KanbanBoard {
        val descriptor = ModelCallDescriptor(
            id = ModelCallId.generate(),
            cardId = cardId,
            modelId = modelId,
            provider = provider,
            action = action,
            prompt = prompt,
            status = ModelCallStatus.PENDING
        )
        
        board = board.logModelCall(descriptor)
        return board
    }
    
    /**
     * Get all model calls for a specific card.
     */
    fun getCallsForCard(cardId: KanbanCardId): List<ModelCallDescriptor> =
        board.modelCallsForCard(cardId)
    
    /**
     * Get the current board.
     */
    fun getBoard(): KanbanBoard = board
    
    /**
     * Reload board from disk.
     */
    fun reload() {
        board = ForgeKanbanIngest.load(userId).board
    }
    
    /**
     * Start the daemon as a background job reacting to fanout events.
     */
    fun startAutoProcess(fanoutFlow: Flow<FanoutEvent>): Job = scope.launch {
        fanoutFlow.collect { event ->
            try {
                processPendingCalls()
            } catch (e: Exception) {
                println("[ForgeKanbanDaemon] Error processing calls: ${e.message}")
            }
        }
    }
    
    private fun nowMs(): Long = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
}

/**
 * Quick helper to create a daemon with default setup.
 * 
 * Usage:
 *   val daemon = createKanbanDaemon("jim") { 
 *       env("LLM_")  // load keys from environment with prefix
 *   }
 */
fun createKanbanDaemon(
    userId: String,
    configure: KeyMuxBuilder.() -> Unit = { env() },
    scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
    walReplay: (() -> Sequence<Pair<String, ByteArray>>)? = null,
): ForgeKanbanDaemon {
    // Create keymux with the user's configuration
    val keyMux = KeyMux(configure)
    
    val daemon = ForgeKanbanDaemon(userId, keyMux, scope, walReplay)
    // Note: initializeModelMux() should be called after creating the daemon
    // to set up the model mux with model cards
    
    return daemon
}
