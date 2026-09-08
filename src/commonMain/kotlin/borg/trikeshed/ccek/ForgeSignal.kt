package borg.trikeshed.ccek

import borg.trikeshed.forge.ForgeBlockKind

enum class ProjectionKind {
    DOCUMENT,
    BOARD,
    MARKDOWN;

    companion object {
        val ALL: Set<ProjectionKind> = entries.toSet()
    }
}

sealed class ForgeSignal {
    data class AppendBlock(
        val kind: ForgeBlockKind,
        val text: String,
        val properties: Map<String, String> = emptyMap(),
    ) : ForgeSignal()

    data class UpdateText(val blockId: String, val text: String) : ForgeSignal()
    data class DeleteBlock(val blockId: String) : ForgeSignal()
    data class MoveCard(val cardId: String, val toColumnId: String) : ForgeSignal()
    data class Continue(val cardId: String) : ForgeSignal()
    data class Repeat(val cardId: String, val edgeId: String) : ForgeSignal()
    data class Abort(val cardId: String, val reason: String) : ForgeSignal()
    data class Fork(val cardId: String, val targetLane: String) : ForgeSignal()
    data class Join(val cardId: String, val group: String, val requiredBranches: Int) : ForgeSignal()
    data class Vote(val cardId: String, val verdict: String) : ForgeSignal()
}

sealed class ForgeProjection {
    data class DocumentChanged(val document: borg.trikeshed.forge.ForgeDocument) : ForgeProjection()
    data class BoardChanged(val board: borg.trikeshed.kanban.KanbanBoard) : ForgeProjection()
    data class MarkdownChanged(val markdown: String) : ForgeProjection()
    data class Error(val agentName: String, val cause: Throwable) : ForgeProjection()
}

sealed class AgentStatusEvent {
    abstract val agentName: String

    data class Started(
        override val agentName: String,
        val signal: ForgeSignal,
    ) : AgentStatusEvent()

    data class Completed(
        override val agentName: String,
    ) : AgentStatusEvent()

    data class Failed(
        override val agentName: String,
        val signal: ForgeSignal,
        val cause: Throwable,
    ) : AgentStatusEvent()
}
