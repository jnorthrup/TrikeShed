package borg.trikeshed.agent.modelmux

import borg.trikeshed.lib.*

enum class AssertionPhase { PENDING, VALIDATED, PASSED, FAILED }

/** Receipt projection only, not authorization or a substitute for checking the task graph. */
fun missionAssertionState(events: Series<borg.trikeshed.agent.modelmux.MissionEvent>): Series<Join<String, borg.trikeshed.agent.modelmux.AssertionPhase>> {
    val states = linkedMapOf<String, borg.trikeshed.agent.modelmux.AssertionPhase>()
    val completedWork = mutableSetOf<String>()
    for (event in events.view) for (target in event.targets.view) {
        states.getOrPut(target) { borg.trikeshed.agent.modelmux.AssertionPhase.PENDING }
        if (states[target] == borg.trikeshed.agent.modelmux.AssertionPhase.FAILED) continue
        when (event.phase) {
            borg.trikeshed.agent.modelmux.MissionPhase.ADMITTED -> {}
            borg.trikeshed.agent.modelmux.MissionPhase.FAILED, borg.trikeshed.agent.modelmux.MissionPhase.REJECTED -> states[target] = borg.trikeshed.agent.modelmux.AssertionPhase.FAILED
            borg.trikeshed.agent.modelmux.MissionPhase.COMPLETED -> when (event.role) {
                borg.trikeshed.agent.modelmux.MissionRole.WORK -> completedWork.add(target)
                borg.trikeshed.agent.modelmux.MissionRole.VALIDATE -> {
                    require(target in completedWork) { "Validation receipt precedes completed work" }
                    states[target] = borg.trikeshed.agent.modelmux.AssertionPhase.VALIDATED
                }
                borg.trikeshed.agent.modelmux.MissionRole.GATE -> {
                    require(states[target] == borg.trikeshed.agent.modelmux.AssertionPhase.VALIDATED || states[target] == borg.trikeshed.agent.modelmux.AssertionPhase.PASSED) {
                        "Gate receipt precedes validation"
                    }
                    states[target] = borg.trikeshed.agent.modelmux.AssertionPhase.PASSED
                }
            }
        }
    }
    return states.entries.toSeries() α { it.key j it.value }
}
