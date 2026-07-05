package borg.trikeshed.forge.notion

import borg.trikeshed.userspace.reactor.KanbanEvent
import borg.trikeshed.userspace.reactor.KanbanFSM
import borg.trikeshed.userspace.reactor.KanbanState

/**
 * Cut: Notion-specialization for software kanban.
 *
 * Maps software-kanban concepts (epic / story / task / sub-task, owner, status,
 * estimate, due date, blocker, parent) onto the existing [CursorDrivenNotion]
 * block kinds and the [NotionBlock.properties] map. Causal nodes from
 * `CausalGraphNodeIndex` map onto epic = root causal node (no parents) and
 * story = leaf causal node (has parents), one tier only, because Q4 picked the
 * simplest mapping.
 *
 * The profile is a pure projection. It does not mutate the existing notion
 * stack, the existing [NotionKanbanBridge], or the existing taxonomy creator.
 * It only emits a [CursorNotionState] plus a flat property-key vocabulary.
 *
 * Endgame chain position (no UI, no server, no recording):
 *   BlackboardSurface (root commonMain)
 *     -> CausalGraphNodeIndex (root commonMain)
 *       -> SoftwareKanbanNotionProfile.specialize (THIS CUT, libs/forge)
 *         -> CursorDrivenNotion state with HEADING_1 epic + BULLET stories
 *           -> NotionKanbanBridge.projectTaxonomyEvents (cut #2, HARD)
 *             -> KanbanEvent.TaxonomyNodeCreated (cut #1, contract)
 *               -> KanbanFSM.reduce -> KanbanState (cut #1, HARD)
 */
object SoftwareKanbanNotionProfile {

    /**
     * Property keys used to encode software-kanban fields on [NotionBlock.properties].
     * Flat by Q3 pick: name / status / owner / estimate / due / blocker / parent.
     */
    val PROPERTY_KEYS: List<String> = listOf(
        "kanban.status",
        "kanban.owner",
        "kanban.estimate",
        "kanban.due",
        "kanban.blocker",
        "kanban.parent",
    )

    /**
     * Specialize a generic topic page into software-kanban-shaped notion blocks.
     *
     * Mapping rules (Q1 lean + Q4 epic-root / story-leaf):
     * - One HEADING_1 epic block with the topic as title.
     * - One CALLOUT "definition" block for each story.
     * - One BULLET story block per story with software-kanban property keys.
     *
     * Causal mapping:
     * - epic = root causal node (parentNodeIds empty).
     * - story = leaf causal node (has parents).
     * - One story block per leaf.
     */
    fun specialize(
        epic: CausalNodeForProfile,
        stories: List<CausalNodeForProfile>,
        actorId: String = "software-kanban-profile",
    ): CursorNotionState {
        val state = CursorDrivenNotion.empty(title = epic.title, actorId = actorId)

        var current = CursorDrivenNotion.appendBlock(
            state = state,
            parentId = state.rootPageId,
            kind = NotionBlockKind.HEADING_1,
            text = epic.title,
            properties = epicPropertyMap(epic),
            actorId = actorId,
        )

        if (epic.summary.isNotBlank()) {
            current = CursorDrivenNotion.appendBlock(
                state = current,
                parentId = current.rootPageId,
                kind = NotionBlockKind.QUOTE,
                text = epic.summary,
                actorId = actorId,
            )
        }

        for (story in stories) {
            current = CursorDrivenNotion.appendBlock(
                state = current,
                parentId = current.rootPageId,
                kind = NotionBlockKind.BULLET,
                text = story.title,
                properties = storyPropertyMap(story, epic),
                actorId = actorId,
            )
        }

        return current
    }

    /** Flat property map for an epic block. */
    fun epicPropertyMap(epic: CausalNodeForProfile): Map<String, String> = basePropertyMap(
        status = epic.status,
        owner = epic.owner,
        estimate = epic.estimate,
        due = epic.due,
        blocker = epic.blocker,
        parent = epic.parent,
    )

    /** Flat property map for a story block, with the epic id pinned as parent. */
    fun storyPropertyMap(story: CausalNodeForProfile, epic: CausalNodeForProfile): Map<String, String> =
        basePropertyMap(
            status = story.status,
            owner = story.owner,
            estimate = story.estimate,
            due = story.due,
            blocker = story.blocker,
            parent = story.parent.ifBlank { epic.title },
        )

    private fun basePropertyMap(
        status: String,
        owner: String,
        estimate: String,
        due: String,
        blocker: String,
        parent: String,
    ): Map<String, String> = buildMap {
        if (status.isNotBlank()) put("kanban.status", status)
        if (owner.isNotBlank()) put("kanban.owner", owner)
        if (estimate.isNotBlank()) put("kanban.estimate", estimate)
        if (due.isNotBlank()) put("kanban.due", due)
        if (blocker.isNotBlank()) put("kanban.blocker", blocker)
        if (parent.isNotBlank()) put("kanban.parent", parent)
    }

    /**
     * Project the specialized notion state into kanban taxonomy events via the
     * existing [NotionKanbanBridge]. This keeps the one-way Q5 pick: surface
     * -> notion blocks -> kanban events. Notion never rebroadcasts into the
     * surface in this cut.
     */
    fun toKanbanEvents(state: CursorNotionState): List<KanbanEvent.TaxonomyNodeCreated> =
        NotionKanbanBridge.projectTaxonomyEvents(state)

    /** Reduce the projected events into a [KanbanState] via the existing FSM. */
    fun toKanbanState(state: CursorNotionState): KanbanState =
        toKanbanEvents(state).fold(KanbanState()) { acc, e -> KanbanFSM.reduce(e, acc) }
}

/**
 * Minimal causal-node-shaped record the profile consumes.
 *
 * Kept as a plain value shape so the profile stays decoupled from the root
 * commonMain [CausalGraphNode]. Callers map their causal index rows into this
 * shape before calling [SoftwareKanbanNotionProfile.specialize].
 */
data class CausalNodeForProfile(
    val title: String,
    val summary: String = "",
    val status: String = "todo",
    val owner: String = "",
    val estimate: String = "",
    val due: String = "",
    val blocker: String = "",
    val parent: String = "",
)