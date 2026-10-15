package borg.trikeshed.lcnc

import borg.trikeshed.lib.*

/** Inert route data. Confix owns the wire formats; [parse] consumes their reified values. */
data class CamelRoutePlan(val from: String, val steps: Series<CamelRouteStep>) {
    companion object {
        fun parse(value: Any?): CamelRoutePlan = CamelRoutePlanParser().parse(value)
    }

    fun toValue(): Map<String, Any?> {
        CamelRoutePlanBounds().visit(this) {}
        return linkedMapOf("from" to from, "steps" to steps.value())
    }

    /** Complete this gate before creating a Camel context, route, consumer, or producer. */
    fun validate(reach: CamelLinkage.Reach, mounted: Collection<String>) {
        CamelRoutePlanBounds().visit(this) { uri ->
            val refusal = CamelLinkage.refusal(uri, reach, mounted)
            require(refusal == null) { refusal ?: "Camel endpoint refused" }
        }
    }
}

sealed interface CamelRouteStep {
    data class To(val uri: String) : CamelRouteStep
    data class SetBody(val value: String) : CamelRouteStep
    data class SetHeader(val name: String, val value: String) : CamelRouteStep
    data class Choice(val branches: Series<CamelRouteBranch>, val otherwise: Series<CamelRouteStep>) : CamelRouteStep
    data object Stop : CamelRouteStep
}

data class CamelRouteBranch(val bodyEquals: String, val steps: Series<CamelRouteStep>)

/** Shared whole-plan budgets, also applied to plans built directly through the public algebra. */
private class CamelRoutePlanBounds {
    var steps = 0
    var branches = 0
    var textBytes = 0

    fun steps(size: Int, depth: Int) {
        require(depth <= 8) { "Camel route choice depth exceeds 8" }
        require(size in 0..(128 - steps)) { "Camel route exceeds 128 steps" }
        steps += size
    }

    fun branches(size: Int) {
        // Empty branches must not provide an unbounded path around the step budget.
        require(size in 0..(128 - branches)) { "Camel route exceeds 128 branches" }
        branches += size
    }

    fun text(value: Any?, path: String, nonblank: Boolean = false): String {
        require(value is String) { "$path must be a string" }
        require(value.length <= 65536 - textBytes) { "Camel route text exceeds 64 KiB" }
        val bytes = value.encodeToByteArray().size
        require(bytes <= 65536 - textBytes) { "Camel route text exceeds 64 KiB" }
        textBytes += bytes
        require(!nonblank || value.isNotBlank()) { "$path must not be blank" }
        return value
    }

    fun endpoint(value: Any?, path: String): String = text(value, path, nonblank = true).also {
        require("{{" !in it && "\${" !in it && "\$simple{" !in it) {
            "$path must be a static endpoint without property placeholders or dynamic expressions"
        }
    }

    fun visit(plan: CamelRoutePlan, admit: (String) -> Unit) {
        admit(endpoint(plan.from, "from"))
        visit(plan.steps, 0, admit)
    }

    fun visit(values: Series<CamelRouteStep>, depth: Int, admit: (String) -> Unit) {
        steps(values.size, depth)
        for (step in values.view) when (step) {
            is CamelRouteStep.To -> admit(endpoint(step.uri, "to"))
            is CamelRouteStep.SetBody -> text(step.value, "setBody")
            is CamelRouteStep.SetHeader -> {
                text(step.name, "setHeader.name", nonblank = true)
                text(step.value, "setHeader.value")
            }
            is CamelRouteStep.Choice -> {
                branches(step.branches.size)
                for (branch in step.branches.view) {
                    text(branch.bodyEquals, "choice.when.bodyEquals")
                    visit(branch.steps, depth + 1, admit)
                }
                visit(step.otherwise, depth + 1, admit)
            }
            CamelRouteStep.Stop -> Unit
        }
    }
}

