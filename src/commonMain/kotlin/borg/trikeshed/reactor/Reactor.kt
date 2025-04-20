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

    // Rest of the Reactor implementation remains the same...

    // Kept the BufferPoolImpl as it was
    private class BufferPoolImpl(private val bufferSize: Int = 16384) {
        private val pool = mutableListOf<ByteBuffer>()
        private val mutex = Mutex()

        suspend fun acquireBuffer(): ByteBuffer = mutex.withLock {
            pool.removeLastOrNull() ?: ByteBufferFactory.allocateDirect(bufferSize)  // Use the refactored ByteBufferFactory
        }

        suspend fun releaseBuffer(buffer: ByteBuffer) = mutex.withLock {
            buffer.clear()
            pool.add(buffer)
        }
    }
}
