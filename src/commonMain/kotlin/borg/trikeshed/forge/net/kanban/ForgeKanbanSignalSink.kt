package borg.trikeshed.forge.net.kanban

import kotlin.coroutines.CoroutineContext

/**
 * Sink for Forge Kanban planning signals.
 *
 * Implementations live in platform modules (JVM: CLI, desktop, server; JS: browser; etc.).
 * The commonMain conduit only depends on this interface.
 */
interface ForgeKanbanSignalSink : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<ForgeKanbanSignalSink>
    override val key: CoroutineContext.Key<*> get() = Key

    suspend fun accept(signal: ForgeKanbanSignal): Boolean
}
