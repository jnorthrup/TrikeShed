@file:Suppress("UnsafeCastFromDynamic")

package borg.trikeshed.viewserver

import kotlin.js.JSON
import kotlin.js.jsTypeOf

private external val process: dynamic

private fun objectKeys(value: dynamic): Array<String> = js("Object.keys(value)")
private fun isArray(value: dynamic): Boolean = js("Array.isArray(value)")

private fun dynamicToViewValue(value: dynamic): ViewValue = when {
    value == null -> ViewValue.Null
    isArray(value) -> ViewValue.ArrayValue((value as Array<dynamic>).map(::dynamicToViewValue))
    jsTypeOf(value) == "string" -> ViewValue.Text(value as String)
    jsTypeOf(value) == "number" -> ViewValue.Number(value as Double)
    jsTypeOf(value) == "boolean" -> ViewValue.Bool(value as Boolean)
    else -> ViewValue.ObjectValue(documentFromDynamic(value))
}

private fun viewValueToDynamic(value: ViewValue): dynamic = when (value) {
    ViewValue.Null -> null
    is ViewValue.Text -> value.value
    is ViewValue.Number -> value.value
    is ViewValue.Bool -> value.value
    is ViewValue.ArrayValue -> value.values.map(::viewValueToDynamic).toTypedArray()
    is ViewValue.ObjectValue -> {
        val result: dynamic = js("({})")
        for ((key, fieldValue) in value.fields) result[key] = viewValueToDynamic(fieldValue)
        result
    }
}

private fun documentFromDynamic(value: dynamic): Map<String, ViewValue> {
    val result = mutableMapOf<String, ViewValue>()
    for (key in objectKeys(value)) {
        result[key] = dynamicToViewValue(value[key])
    }
    return result
}

private fun emissionsToDynamic(batches: List<List<ViewEmission>>): dynamic =
    batches.map { emissions ->
        emissions.map { emission ->
            arrayOf<dynamic>(
                viewValueToDynamic(emission.key),
                viewValueToDynamic(emission.value),
            )
        }.toTypedArray()
    }.toTypedArray()

/** Production CouchDB-style JSON-lines view server for the Node.js target. */
fun main() {
    val server = CommonViewServer()
    val readline: dynamic = js("require('readline')")
    val options: dynamic = js("({})")
    options.input = process.stdin
    options.output = process.stdout
    options.terminal = false
    val lines = readline.createInterface(options)

    lines.on("line") { raw: dynamic ->
        val reply: dynamic = try {
            val command = JSON.parse<dynamic>(raw as String)
            when (command[0] as String) {
                "reset" -> {
                    server.reset()
                    true
                }
                "add_fun" -> {
                    server.addFunction(command[1] as String)
                    true
                }
                "add_tool" -> {
                    server.addFunction("tool:${command[1] as String}/${command[2] as String}")
                    true
                }
                "map_doc" -> emissionsToDynamic(server.mapDocument(documentFromDynamic(command[1])))
                "reduce" -> viewValueToDynamic(
                    server.reduce(
                        command[1] as String,
                        (command[2] as Array<dynamic>).map(::dynamicToViewValue),
                    ),
                )
                "rereduce" -> viewValueToDynamic(
                    server.rereduce(
                        command[1] as String,
                        (command[2] as Array<dynamic>).map(::dynamicToViewValue),
                    ),
                )
                "tool_reduce" -> viewValueToDynamic(
                    server.reduce(
                        "tool:${command[1] as String}",
                        (command[2] as Array<dynamic>).map(::dynamicToViewValue),
                    ),
                )
                "tool_rereduce" -> viewValueToDynamic(
                    server.rereduce(
                        "tool:${command[1] as String}",
                        (command[2] as Array<dynamic>).map(::dynamicToViewValue),
                    ),
                )
                else -> "error: unknown command"
            }
        } catch (failure: dynamic) {
            "error: " + (failure.message ?: "invalid command")
        }
        println(JSON.stringify(reply))
    }
}
