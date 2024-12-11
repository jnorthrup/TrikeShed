package borg.trikeshed.reactor

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import borg.trikeshed.io.ByteBuffer
import borg.trikeshed.lib.SelectableChannel

expect class Reactor(
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    numSelectorThreads: Int = 1
) {
    val reactorScope: CoroutineScope
    val isRunning: StateFlow<Boolean>
    
    suspend fun registerChannel(channel: SelectableChannel, ops: Int, reaction: AsyncReaction)
    suspend fun writeData(channel: SelectableChannel, data: ByteBuffer)
    suspend fun acquireBuffer(): ByteBuffer
    fun start()
    fun shutdown()
}
