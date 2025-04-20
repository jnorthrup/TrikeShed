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
    private val selectorEvents = Channel<Set<SelectionKey>>(capacity = Channel.UNLIMITED)

    suspend fun registerChannel(channel: SelectableChannel, interest: Int, reaction: () -> AsyncReaction?) {
        channelRegistrations.emit(Triple(channel, interest, reaction))
    }

    suspend fun runEventLoop(isRunning: MutableStateFlow<Boolean>) = coroutineScope {
        // Launch a separate coroutine to handle blocking selector.select() on IO dispatcher
        launch(Dispatchers.IO) {
            while (isRunning.value && isActive) {
                try {
                    if (selector.select() > 0) {
                        val readyKeys = selector.selectedKeys()
                        selectorEvents.send(readyKeys)
                    }
                } catch (e: Exception) {
                    println("Error in selector loop: ${e.message}")
                    isRunning.value = false // Stop the loop on error
                }
            }
        }

        // Main event loop using select for non-blocking processing
        while (isRunning.value && isActive) {
            kotlinx.coroutines.selects.select<Unit> {
                // Handle channel registrations
                channelRegistrations.onReceiveCatching { result ->
                    result.getOrNull()?.let { (channel, interest, reaction) ->
                        try {
                            val key = selector.register(channel, interest, null)
                            keyReactions[key] = reaction
                        } catch (e: Exception) {
                            println("Error registering channel: ${e.message}")
                            try { channel.close() } catch (_: Exception) {}
                        }
                    }
                }

                // Handle selector events
                selectorEvents.onReceive { readyKeys ->
                    val iterator = readyKeys.iterator()
                    while (iterator.hasNext()) {
                        val key = iterator.next()
                        iterator.remove() // Remove from selected set

                        if (key.isValid) {
                            keyReactions[key]?.let { reaction ->
                                try {
                                    reaction()?.let { asyncReaction ->
                                        reactions.emit(key to asyncReaction)
                                    } ?: run {
                                        keyReactions.remove(key)
                                        key.cancel()
                                    }
                                } catch (e: Exception) {
                                    println("Error during reaction for key $key: ${e.message}")
                                    keyReactions.remove(key)
                                    key.cancel()
                                    try { key.channel().close() } catch (_: Exception) {}
                                }
                            }
                        } else {
                            keyReactions.remove(key)
                        }
                    }
                }
            }
        }
    }
}
