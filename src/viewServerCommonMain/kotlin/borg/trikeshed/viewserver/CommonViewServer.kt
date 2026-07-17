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
