package borg.trikeshed.reactor

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.channels.*
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import borg.trikeshed.io.ByteBuffer
import borg.trikeshed.lib.SelectableChannel
import borg.trikeshed.lib.AsyncReaction
import kotlin.math.max

actual class Reactor(
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val numSelectorThreads: Int = max(1, Runtime.getRuntime().availableProcessors() - 1)
) {
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        println("Unhandled exception in Reactor: ${throwable.localizedMessage}")
        throwable.printStackTrace()
    }

    actual val reactorScope: CoroutineScope = CoroutineScope(dispatcher + SupervisorJob() + exceptionHandler)
    actual val isRunning: StateFlow<Boolean> = MutableStateFlow(true)
    
    private val selectorThreads = List(numSelectorThreads) {
        SelectorThread(
            JvmSelector(Selector.open()),
            MutableSharedFlow(extraBufferCapacity = Channel.UNLIMITED),
            MutableSharedFlow(extraBufferCapacity = Channel.UNLIMITED)
        )
    }

    private val bufferPool = BufferPool()
    private var nextSelectorIndex = 0

    private fun getNextSelector(): SelectorThread {
        synchronized(this) {
            val selector = selectorThreads[nextSelectorIndex]
            nextSelectorIndex = (nextSelectorIndex + 1) % selectorThreads.size
            return selector
        }
    }

    actual suspend fun registerChannel(channel: SelectableChannel, ops: Int, reaction: AsyncReaction) {
        val selectorThread = getNextSelector()
        selectorThread.taskFlow.emit {
            selectorThread.selector.register(channel, ops, reaction)
        }
        selectorThread.selector.wakeup()
    }

    actual suspend fun writeData(channel: SelectableChannel, data: ByteBuffer) {
        val selectorThread = selectorThreads.first { selector ->
            selector.selector.selectedKeys().any { it.channel() == channel }
        }
        selectorThread.writeFlow.emit(channel to data)
        selectorThread.selector.wakeup()
    }

    actual suspend fun acquireBuffer(): ByteBuffer = bufferPool.acquire()

    actual fun start() {
        selectorThreads.forEach { selectorThread ->
            reactorScope.launch {
                runSelectorLoop(selectorThread)
            }
        }
    }

    actual fun shutdown() {
        (isRunning as MutableStateFlow).value = false
        reactorScope.cancel()
        selectorThreads.forEach { 
            it.selector.close()
        }
    }

    private suspend fun runSelectorLoop(selectorThread: SelectorThread) {
        coroutineScope {
            launch {
                selectorThread.taskFlow.collect { task ->
                    try {
                        task()
                    } catch (e: Exception) {
                        println("Error processing task: ${e.message}")
                    }
                }
            }

            while (isRunning.value) {
                try {
                    if (selectorThread.selector.select() > 0) {
                        val selectedKeys = selectorThread.selector.selectedKeys()
                        selectedKeys.forEach { key ->
                            processKey(key)
                        }
                    }
                } catch (e: Exception) {
                    println("Selector error: ${e.message}")
                }
            }
        }
    }

    private suspend fun processKey(key: SelectionKey) {
        withContext(Dispatchers.Default) {
            (key.attachment() as? AsyncReaction)?.let { reaction ->
                if (key.isValid) {
                    key.interestOps(reaction.first)
                    key.attach(reaction.second)
                }
            } ?: key.cancel()
        }
    }
}
