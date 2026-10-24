package borg.trikeshed.agent.modelmux

import borg.trikeshed.lib.*

/** Zenith task roles govern assertion ownership and validation-before-seal. */
enum class MissionRole { WORK, VALIDATE, GATE }

/** Validate a complete, acyclic task graph before admitting any effects. */
fun validateMissionContract(tasks: Series<borg.trikeshed.agent.modelmux.MissionTask>, assertions: Series<String>) {
    val declared = assertions.view.toSet()
    require(declared.size == assertions.size) { "Duplicate mission assertion" }
    require(declared.all { it.matches(Regex("[A-Z][A-Z0-9-]+")) }) { "Invalid assertion identity" }
    val indexed = tasks.view.associateBy { it.id }
    if (declared.isEmpty()) {
        require(tasks.view.all { it.role == borg.trikeshed.agent.modelmux.MissionRole.WORK && it.targets.size == 0 }) {
            "Task roles and targets require an explicit assertion contract"
        }
        return
    }
    tasks.view.forEach { task ->
        require(task.targets.size > 0 && task.targets.view.all { it in declared }) { "Unknown or missing assertion target" }
    }
    fun ancestors(task: borg.trikeshed.agent.modelmux.MissionTask): Set<String> {
        val result = mutableSetOf<String>()
        fun add(id: String) {
            if (result.add(id)) indexed.getValue(id).dependencies.view.forEach { add(it) }
        }
        task.dependencies.view.forEach { add(it) }
        return result
    }
    declared.forEach { assertion ->
        val owners = tasks.view.filter { it.role == borg.trikeshed.agent.modelmux.MissionRole.WORK && assertion in it.targets.view }
        require(owners.size == 1) { "Each assertion requires exactly one work owner" }
        val validators = tasks.view.filter { it.role == borg.trikeshed.agent.modelmux.MissionRole.VALIDATE && assertion in it.targets.view }
        require(validators.isNotEmpty()) { "Assertion requires independent validation task" }
        validators.forEach { require(owners[0].id in ancestors(it)) { "Validator must depend on work owner" } }
        val gates = tasks.view.filter { it.role == borg.trikeshed.agent.modelmux.MissionRole.GATE && assertion in it.targets.view }
        require(gates.isNotEmpty()) { "Assertion requires a sealing gate" }
        gates.forEach { gate ->
            val upstream = ancestors(gate)
            require(validators.view.any { it.id in upstream }) { "Gate cannot bypass assertion validation" }
        }
    }
}
