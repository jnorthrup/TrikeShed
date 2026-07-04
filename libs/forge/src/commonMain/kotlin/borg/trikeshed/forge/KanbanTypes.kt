@file:Suppress("unused")
package borg.trikeshed.forge

/**
 * Pruned: core Kanban types have been internalized into the TrikeShed root
 * at borg.trikeshed.kanban. This file is pure typealiases so that all
 * existing forge-internal code (KanbanGraphOverlays, demos) continues to
 * compile without changes.
 *
 * Nothing new should be added here. Forge-specific Cascade/PatchBay
 * extensions live in KanbanForgeExtensions.kt.
 * Graph overlay extensions live in KanbanGraphOverlays.kt.
 */

typealias KanbanBoard    = borg.trikeshed.kanban.KanbanBoard
typealias KanbanBoardId  = borg.trikeshed.kanban.KanbanBoardId
typealias KanbanColumn   = borg.trikeshed.kanban.KanbanColumn
typealias KanbanColumnId = borg.trikeshed.kanban.KanbanColumnId
typealias KanbanCard     = borg.trikeshed.kanban.KanbanCard
typealias KanbanCardId   = borg.trikeshed.kanban.KanbanCardId
typealias CardPriority   = borg.trikeshed.kanban.CardPriority
typealias Swimlane       = borg.trikeshed.kanban.Swimlane
typealias SwimlaneId     = borg.trikeshed.kanban.SwimlaneId
