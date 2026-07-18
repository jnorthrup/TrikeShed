package borg.trikeshed.couch.viewserver

import borg.trikeshed.viewserver.CommonViewServer
import borg.trikeshed.viewserver.ViewValue
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.ResourceLimits
import org.graalvm.polyglot.Value
import org.graalvm.polyglot.proxy.ProxyExecutable

private fun polyglotToViewValue(value: Value): ViewValue = when {
    value.isNull -> ViewValue.Null
    value.isString -> ViewValue.Text(value.asString())
    value.isNumber -> ViewValue.Number(value.asDouble())
    value.isBoolean -> ViewValue.Bool(value.asBoolean())
    value.hasArrayElements() -> {
        val list = mutableListOf<ViewValue>()
        for (i in 0 until value.arraySize) {
            list.add(polyglotToViewValue(value.getArrayElement(i)))
        }
        ViewValue.ArrayValue(list)
    }
    value.hasMembers() -> {
        val map = mutableMapOf<String, ViewValue>()
        for (key in value.memberKeys) {
            map[key] = polyglotToViewValue(value.getMember(key))
        }
        ViewValue.ObjectValue(map)
    }
    else -> ViewValue.Null
}

private fun polyglotToDocument(value: Value): Map<String, ViewValue> {
    val result = mutableMapOf<String, ViewValue>()
    for (key in value.memberKeys) {
        result[key] = polyglotToViewValue(value.getMember(key))
    }
    return result
}

private fun viewValueToPolyglot(value: ViewValue, context: Context): Value = when (value) {
    ViewValue.Null -> context.asValue(null)
    is ViewValue.Text -> context.asValue(value.value)
    is ViewValue.Number -> context.asValue(value.value)
    is ViewValue.Bool -> context.asValue(value.value)
    is ViewValue.ArrayValue -> {
        val list = value.values.map { viewValueToPolyglot(it, context) }.toTypedArray()
        context.asValue(list)
    }
    is ViewValue.ObjectValue -> {
        val obj = context.eval("js", "({})")
        for ((k, v) in value.fields) {
            obj.putMember(k, viewValueToPolyglot(v, context))
        }
        obj
    }
}

fun main() {
    val context = Context.newBuilder("js")
        .allowHostAccess(HostAccess.NONE)
        .resourceLimits(ResourceLimits.newBuilder().statementLimit(10000, null).build())
        .build()

    try {
        val server = CommonViewServer()

        context.getBindings("js").putMember("reset", ProxyExecutable { _ ->
            server.reset()
            true
        })

        context.getBindings("js").putMember("add_fun", ProxyExecutable { args ->
            server.addFunction(args[0].asString())
            true
        })

        context.getBindings("js").putMember("add_tool", ProxyExecutable { args ->
            server.addFunction("tool:${args[0].asString()}/${args[1].asString()}")
            true
        })

        context.getBindings("js").putMember("map_doc", ProxyExecutable { args ->
            val doc = polyglotToDocument(args[0])
            val emissions = server.mapDocument(doc)
            val outerList = emissions.map { batch ->
                batch.map { emission ->
                    arrayOf(viewValueToPolyglot(emission.key, context), viewValueToPolyglot(emission.value, context))
                }.toTypedArray()
            }.toTypedArray()
            context.asValue(outerList)
        })

        context.getBindings("js").putMember("reduce", ProxyExecutable { args ->
            val name = args[0].asString()
            val vals = mutableListOf<ViewValue>()
            val arr = args[1]
            for (i in 0 until arr.arraySize) {
                vals.add(polyglotToViewValue(arr.getArrayElement(i)))
            }
            viewValueToPolyglot(server.reduce(name, vals), context)
        })

        context.getBindings("js").putMember("rereduce", ProxyExecutable { args ->
            val name = args[0].asString()
            val vals = mutableListOf<ViewValue>()
            val arr = args[1]
            for (i in 0 until arr.arraySize) {
                vals.add(polyglotToViewValue(arr.getArrayElement(i)))
            }
            viewValueToPolyglot(server.rereduce(name, vals), context)
        })

        context.getBindings("js").putMember("tool_reduce", ProxyExecutable { args ->
            val name = "tool:" + args[0].asString()
            val vals = mutableListOf<ViewValue>()
            val arr = args[1]
            for (i in 0 until arr.arraySize) {
                vals.add(polyglotToViewValue(arr.getArrayElement(i)))
            }
            viewValueToPolyglot(server.reduce(name, vals), context)
        })

        context.getBindings("js").putMember("tool_rereduce", ProxyExecutable { args ->
            val name = "tool:" + args[0].asString()
            val vals = mutableListOf<ViewValue>()
            val arr = args[1]
            for (i in 0 until arr.arraySize) {
                vals.add(polyglotToViewValue(arr.getArrayElement(i)))
            }
            viewValueToPolyglot(server.rereduce(name, vals), context)
        })

        println("reset: " + context.eval("js", "reset()"))
        println("add_tool: " + context.eval("js", "add_tool('couchdbcascade', 'byOrganization')"))
        println("map_doc: " + context.eval("js", "map_doc({'organization_id': '1', 'machine_id': '2', 'reading_date': '2024-05-10T12:30:00Z', 'cpu_mhz': 3500})"))
        println("tool_reduce: " + context.eval("js", "tool_reduce('couchdbcascade', [{'organization_id': '1', 'machine_id': '2', 'reading_date': '2024-05-10T12:30:00Z', 'cpu_mhz': 3500}, {'organization_id': '1', 'machine_id': '2', 'reading_date': '2024-05-10T12:31:00Z', 'cpu_mhz': 4000}])"))

    } finally {
        context.close()
    }
}
