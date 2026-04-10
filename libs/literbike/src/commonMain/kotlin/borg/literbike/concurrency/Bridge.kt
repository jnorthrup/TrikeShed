package borg.literbike.concurrency

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*

/**
 * Bridge between CCEK and userspace NIO ecosystem
 *
 * This module provides integration between our CCEK context system
 * and the userspace kernel emulation (ENDGAME).
 */

/**
 * CCEK-aware userspace runtime wrapper
 */
class CcekRuntime(
    val context: CoroutineContextImpl
) {
    private val scope = kotlinx.coroutines.CoroutineScope(context + Dispatchers.Default)

    companion object {
        fun new(context: CoroutineContextImpl) = CcekRuntime(context)
    }

    /**
     * Launch a coroutine with CCEK context
     */
    fun launch(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit): Job {
        return scope.launch(block = block).asCoroutineJob()
    }

    /**
     * Run a blocking operation with CCEK context
     */
    fun <T> runBlocking(block: suspend kotlinx.coroutines.CoroutineScope.() -> T): T {
        return kotlinx.coroutines.runBlocking(context) { block() }
    }
}

/**
 * Create a channel bound to CCEK context
 */
fun <T : Any> ccekChannel(buffer: Int): Pair<CcekSender<T>, CcekReceiver<T>> {
    val (tx, rx) = channel<T>(buffer)
    return CcekSender(tx) to CcekReceiver(rx)
}

/**
 * CCEK Sender wrapper
 */
class CcekSender<T : Any>(
    private val sender: ChannelSender<T>
) {
    suspend fun send(value: T): Result<Unit> = sender.send(value)
}

/**
 * CCEK Receiver wrapper
 */
class CcekReceiver<T : Any>(
    private val receiver: ChannelReceiver<T>
) {
    suspend fun recv(): T? = receiver.recv()
}

/**
 * Extension: CoroutineContext helpers
 */
object CoroutineContextExt {
    /**
     * Create a channel from a context
     */
    fun <T : Any> CoroutineContextImpl.createChannel(bufferSize: Int): Pair<ChannelSender<T>, ChannelReceiver<T>> {
        return channel(bufferSize)
    }

    /**
     * Spawn a task with context
     */
    fun CoroutineContextImpl.spawnTask(
        scope: kotlinx.coroutines.CoroutineScope,
        block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit
    ): kotlinx.coroutines.Job {
        return scope.launch(this + Dispatchers.Default, block = block)
    }
}

/**
 * Helper to convert kotlinx.coroutines.Job to our Job interface
 */
private fun kotlinx.coroutines.Job.asCoroutineJob(): Job {
    return object : Job {
        override fun cancel() = this@asCoroutineJob.cancel()
        override fun isActive(): Boolean = this@asCoroutineJob.isActive
        override fun isCompleted(): Boolean = this@asCoroutineJob.isCompleted
        override fun isCancelled(): Boolean = this@asCoroutineJob.isCancelled
    }
}
