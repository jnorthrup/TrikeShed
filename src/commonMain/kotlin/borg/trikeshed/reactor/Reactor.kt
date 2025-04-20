package borg.trikeshed.reactor

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.max

class Reactor(
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val numSelectorThreads: Int = max(1, 4)
) {
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        println("Unhandled exception in Reactor: ${throwable.message}")
        throwable.printStackTrace()
    }

    private val reactorScope = CoroutineScope(dispatcher + SupervisorJob() + exceptionHandler)
    private val isRunning = MutableStateFlow(true)

    private lateinit var selectorThreads: List<SelectorThread>
    private var nextSelectorIndex = 0

    private val bufferPool = BufferPoolImpl()

    init {
        reactorScope.launch {
            selectorThreads = List(numSelectorThreads) {
                SelectorThread(
                    createSelectorInterface(),
                    MutableSharedFlow(extraBufferCapacity = Channel.UNLIMITED),
                    MutableSharedFlow(extraBufferCapacity = Channel.UNLIMITED)
                )
            }
        }
    }

    // Register a channel with a specific operation (interest and reaction)
    fun registerChannel(channel: SelectableChannel, operation: Operation) {
        val selectorThread = selectorThreads[nextSelectorIndex]
        nextSelectorIndex = (nextSelectorIndex + 1) % numSelectorThreads
        reactorScope.launch {
            selectorThread.registerChannel(channel, operation.interest, operation.action)
        }
    }

    // Start the reactor, launching event loops for each selector thread
    fun start() {
        selectorThreads.forEach { thread ->
            reactorScope.launch {
                thread.runEventLoop(isRunning)
            }
        }
    }

    // Shutdown the reactor, stopping all selector threads and cleaning up resources
    fun shutdown() {
        isRunning.value = false
        reactorScope.cancel()
        reactorScope.launch {
            selectorThreads.forEach { thread ->
                thread.selector.close()
            }
        }
    }

    // Check if the reactor is active
    val isActive: Boolean get() = isRunning.value
    
    // Property to hold the server channel if needed
    var serverChannel: ServerChannel? = null
}

// SelectorThread class for handling selector operations
private class SelectorThread(
    val selector: SelectorInterface,
    private val channelRegistrations: MutableSharedFlow<Triple<SelectableChannel, Int, () -> AsyncReaction?>>,
    private val reactions: MutableSharedFlow<Pair<SelectionKey, AsyncReaction>>
) {
    private val keyReactions = mutableMapOf<SelectionKey, () -> AsyncReaction?>()

    suspend fun registerChannel(channel: SelectableChannel, interest: Int, reaction: () -> AsyncReaction?) {
        channelRegistrations.emit(Triple(channel, interest, reaction))
    }

    suspend fun runEventLoop(isRunning: MutableStateFlow<Boolean>) {
        while (isRunning.value) {
            // Handle new channel registrations
            channelRegistrations.tryReceive().getOrNull()?.let { (channel, interest, reaction) ->
                val key = selector.register(channel, interest, null)
                keyReactions[key] = reaction
            }

            // Select and process ready keys
            if (selector.select() > 0) {
                val readyKeys = selector.selectedKeys()
                for (key in readyKeys) {
                    if (key.isValid) {
                        keyReactions[key]?.let { reaction ->
                            reaction()?.let { asyncReaction ->
                                reactions.emit(key to asyncReaction)
                            }
                        }
                    }
                }
            }
        }
    }
}
