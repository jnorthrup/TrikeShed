package borg.trikeshed.sleeve

import borg.trikeshed.kanban.CardPriority
import borg.trikeshed.kanban.KanbanCard
import borg.trikeshed.kanban.KanbanCardId
import borg.trikeshed.kanban.KanbanColumnId

/**
 * Traps → cards on the live board.
 *
 * The ethos in one direction: an incompatibility that is merely *known* is worth nothing, so every
 * unresolved trap becomes a card that names the source fork to write and the red test that must fail
 * first. A trap resolved as PORTED or WITHHELD stops generating work.
 *
 * Cards land in `todo`, not `ready`. The board's claim loop dispatches Ready to a model and parks the
 * result in Review, so a trap nobody has watched fail would otherwise be handed to a model on the
 * strength of a description alone. Promoting a card to Ready is the act of saying "I have seen this
 * break" — and the spec below is what the plane judge closes against once it is.
 */
object SleeveTddRedKanban {

    /** The board's real intake-past column. `triage` is for unsorted intake; these arrive sorted. */
    val COLUMN: KanbanColumnId = KanbanColumnId.of("todo")

    /** Lower is more urgent, matching the board. An uncatchable trap hides everything behind it. */
    const val PRIORITY_BLOCKING = 1
    const val PRIORITY_UNCATCHABLE = 1
    const val PRIORITY_CATCHABLE = 2

    /** Stable id, so re-running this never duplicates a card for a trap that already has one. */
    fun cardId(trap: PolyglotTrap): KanbanCardId = KanbanCardId("sleeve-${trap.id.replace('/', '-')}")

    fun pinCardId(manifest: AgentSleeveManifest): KanbanCardId =
        KanbanCardId("sleeve-${manifest.id}-pin-donor-root")

    fun cards(manifest: AgentSleeveManifest): List<KanbanCard> {
        val unpinned = unpinnedCard(manifest)
        // Every trap card depends on the donor root being pinned: you cannot reproduce a break in a
        // checkout you have not named, so the whole sleeve hangs off that one card when it exists.
        val blockers = listOfNotNull(unpinned?.id)
        return listOfNotNull(unpinned) + manifest.unresolved.map { trap -> card(manifest, trap, blockers) }
    }

    fun cards(): List<KanbanCard> = AgentSleeveRegistry.ALL.flatMap(::cards)

    /**
     * The acceptance spec, as the RFC 2119 lines the board reads. The judge closes a claimed card when
     * every MUST is met with an evidence id, so each MUST here names something checkable rather than an
     * intention: a file that exists, a test that passes, a shape that was chosen.
     */
    fun spec(manifest: AgentSleeveManifest, trap: PolyglotTrap): String = buildString {
        appendLine("GOAL: resolve the ${trap.symbol} trap so ${manifest.entryModule} survives it under ${manifest.facet.id}")
        appendLine("MUST: ${manifest.sleeveRoot}/${trap.forkPath} exists and shadows ${trap.donorModule} by module identity")
        appendLine("MUST: ${trap.redTest} fails against the unforked sleeve and passes against the fork")
        appendLine("MUST: the resolution is recorded as withheld or ported, never an inert stub that returns a wrong answer quietly")
        if (trap.catchability == TrapCatchability.HOST_UNCATCHABLE) {
            appendLine("MUST: the guest can observe the failure afterwards — no host exception may reach InProcessIsolate.classify as DEAD")
        } else {
            appendLine("SHOULD: check what the caller does with the error; a swallowed refusal needs a ported twin, not a withheld one")
        }
        appendLine("OUT-OF-SCOPE: editing the donor checkout at ${manifest.donorRoot ?: "(unpinned)"}")
        appendLine("OUT-OF-SCOPE: loosening the isolate bounds to make the trap disappear")
        appendLine("REVIEW: human")
        appendLine("AGENT: codex")
    }

    fun pinSpec(manifest: AgentSleeveManifest): String = buildString {
        appendLine("GOAL: pin the ${manifest.id} donor root so the sleeve can run and trap")
        appendLine("MUST: AgentSleeveRegistry.${manifest.id.uppercase()}.donorRoot names a checkout that exists")
        appendLine("MUST: ${manifest.entryModule} has been attempted once inside ${manifest.facet.id} and the outcome recorded")
        appendLine("MUST: every trap the attempt hits is filed as a PolyglotTrap with evidence, not a summary")
        appendLine("OUT-OF-SCOPE: resolving any trap the attempt finds — those are their own cards")
        appendLine("REVIEW: human")
    }

