package borg.trikeshed.forge.notion

/**
 * Cut #3 — deterministic stub taxonomy creator.
 *
 * This is the "AI taxonomical creator" placeholder. It takes a topic string
 * and produces a [CursorNotionState] containing a taxonomy structure
 * (heading + sub-headings), deterministically. No LLM, no randomness —
 * the decomposition is a fixed pattern so tests are reproducible.
 *
 * When a real AI agent is added later, it replaces [decompose] internals
 * but keeps the same output contract: a [CursorNotionState] that feeds
 * into [NotionKanbanBridge.projectTaxonomyEvents].
 *
 * Endgame chain position (all links HARD after this cut):
 *   TaxonomyCreator.createFromTopic  (THIS CUT — deterministic stub)
 *     → CursorDrivenNotion.appendBlock  (HARD — real editor)
 *       → NotionKanbanBridge.projectTaxonomyEvents  (cut #2 — HARD bridge)
 *         → KanbanEvent.TaxonomyNodeCreated  (cut #1 — contract)
 *           → KanbanFSM.reduce → KanbanState  (cut #1 — tested)
 */
object TaxonomyCreator {

    /**
     * Create a taxonomy page from a topic string. The page contains:
     * - A HEADING_1 with the topic name.
     * - One HEADING_2 child per decomposition facet.
     * - One TEXT block under each facet with a placeholder description.
     *
     * The decomposition is deterministic and topic-agnostic — it produces
     * a fixed set of facets that any taxonomy node would have. This is the
     * stub that a real AI agent would replace with topic-specific facets.
     */
    fun createFromTopic(
        topic: String,
        actorId: String = "taxonomy-creator",
    ): CursorNotionState {
        var state = CursorDrivenNotion.empty(title = topic, actorId = actorId)

        // Main taxonomy heading — this is the root taxonomy node.
        state = CursorDrivenNotion.appendBlock(
            state = state,
            parentId = state.rootPageId,
            kind = NotionBlockKind.HEADING_1,
            text = topic,
            actorId = actorId,
        )

        // Deterministic decomposition facets — each becomes a taxonomy sub-node.
        val facets = decompose(topic)
        for (facet in facets) {
            state = CursorDrivenNotion.appendBlock(
                state = state,
                parentId = state.rootPageId,
                kind = NotionBlockKind.HEADING_2,
                text = facet,
                actorId = actorId,
            )
            state = CursorDrivenNotion.appendBlock(
                state = state,
                parentId = state.rootPageId,
                kind = NotionBlockKind.TEXT,
                text = "Description for $facet under $topic.",
                actorId = actorId,
            )
        }

        return state
    }

    /**
     * Deterministic decomposition of a topic into taxonomy facets.
     *
     * This is the stub — a real AI agent would analyze the topic and
     * produce specific, relevant facets. The stub produces a fixed set
     * that proves the spine works end-to-end.
     *
     * Override this function (or replace the object) when wiring a real
     * taxonomy generator.
     */
    fun decompose(topic: String): List<String> = listOf(
        "$topic — Definition",
        "$topic — Properties",
        "$topic — Relationships",
        "$topic — Examples",
    )
}
