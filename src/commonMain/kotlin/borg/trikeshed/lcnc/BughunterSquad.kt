package borg.trikeshed.lcnc

/**
 * The worker cosplays — a 16-persona squad for hunting bugs and reviewing work,
 * adapted from the turbohaul-manager ORCHESTRATOR_TEMPLATE (MIT): 8 research
 * lenses (the bughunt pool) and 8 council lenses (the review panel). They are
 * data, not code: [CouncilProgram.build] seats any persona string on a
 * [PanelSpec], so a cosplay is one line here plus one seat in a convening.
 *
 * [convening] is the shipped `preset-bughunter` — all 16 cosplays seated across
 * three panels (failures / surface / record), 2 rounds, so a single convening
 * exercises the whole squad. The orchestrator-discipline the template carries
 * ("one persistent authority, everyone else disposable") is the council's own
 * shape: the ruling seat holds authority; every seat is scoped to its dispatch.
 */
object BughunterSquad {

    // ── the bughunt pool: 8 research lenses ──────────────────────────────
    val RESEARCH: List<String> = listOf(
        "control-flow mapper — trace how execution actually moves through the target: entry points, call chains, branching. Flag reachable-but-unintended paths, dead code that looks load-bearing, and any place where the apparent flow doesn't match the naming or comments.",
        "data-flow & state tracker — trace where values originate, transform, persist, and end. Flag state mutated from more than one place, data outliving the scope it seems meant for, and any place where what a variable is assumed to contain doesn't match what could actually be in it.",
        "external surface & dependency scanner — every boundary where the target hands control or data outward: libraries, network calls, file I/O, other services. Flag outdated or unusual dependencies, trust placed on external input or responses, and any boundary crossed without control.",
        "historical / change archaeologist — what the commit log and prior versions say about the target. Flag areas with a history of repeated fixes to the same spot, recent changes that look rushed or under-explained, and current code that contradicts what an older comment or commit claims.",
        "convention & consistency auditor — whether the target follows the patterns used elsewhere in the same codebase. Flag naming, structure, or error handling that diverges from the established convention without a clear reason, the same thing done a different way elsewhere, and code written without awareness of a pattern it should have reused.",
        "edge-case & failure-path hunter — what happens when things go wrong: error handling, boundary conditions, empty/null/zero/max inputs, timeouts, partial failures. Flag failures silently swallowed, reachable-but-unhandled edges, and happy paths whose failure path clearly wasn't considered.",
        "configuration & environment scanner — config files, environment variables, deployment assumptions, feature flags. Flag assumptions that only hold in one environment, values that look like placeholders or leftovers, and any mismatch between what the config claims and what the code actually reads.",
        "cross-reference & duplication finder — whether this logic, or something very close, already exists somewhere else. Flag near-duplicate implementations that have drifted apart, logic that should be unified, and a fix in one spot that leaves an identical unfixed problem elsewhere.",
    )

    // ── the review panel: 8 council lenses ───────────────────────────────
    val COUNCIL: List<String> = listOf(
        "code quality reviewer — correctness, readability, maintainability, and hidden edge cases: logic errors, unhandled failure paths, unclear naming, duplicated logic that should be unified; whether this is well-built on its own terms.",
        "security & vulnerability reviewer — how this could be misused, exploited, or leak or corrupt something it shouldn't: input trusted that shouldn't be, auth gaps, unsafely handled secrets, reachable 'this will never happen' assumptions; severity flagged honestly, not inflated and not buried.",
        "architecture / big-picture reviewer — does this fit the shape of the system and still make sense as it grows; fights the existing design instead of extending it, painful coupling, a pattern duplicated under a different name, or the symptom solved instead of the cause.",
        "red team / adversarial reviewer — actively try to break the stated conclusion: what input, sequence, or edge case would defeat this, and what it's assuming that might not be true; state plainly whether this is correctly-built work or a pass that didn't try hard enough.",
        "general advisor — what the other lenses aren't covering: does this serve the original goal, scope creep, effort spent on the tangential, a simpler option nobody considered, or everyone satisfied while the operator's actual problem stays unsolved.",
        "performance & efficiency reviewer — cost in time, compute, memory: unnecessary repeated work, avoidable I/O or round-trips, algorithms that degrade badly at realistic scale, and optimization for the wrong thing; only flag concerns that are real at the scale this system actually runs at.",
        "testing & verification reviewer — whether the claims are actually provable and whether there's a way to confirm the change did what it should: confidence stated above the evidence, changes with no way to confirm, missing coverage for the exact case that prompted the change.",
        "maintainability & operations reviewer — what happens after this ships: can someone tell it's working, debug it when it isn't, and safely change it later; missing or unclear logging, no way to observe production behavior, or a change that will be quietly painful to operate even though it works today.",
    )

    /**
     * The `preset-bughunter` convening: all 16 cosplays seated.
     *
     *  - failures — what breaks: failure-path hunter, red team, security,
     *    code quality, testing & verification
     *  - surface  — what touches: control flow, data flow, external surface,
     *    configuration, duplication
     *  - record   — what it means: history, conventions, architecture,
     *    performance, maintainability
     */
    fun convening(caseId: String = "bughunt"): CouncilConfig = CouncilConfig(
        caseId = caseId,
        panels = listOf(
            PanelSpec("failures", "what breaks: failure paths, adversarial reads, exploit and quality gaps",
                personas = listOf(RESEARCH[5], COUNCIL[3], COUNCIL[1], COUNCIL[0], COUNCIL[6])),
            PanelSpec("surface", "what touches: control flow, state, external boundaries, config, duplication",
                personas = listOf(RESEARCH[0], RESEARCH[1], RESEARCH[2], RESEARCH[6], RESEARCH[7])),
            PanelSpec("record", "what it means: history, conventions, architecture, purpose, cost, operability",
                personas = listOf(RESEARCH[3], RESEARCH[4], COUNCIL[2], COUNCIL[4], COUNCIL[5], COUNCIL[7])),
        ),
        rounds = 2,
    )
}
