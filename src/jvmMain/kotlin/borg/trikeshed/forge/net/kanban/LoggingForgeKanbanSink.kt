package borg.trikeshed.forge.net.kanban

import borg.trikeshed.ccek.KeyedService
import kotlin.coroutines.CoroutineContext

/**
 * JVM logging sink for Forge Kanban signals.
 * Prints signals to stdout for debugging / observation.
 */
class LoggingForgeKanbanSink : ForgeKanbanSignalSink, KeyedService {

    companion object Key : CoroutineContext.Key<LoggingForgeKanbanSink>

    override val key: CoroutineContext.Key<*>
        get() = Key

    override suspend fun accept(signal: ForgeKanbanSignal): Boolean {
        println("[FORGE-KANBAN] ${signal::class.simpleName}")
        println("  idempotencyKey: ${signal.idempotencyKey}")
        println("  title: ${signal.title}")
        println("  body: ${signal.body}")
        println("  metadata: ${signal.metadata}")
        when (signal) {
            is ForgeKanbanSignal.NewIntent -> println("  workspace: ${signal.workspace}")
            is ForgeKanbanSignal.ProgressNote -> println("  taskId: ${signal.taskId}")
            is ForgeKanbanSignal.NeedsHuman -> println("  taskId: ${signal.taskId}, reason: ${signal.reason}")
            is ForgeKanbanSignal.Resolved -> println("  taskId: ${signal.taskId}, summary: ${signal.summary}")
        }
        println()
        return true
    }
}