package borg.trikeshed.forge.server

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Event bus for pushing Kanban updates to connected clients over WebSockets.
 */
object KanbanPushBus {
    private val _patches = MutableSharedFlow<String>(extraBufferCapacity = 64)

    val patches: SharedFlow<String> = _patches.asSharedFlow()

    fun publish(patch: String) {
        _patches.tryEmit(patch)
    }
}
