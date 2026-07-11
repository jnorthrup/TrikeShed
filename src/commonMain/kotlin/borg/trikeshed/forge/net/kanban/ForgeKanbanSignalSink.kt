package borg.trikeshed.forge.net.kanban

import borg.trikeshed.ccek.KeyedService
import kotlin.coroutines.CoroutineContext

/**
 * Sink for Forge Kanban planning signals.
 *
 * Implementations live in platform modules (JVM: CLI, desktop, server; JS: browser; etc.).
 * The commonMain conduit only depends on this interface.
 */
interface ForgeKanbanSignalSink : KeyedService {
    companion object Key : CoroutineContext.Key<ForgeKanbanSignalSink>

    suspend fun accept(signal: ForgeKanbanSignal): Boolean
}