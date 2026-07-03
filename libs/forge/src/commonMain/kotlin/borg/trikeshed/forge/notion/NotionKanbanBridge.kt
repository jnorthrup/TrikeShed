@file:Suppress("unused")

package borg.trikeshed.forge.notion

import borg.trikeshed.userspace.reactor.KanbanEvent

/**
 * Cut #2 — Notion → Kanban emission bridge.
 *
 * The Notion clone ([CursorDrivenNotion]) is a pure, hermetic block-tree
 * editor: it records mutations in an internal [NotionMutation] history but
 * never emits to an external consumer. This bridge is the narrowest emission
 * path that lifts taxonomy-creating Notion mutations into
 * [KanbanEvent.TaxonomyNodeCreated] events, giving the kanban FSM a
 * consumption source for the Notion clone's creation surface.
 *
 * Design: the bridge is a pure projection, not a side-effecting sink. Callers
 * collect events via [projectTaxonomyEvents] (pull the whole history) or
 * [emitNewTaxonomyEvents] (push a SharedFlow). The Notion clone itself is
 * untouched — no fields added, no callbacks injected — so its existing tests
 * and purity are preserved.
 *
 * Endgame chain position:
 *   AI taxonomical creator (FUTURE)
 *     → CursorDrivenNotion.appendBlock / insertBlockAfter (HARD, real)
 *       → NotionKanbanBridge.projectTaxonomyEvents (THIS CUT)
 *         → KanbanEvent.TaxonomyNodeCreated (cut #1, contract)
 *           → KanbanFSM.reduce() updates KanbanState (cut #1, HARD, tested)
 */
object NotionKanbanBridge {

    /**
     * Mutation operations in Notion history that create a new taxonomy node.
     * Text updates, cursor moves, indent/outdent, and deletes are excluded —
     * only operations that bring a new block into existence qualify.
     */
    private val TAXONOMY_CREATING_OPS: Set<String> = setOf(
        "append-block",
        "insert-block-after",
    )

    /**
     * Project the Notion clone's mutation history into a list of
     * [KanbanEvent.TaxonomyNodeCreated] events, one per taxonomy-creating
     * mutation. Non-creating mutations are skipped. Block kind and label are
     * recovered from the mutation payload and the state's block store.
     */
    fun projectTaxonomyEvents(state: CursorNotionState): List<KanbanEvent.TaxonomyNodeCreated> =
        state.history
            .filter { it.operation in TAXONOMY_CREATING_OPS }
            .mapNotNull { mutation ->
                mutation.blockId?.let { id -> state.block(id) }?.let { block ->
                    KanbanEvent.TaxonomyNodeCreated(
                        nodeId = block.id.value,
                        kind = block.kind.name.lowercase(),
                        label = block.text.ifBlank { block.kind.name },
                        parentId = block.parentId?.value,
                        timestampMs = mutation.timestamp,
                    )
                }
            }
}
