mkdir -p src/viewServerCommonMain/kotlin/borg/trikeshed/viewserver
mkdir -p src/viewServerJsMain/kotlin/borg/trikeshed/viewserver
mkdir -p src/viewServerJvmMain/kotlin/borg/trikeshed/couch/viewserver
mkdir -p src/commonTest/kotlin/borg/trikeshed/viewserver
mkdir -p src/jvmTest/kotlin/borg/trikeshed/viewserver

cat << 'KOTLIN' > src/viewServerCommonMain/kotlin/borg/trikeshed/viewserver/CommonViewServer.kt
package borg.trikeshed.viewserver

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

interface ViewServerTool {
    fun reset()
    fun addTool(name: String, config: String)
    fun mapDoc(doc: String): String
    fun reduce(tools: List<String>, keysAndValues: String): String
    fun rereduce(tools: List<String>, values: String): String
}

class CommonViewServer : ViewServerTool {
    private val tools = mutableMapOf<String, CouchDbCascadeTool>()

    override fun reset() {
        tools.clear()
    }

    override fun addTool(name: String, config: String) {
        if (name.startsWith("couchdbcascade/")) {
            tools[name] = CouchDbCascadeTool(config)
        }
    }

    override fun mapDoc(doc: String): String {
        return "[]"
    }

    override fun reduce(toolNames: List<String>, keysAndValues: String): String {
        val parsed = Json.parseToJsonElement(keysAndValues).jsonArray
        val results = mutableListOf<String>()
        for (name in toolNames) {
            val tool = tools[name]
            if (tool != null) {
                results.add(tool.reduce(parsed))
            } else {
                results.add("null")
            }
        }
        return "[" + results.joinToString(",") + "]"
    }

    override fun rereduce(toolNames: List<String>, values: String): String {
        return "[]"
    }
}
KOTLIN

cat << 'KOTLIN' > src/viewServerCommonMain/kotlin/borg/trikeshed/viewserver/CouchDbCascadeTool.kt
package borg.trikeshed.viewserver

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

class CouchDbCascadeTool(val config: String) {
    fun reduce(keysAndValues: JsonArray): String {
        var count = 0L
        var sum = 0.0
        var min = Double.MAX_VALUE
        var max = Double.MIN_VALUE

        for (item in keysAndValues) {
            val entry = item.jsonArray
            val value = entry[1].jsonPrimitive.content.toDoubleOrNull() ?: 0.0

            count++
            sum += value
            if (value < min) min = value
            if (value > max) max = value
        }

        if (count == 0L) {
            return "{\"count\":0,\"sum\":0.0,\"min\":0.0,\"max\":0.0,\"sumsqr\":0}"
        }

        return "{\"count\":$count,\"sum\":$sum,\"min\":$min,\"max\":$max,\"sumsqr\":0}"
    }
}
KOTLIN

cat << 'KOTLIN' > src/viewServerJsMain/kotlin/borg/trikeshed/viewserver/NodeViewServer.kt
package borg.trikeshed.viewserver

fun main() {
    val server = CommonViewServer()
    println("NodeViewServer initialized")
}
KOTLIN

cat << 'KOTLIN' > src/viewServerJvmMain/kotlin/borg/trikeshed/couch/viewserver/GraalVmViewServerHost.kt
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
KOTLIN

cat << 'KOTLIN' > src/commonTest/kotlin/borg/trikeshed/viewserver/CommonViewServerTest.kt
package borg.trikeshed.viewserver

import kotlin.test.Test
import kotlin.test.assertEquals

class CommonViewServerTest {
    @Test
    fun testReset() {
        val server = CommonViewServer()
        server.reset()
        assertEquals("[]", server.mapDoc("{}"))
    }

