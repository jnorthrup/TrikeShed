package borg.trikeshed.couch.viewserver

import borg.trikeshed.viewserver.CommonViewServer
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.ResourceLimits
import org.graalvm.polyglot.proxy.ProxyExecutable

fun main() {
    val context = Context.newBuilder("js")
        .allowHostAccess(HostAccess.NONE)
        .resourceLimits(ResourceLimits.newBuilder().statementLimit(10000, null).build())
        .build()

    try {
        val server = CommonViewServer()

        context.getBindings("js").putMember("reset", ProxyExecutable { args ->
            server.reset()
            true
        })

        context.getBindings("js").putMember("addTool", ProxyExecutable { args ->
            server.addTool(args[0].asString(), args[1].asString())
            true
        })

        context.getBindings("js").putMember("mapDoc", ProxyExecutable { args ->
            server.mapDoc(args[0].asString())
        })

        context.getBindings("js").putMember("reduce", ProxyExecutable { args ->
            val tools = mutableListOf<String>()
            for (i in 0 until args[0].arraySize) {
                tools.add(args[0].getArrayElement(i).asString())
            }
            server.reduce(tools, args[1].asString())
        })

        println("reset: " + context.eval("js", "reset()"))
        println("addTool: " + context.eval("js", "addTool('couchdbcascade/byOrganization', '{}')"))
        println("map_doc: " + context.eval("js", "mapDoc('{\"organization_id\": 1, \"machine_id\": 2, \"year\": 2024, \"month\": 5, \"day\": 10, \"hour\": 12, \"minute\": 30, \"cpu_mhz\": 3500}')"))
        println("tool_reduce: " + context.eval("js", "reduce(['couchdbcascade/byOrganization'], '[[[1,2,2024,5,10,12,30], 3500], [[1,2,2024,5,10,12,31], 4000]]')"))
    } finally {
        context.close()
    }
}
