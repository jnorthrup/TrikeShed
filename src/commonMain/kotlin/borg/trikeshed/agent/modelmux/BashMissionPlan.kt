package borg.trikeshed.agent.modelmux

import borg.trikeshed.context.nuid.Nuid
import borg.trikeshed.lib.*
import borg.trikeshed.parse.reifyStrict
import borg.trikeshed.parse.*
import borg.trikeshed.parse.confix.*
import borg.trikeshed.userspace.nio.channels.spi.ProcessResult

/** Explicit assertions are kept separate from execution bindings. */
fun missionAssertions(source: String): Series<String> {
    val root = reifyStrict(source) as? Map<*, *> ?: error("Mission plan must be an object")
    if ("assertions" !in root) return emptySeriesOf()
    val assertions = root["assertions"] as? List<*> ?: error("Assertions must be an array")
    return assertions.map { it as? String ?: error("Assertion identity must be a string") }.toSeries()
}

/** Strict bounded wire boundary. Dependency validation remains with executeMission. */
fun bashMissionPlan(source: String, address: Nuid, modelAddress: Nuid? = null): Series<borg.trikeshed.agent.modelmux.MissionTask> {
    require(source.length <= 1_048_576) { "Mission plan exceeds 1Mi characters" }
    val root = reifyStrict(source) as? Map<*, *> ?: error("Mission plan must be an object")
    require("tasks" in root && root.keys.all { it in setOf("tasks", "assertions") }) { "Unknown mission plan field" }
    val rows = root["tasks"] as? List<*> ?: error("Mission tasks must be an array")
    require(rows.size in 1..1000) { "Mission requires 1..1000 tasks" }
    fun strings(value: Any?): Series<String> {
        val values = value as? List<*> ?: error("Expected string array")
        return values.map { it as? String ?: error("Expected string argument") }.toSeries()
    }
    val document = confixDoc(source)
    return rows.mapIndexed { index, value ->
        val row = value as? Map<*, *> ?: error("Mission task must be an object")
        val kind = row["kind"] ?: "bash"
        require(kind == "bash" || kind == "model") { "Unknown mission task kind" }
        val fields = if (kind == "bash") setOf("id", "kind", "args", "depends_on")
            else setOf("id", "kind", "model", "request", "depends_on")
        require(row.keys.all { it in fields || it == "type" || it == "targets" }) { "Unknown mission task field" }
        val role = when (row["type"] ?: "work") {
            "work" -> borg.trikeshed.agent.modelmux.MissionRole.WORK
            "validate" -> borg.trikeshed.agent.modelmux.MissionRole.VALIDATE
            "gate" -> borg.trikeshed.agent.modelmux.MissionRole.GATE
            else -> error("Unknown mission task type")
        }
        val targets = if ("targets" in row) strings(row["targets"]) else emptySeriesOf()
        val id = row["id"] as? String ?: error("Mission task requires id")
        require(id.matches(Regex("[A-Za-z0-9_.-]{1,128}"))) { "Invalid mission task id" }
        val dependencies = if ("depends_on" in row) strings(row["depends_on"]) else emptySeriesOf()
        val task = if (kind == "model") {
            val destination = requireNotNull(modelAddress) { "Model workgroup is not configured" }
            val model = row["model"] as? String ?: error("Model task requires model")
            require(model.isNotBlank()) { "Empty model identity" }
            require(row["request"] is Map<*, *>) { "Model task requires request object" }
            val request = requireNotNull(document.getAt("tasks", index, "request"))
            // Preserve exact JSON tokens: reification otherwise turns integer API parameters into doubles.
            val original = source.substring(request.open, request.close + 1)
            borg.trikeshed.agent.modelmux.MissionTask(
                id,
                destination,
                dependencies,
                borg.trikeshed.agent.modelmux.ModelWork(model, confixDoc(original))
            )
        } else {
            val arguments = strings(row["args"])
            require(arguments.size > 0) { "Mission task requires Bash arguments" }
            borg.trikeshed.agent.modelmux.MissionTask(
                id, address, dependencies, borg.trikeshed.agent.modelmux.BashWork(arguments),
                accept = { (it as ProcessResult).exitCode == 0 })
        }
        task.copy(role = role, targets = targets)
    }.toSeries()
}