    /**
     * The `kanban.submit` payload shape, so these can go onto the live board without a second
     * translation. `priority` is the board's integer, not [CardPriority].
     */
    fun submissions(): List<Map<String, Any?>> = AgentSleeveRegistry.ALL.flatMap { manifest ->
        cards(manifest).map { card ->
            val trapId = card.metadata["trap"]
            val trap = manifest.traps.firstOrNull { it.id == trapId }
            mapOf(
                "jobId" to card.id.value,
                "title" to card.title,
                "spec" to if (trap == null) pinSpec(manifest) else spec(manifest, trap),
                "tags" to card.tags.toList(),
                "dependencies" to card.dependencies.map { it.value },
                "priority" to (card.metadata["boardPriority"]?.toInt() ?: PRIORITY_CATCHABLE),
            )
        }
    }

    private fun unpinnedCard(manifest: AgentSleeveManifest): KanbanCard? {
        if (manifest.pinned) return null
        return KanbanCard(
            id = pinCardId(manifest),
            title = "Sleeve ${manifest.id}: pin the donor root so the sleeve can run and trap",
            description = buildString {
                appendLine("`${manifest.sleeveRoot}` shadows donor modules by module identity, but no donor")
                appendLine("checkout is named, so nothing can be run and nothing can be trapped.")
                appendLine()
                appendLine(pinSpec(manifest))
            },
            columnId = COLUMN,
            priority = CardPriority.HIGH,
            tags = setOf("sleeve", manifest.id, "polyglot", "blocked-on-ruling"),
            metadata = mapOf(
                "sleeve" to manifest.id,
                "facet" to manifest.facet.id,
                "sleeveRoot" to manifest.sleeveRoot,
                "entryModule" to manifest.entryModule,
                "kind" to "pin-donor-root",
                "boardPriority" to PRIORITY_BLOCKING.toString(),
            ),
        )
    }

    private fun card(manifest: AgentSleeveManifest, trap: PolyglotTrap, blockers: List<KanbanCardId>): KanbanCard {
        val uncatchable = trap.catchability == TrapCatchability.HOST_UNCATCHABLE
        return KanbanCard(
            id = cardId(trap),
            title = "Sleeve ${manifest.id}: ${trap.donorModule} · ${trap.symbol}",
            description = buildString {
                appendLine("**Observed.** ${trap.evidence}")
                appendLine()
                appendLine("**Fork.** `${manifest.sleeveRoot}/${trap.forkPath}` — shadow ${trap.donorModule} by")
                appendLine("module identity; never edit the donor checkout.")
                appendLine()
                appendLine("**Red test.** `${trap.redTest}` must fail before the fork exists and pass after.")
                appendLine()
                if (uncatchable) {
                    appendLine("**Uncatchable.** The guest cannot see this failure — it arrives as a host")
                    appendLine("exception, the isolate is classified DEAD, and every trap behind it stays hidden.")
                    appendLine("Resolve this before anything ranked below it.")
                } else {
                    appendLine("**Catchable.** The guest observes this one, so a wrong resolution degrades")
                    appendLine("quietly rather than downing the VM — check what the caller does with the error.")
                }
                appendLine()
                appendLine(spec(manifest, trap))
            },
            columnId = COLUMN,
            priority = if (uncatchable) CardPriority.CRITICAL else CardPriority.HIGH,
            dependencies = blockers,
            tags = setOf("sleeve", manifest.id, "polyglot", "tdd-red", trap.catchability.name.lowercase()),
            metadata = mapOf(
                "sleeve" to manifest.id,
                "facet" to manifest.facet.id,
                "trap" to trap.id,
                "donorModule" to trap.donorModule,
                "symbol" to trap.symbol,
                "forkPath" to "${manifest.sleeveRoot}/${trap.forkPath}",
                "redTest" to trap.redTest,
                "catchability" to trap.catchability.name.lowercase(),
                "kind" to "polyglot-trap",
                "boardPriority" to (if (uncatchable) PRIORITY_UNCATCHABLE else PRIORITY_CATCHABLE).toString(),
            ),
        )
    }
}