private class CamelRoutePlanParser {
    val bounds = CamelRoutePlanBounds()

    fun parse(value: Any?): CamelRoutePlan {
        val root = fields(value, "route", "from", "steps")
        return CamelRoutePlan(bounds.endpoint(root["from"], "from"), steps(root["steps"], 0, "steps"))
    }

    fun steps(value: Any?, depth: Int, path: String): Series<CamelRouteStep> {
        val values = array(value, path)
        bounds.steps(values.size, depth)
        // Eager, owned storage: admission cannot be deferred by a lazy projection or input mutation.
        return Array(values.size) { step(values[it], depth, "$path[$it]") }.toSeries()
    }

    fun step(value: Any?, depth: Int, path: String): CamelRouteStep {
        require(value is Map<*, *> && value.size == 1) { "$path must be a single-key step object" }
        val key = value.keys.single()
        require(key is String) { "$path keys must be strings" }
        val payload = value[key]
        return when (key) {
            "to" -> CamelRouteStep.To(bounds.endpoint(payload, "$path.to"))
            "setBody" -> CamelRouteStep.SetBody(bounds.text(payload, "$path.setBody"))
            "setHeader" -> {
                val header = fields(payload, "$path.setHeader", "name", "value")
                CamelRouteStep.SetHeader(
                    bounds.text(header["name"], "$path.setHeader.name", nonblank = true),
                    bounds.text(header["value"], "$path.setHeader.value"),
                )
            }
            "choice" -> {
                val choice = fields(payload, "$path.choice", "when", "otherwise")
                val branches = array(choice["when"], "$path.choice.when")
                bounds.branches(branches.size)
                CamelRouteStep.Choice(
                    Array(branches.size) { i ->
                        val branchPath = "$path.choice.when[$i]"
                        val branch = fields(branches[i], branchPath, "bodyEquals", "steps")
                        CamelRouteBranch(
                            bounds.text(branch["bodyEquals"], "$branchPath.bodyEquals"),
                            steps(branch["steps"], depth + 1, "$branchPath.steps"),
                        )
                    }.toSeries(),
                    steps(choice["otherwise"], depth + 1, "$path.choice.otherwise"),
                )
            }
            "stop" -> {
                require(payload is Boolean && payload) { "$path.stop must be true" }
                CamelRouteStep.Stop
            }
            else -> throw IllegalArgumentException("$path has an unknown step key")
        }
    }

    fun fields(value: Any?, path: String, vararg names: String): Map<*, *> {
        require(value is Map<*, *>) { "$path must be an object" }
        require(value.size == names.size && value.keys.all { it is String && it in names } &&
            names.all { value.containsKey(it) }) { "$path requires exactly ${names.joinToString()}" }
        return value
    }

    fun array(value: Any?, path: String): Series<Any?> = when (value) {
        is List<*> -> value.toSeries()
        is Array<*> -> value.toSeries()
        else -> throw IllegalArgumentException("$path must be an array")
    }
}

/** Lists are the Confix value boundary; the model itself retains Series throughout. */
private fun Series<CamelRouteStep>.value(): List<Map<String, Any?>> = view.map { step ->
    when (step) {
        is CamelRouteStep.To -> linkedMapOf("to" to step.uri)
        is CamelRouteStep.SetBody -> linkedMapOf("setBody" to step.value)
        is CamelRouteStep.SetHeader -> linkedMapOf(
            "setHeader" to linkedMapOf("name" to step.name, "value" to step.value),
        )
        is CamelRouteStep.Choice -> linkedMapOf(
            "choice" to linkedMapOf(
                "when" to step.branches.view.map { branch ->
                    linkedMapOf("bodyEquals" to branch.bodyEquals, "steps" to branch.steps.value())
                },
                "otherwise" to step.otherwise.value(),
            ),
        )
        CamelRouteStep.Stop -> linkedMapOf("stop" to true)
    }
}
