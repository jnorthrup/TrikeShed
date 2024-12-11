package borg.trikeshed.lib

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

expect class CommonReactor {
    val scope: CoroutineScope
    
    suspend fun registerChannel(channel: SelectableChannel, ops: Int, reaction: AsyncReaction)
    suspend fun start()
    suspend fun shutdown()
    
    companion object {
        fun create(): CommonReactor
    }
}
