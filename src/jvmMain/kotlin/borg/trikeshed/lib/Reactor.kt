@file:Suppress("UNCHECKED_CAST")

package borg.trikeshed.lib

import borg.trikeshed.lib.UnaryAsyncReaction.*
import borg.trikeshed.lib.UnaryAsyncReaction.Companion.OP_ACCEPT
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.SelectableChannel
import java.util.concurrent.*
import kotlin.math.max
import java.nio.channels.SelectionKey as SelectionKey
import java.nio.channels.Selector as Selector
import java.nio.channels.ServerSocketChannel as ServerSocketChannel

private class SelectorThread(
    val selector: Selector,
    val scope: CoroutineScope,
    val taskChannel: Channel<suspend () -> Unit>,
    val writeChannel: Channel<Join<SelectableChannel, ByteBuffer>>
)

private class BufferPool(private val bufferSize: Int = 16384) {
    private val pool = Channel<ByteBuffer>(Channel.UNLIMITED)
    
    suspend fun acquire(): ByteBuffer = pool.tryReceive().getOrNull() ?: ByteBuffer.allocateDirect(bufferSize)
    
    suspend fun release(buffer: ByteBuffer) {
        buffer.clear()
        pool.trySend(buffer)
    }
}

class Reactor(
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    val serverChannel: ServerSocketChannel? = ServerSocketChannel.open(),
    private val numSelectorThreads: Int = max(1, Runtime.getRuntime().availableProcessors() - 1),
    val scope: CoroutineScope = CoroutineScope(dispatcher + SupervisorJob())
) {
    private val selectorThreads = List(numSelectorThreads) {
        SelectorThread(
            Selector.open(),
            scope,
            Channel(Channel.UNLIMITED),
            Channel(Channel.UNLIMITED)
        )
    }
    
    private val workerPool = Executors.newFixedThreadPool(
        Runtime.getRuntime().availableProcessors() * 2
    ).asCoroutineDispatcher()
    
    private val bufferPool = BufferPool()
    private var nextSelectorIndex = 0
    
    @Volatile 
    private var running = true

    private fun getNextSelector(): SelectorThread {
        synchronized(this) {
            val selector = selectorThreads[nextSelectorIndex]
            nextSelectorIndex = (nextSelectorIndex + 1) % selectorThreads.size
            return selector
        }
    }

    suspend fun registerChannel(channel: SelectableChannel, ops: Int, reaction: UnaryAsyncReaction) {
        val selectorThread = getNextSelector()
        selectorThread.taskChannel.send {
            try {
                channel.configureBlocking(false)
                channel.register(selectorThread.selector, ops).attach(reaction)
            } catch (e: ClosedChannelException) {
                // Handle or log channel closure
            }
        }
        selectorThread.selector.wakeup()
    }

    fun start() {
        serverChannel?.apply {
            configureBlocking(false)
            // Register accept on first selector thread
            selectorThreads[0].selector.let { selector ->
                register(selector, OP_ACCEPT)
            }
        }

        selectorThreads.forEach { selectorThread ->
            scope.launch(workerPool) {
                runSelectorLoop(selectorThread)
            }
        }
    }

    private suspend fun runSelectorLoop(selectorThread: SelectorThread) {
        with(selectorThread) {
            while (running) {
                // Process pending tasks
                while (!taskChannel.isEmpty) {
                    taskChannel.receive().invoke()
                }

                // Process write queue
                processWriteQueue(selectorThread)

                try {
                    if (selector.select() > 0) {
                        val iterator = selector.selectedKeys().iterator()
                        while (iterator.hasNext()) {
                            val key = iterator.next()
                            iterator.remove()

                            if (key.isValid) {
                                processKey(key, selectorThread)
                            }
                        }
                    }
                } catch (e: IOException) {
                    // Handle selector errors
                }
            }
        }
    }

    private suspend fun processKey(key: SelectionKey, selectorThread: SelectorThread) {
        withContext(workerPool) {
            (key.attachment() as? UnaryAsyncReaction)?.invoke(key)?.let { (ops, reaction) ->
                selectorThread.taskChannel.send {
                    if (key.isValid) {
                        key.interestOps(ops)
                        key.attach(reaction)
                    }
                }
                selectorThread.selector.wakeup()
            } ?: key.cancel()
        }
    }

    private suspend fun processWriteQueue(selectorThread: SelectorThread) {
        while (!selectorThread.writeChannel.isEmpty) {
            val (channel, buffer) = selectorThread.writeChannel.receive()
            try {
                (channel as java.nio.channels.WritableByteChannel).write(buffer)
                if (!buffer.hasRemaining()) {
                    bufferPool.release(buffer)
                } else {
                    selectorThread.writeChannel.send(channel j buffer)
                }
            } catch (e: IOException) {
                bufferPool.release(buffer)
                // Handle write error
            }
        }
    }

    fun shutdown() {
        running = false
        selectorThreads.forEach { 
            it.selector.wakeup()
            it.scope.cancel()
        }
        scope.cancel()
        workerPool.close()
    }

    suspend fun writeData(channel: SelectableChannel, data: ByteBuffer) {
        val selectorThread = selectorThreads.find { 
            it.selector.keys().any { it.channel() == channel }
        } ?: return
        
        selectorThread.writeChannel.send(channel j data)
        selectorThread.selector.wakeup()
    }

    suspend fun acquireBuffer(): ByteBuffer = bufferPool.acquire()

    // Bridge method to support old-style enqueue with new AsyncReaction
    suspend fun enqueueReaction(channel: SelectableChannel, op: Int, reaction: UnaryAsyncReaction) {
        registerChannel(channel, op, reaction)
    }
}