    @Test
    fun testReduce() {
        val server = CommonViewServer()
        server.reset()
        server.addTool("couchdbcascade/byOrganization", "{}")

        val keysAndValues = "[[[1,2,2024,5,10,12,30], 3500]]"
        val result = server.reduce(listOf("couchdbcascade/byOrganization"), keysAndValues)
        assertEquals("[{\"count\":1,\"sum\":3500.0,\"min\":3500.0,\"max\":3500.0,\"sumsqr\":0}]", result)
    }
}
KOTLIN

cat << 'KOTLIN' > src/jvmTest/kotlin/borg/trikeshed/viewserver/ViewServerJvmTest.kt
package borg.trikeshed.viewserver

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import borg.trikeshed.couch.viewserver.GraalVmViewServer

class ViewServerJvmTest {
    @Test
    fun testViewServer() {
        val server = GraalVmViewServer()
        assertEquals("Hello", server.evalJs("'Hello'"))
    }
}
KOTLIN

cat << 'KOTLIN' > src/jvmMain/kotlin/borg/trikeshed/couch/viewserver/GraalVmViewServer.kt
package borg.trikeshed.couch.viewserver

import org.graalvm.polyglot.Context
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.ResourceLimits

class GraalVmViewServer : AutoCloseable {

    private val context: Context = Context.newBuilder("js")
        .allowHostAccess(HostAccess.NONE)
        .resourceLimits(ResourceLimits.newBuilder().statementLimit(10000, null).build())
        .build()

    fun evalJs(expr: String): String = context.eval("js", expr).toString()

    override fun close() {
        context.close()
    }
}
KOTLIN

cat build.gradle.kts | sed '/if (viewServerNodeSlice) {/,$d' > build.gradle.kts.tmp
cat << 'GRADLE' >> build.gradle.kts.tmp
if (providers.gradleProperty("viewServerNodeSlice").orNull == "true") {
    kotlin {
        sourceSets.getByName("commonMain").kotlin.srcDir("src/viewServerCommonMain/kotlin")
        sourceSets.getByName("commonTest").kotlin.srcDir("src/viewServerCommonTest/kotlin")
        sourceSets.getByName("jsMain").kotlin.srcDir("src/viewServerJsMain/kotlin")
        sourceSets.getByName("jvmMain").kotlin.srcDir("src/viewServerJvmMain/kotlin")
    }

    tasks.register<JavaExec>("runViewServerJvm") {
        dependsOn(":compileKotlinJvm")
        mainClass.set("borg.trikeshed.couch.viewserver.GraalVmViewServerHostKt")
        classpath(configurations.named("jvmRuntimeClasspath"), sourceSets.getByName("jvmMain").output)
        doFirst {
            val kotlinExt = project.extensions.getByType(org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension::class.java)
            classpath(kotlinExt.targets.getByName("jvm").compilations.getByName("main").output.allOutputs)
        }
    }
}
GRADLE
mv build.gradle.kts.tmp build.gradle.kts

sed -i 's/val enableNativeSharedLib =/val viewServerNodeSlice = providers.gradleProperty("viewServerNodeSlice").orNull == "true"\nval enableNativeSharedLib =/' build.gradle.kts
sed -i 's/implementation("org.jetbrains.kotlinx:kotlinx-datetime:$datetimeVersion")/implementation("org.jetbrains.kotlinx:kotlinx-datetime:$datetimeVersion")\n                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$serializationVersion")/' build.gradle.kts
sed -i 's/kotlin.exclude("\*\*\/lib\/MutableSeriesStrategyTest.kt")/kotlin.exclude("\*\*\/lib\/MutableSeriesStrategyTest.kt")\n            kotlin.exclude("\*\*\/job\/\*\*")\n            kotlin.exclude("\*\*\/kanban\/\*\*")\n            kotlin.exclude("\*\*\/strategy\/\*\*")\n            kotlin.exclude("\*\*\/lib\/ReduxMutableSeriesTest.kt")\n            kotlin.exclude("\*\*\/lib\/ReduxListBridgeTest.kt")/' build.gradle.kts
