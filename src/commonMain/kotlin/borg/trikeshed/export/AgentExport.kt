package borg.trikeshed.export

import borg.trikeshed.lib.*
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.parse.reify
import kotlin.math.abs

/**
 * The daemon's MCP tools offered again as an A2A agent and as OpenAI function tools.
 *
 * The MCP server is the one owner of what a tool is: these faces read its `tools/list` and forward to its
 * `tools/call`, so a tool registered there appears on every face and a tool absent there appears on none.
 * [mcp] is that server's request handler, one JSON-RPC document in and one out (`LcncKanbanMcp.handle`,
 * `McpToolServer.handle`).
 *
 * A2A (protocol [A2A_PROTOCOL]) is answered in messages: `message/send` with a data part
 * `{"skill": <tool name>, "arguments": {…}}` (POST [A2A_PATH]) returns the tool's result as a data part. No task is ever created,
 * so the task methods report task-not-found and streaming and push notifications are refused as unsupported,
 * which is what the card's capabilities advertise.
 */
class AgentExport(
    val name: String,
    val description: String,
    val version: String,
    val mcp: suspend (String) -> String,
) {
    suspend fun tools(): Series<Map<String, Any?>> {
        val reply = reify(mcp(jsonOf(_m["jsonrpc" j "2.0", "id" j "export", "method" j "tools/list"]))) as? Map<*, *>
        val listed = ((reply?.get("result") as? Map<*, *>)?.get("tools") as? List<*>).orEmpty()
        return listed.mapNotNull { tool -> (tool as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to v } }.toSeries()
    }

    /** The A2A agent card, served at `/.well-known/agent-card.json`; [base] is the daemon's absolute origin. */
    suspend fun agentCard(base: String): Map<String, Any?> = _m[
        "protocolVersion" j A2A_PROTOCOL,
        "name" j name,
        "description" j description,
        "url" j base.trimEnd('/') + A2A_PATH,
        "preferredTransport" j "JSONRPC",
        "version" j version,
        "documentationUrl" j base.trimEnd('/') + INDEX_PATH,
        "capabilities" j _m["streaming" j false, "pushNotifications" j false, "stateTransitionHistory" j false],
        "defaultInputModes" j _l["application/json"],
        "defaultOutputModes" j _l["application/json", "text/plain"],
        "skills" j (tools() α { tool ->
            val readOnly = (tool["annotations"] as? Map<*, *>)?.get("readOnlyHint") == true
            _m[
                "id" j tool["name"].toString(),
                "name" j (tool["title"] ?: tool["name"]).toString(),
                "description" j tool["description"]?.toString().orEmpty(),
                "tags" j _l["mcp", if (readOnly) "read-only" else "writes"],
                "inputModes" j _l["application/json"],
                "outputModes" j _l["application/json", "text/plain"],
            ]
        }).view.toList(),
        "supportsAuthenticatedExtendedCard" j false,
    ]

    /** The same tools as OpenAI `tools` entries; [openAiName] maps a tool name onto OpenAI's function-name alphabet. */
    suspend fun openAiTools(): Series<Map<String, Any?>> {
        val tools = tools()
        val names = tools α { openAiName(it["name"].toString()) }
        require(names.view.toSet().size == names.size) { "two MCP tool names map to one OpenAI function name: ${names.view.toList()}" }
        return tools α { tool ->
            _m[
                "type" j "function",
                "function" j _m[
                    "name" j openAiName(tool["name"].toString()),
                    "description" j tool["description"]?.toString().orEmpty(),
                    "parameters" j (tool["inputSchema"] ?: _m["type" j "object"]),
                ],
            ]
        }
    }

    /** One A2A JSON-RPC request document in, one response document out. */
    suspend fun a2a(request: String): String {
        val document = runCatching { reify(request) }.getOrNull() as? Map<*, *>
            ?: return failure(null, -32700, "the request is not a JSON object")
        val id = when (val raw = document["id"]) {
            is String -> raw
            is Number -> raw.toDouble().takeIf { it % 1.0 == 0.0 && abs(it) <= 9.007199254740991E15 }?.toLong()
                ?: return failure(null, -32600, "id must be a string or an integer")
            else -> return failure(null, -32600, "a request needs an id")
        }
        if (document["jsonrpc"] != "2.0") return failure(id, -32600, "jsonrpc must be \"2.0\"")
        return when (document["method"]) {
            "message/send" -> send(id, document["params"] as? Map<*, *>)
            "message/stream", "tasks/resubscribe" -> failure(id, -32004, "this agent does not stream; use message/send")
            "tasks/get", "tasks/cancel" -> failure(id, -32001, "this agent answers in messages and keeps no tasks")
            "tasks/pushNotificationConfig/set", "tasks/pushNotificationConfig/get",
            "tasks/pushNotificationConfig/list", "tasks/pushNotificationConfig/delete" ->
                failure(id, -32003, "push notifications are not supported")
            else -> failure(id, -32601, "method not found")
        }
    }

    suspend fun send(id: Any, params: Map<*, *>?): String {
        val message = params?.get("message") as? Map<*, *> ?: return failure(id, -32602, "params.message is required")
        val call = (message["parts"] as? List<*>).orEmpty()
            .mapNotNull { part -> (part as? Map<*, *>)?.takeIf { it["kind"] == "data" }?.get("data") as? Map<*, *> }
            .firstOrNull { it["skill"] is String }
            ?: return failure(id, -32602, "message/send needs a data part {\"skill\": <tool name>, \"arguments\": {…}}; the agent card lists the skills")
        val arguments = call["arguments"] as? Map<*, *> ?: emptyMap<String, Any?>()
        val reply = reify(mcp(jsonOf(_m[
            "jsonrpc" j "2.0", "id" j "a2a", "method" j "tools/call",
            "params" j _m["name" j call["skill"].toString(), "arguments" j arguments],
        ]))) as? Map<*, *> ?: return failure(id, -32603, "the tool server returned no document")
        (reply["error"] as? Map<*, *>)?.let { error ->
            return failure(id, (error["code"] as? Number)?.toInt() ?: -32603, error["message"]?.toString() ?: "tool call failed")
        }
        val result = reply["result"] as? Map<*, *> ?: return failure(id, -32603, "the tool server returned no result")
        val text = (result["content"] as? List<*>).orEmpty()
            .mapNotNull { (it as? Map<*, *>)?.takeIf { c -> c["type"] == "text" }?.get("text")?.toString() }
        val parts = (result["structuredContent"] as? Map<*, *>)?.let { _l[_m["kind" j "data", "data" j it]] }
            ?: text.map { _m["kind" j "text", "text" j it] }
        return jsonOf(_m[
            "jsonrpc" j "2.0",
            "id" j id,
            "result" j _m[
                "kind" j "message",
                "role" j "agent",
                "messageId" j "${message["messageId"] ?: "a2a"}:reply",
                "contextId" j message["contextId"],
                "parts" j parts,
                "metadata" j if (result["isError"] == true) _m["isError" j true] else null,
            ].filterValues { it != null },
        ])
    }

    companion object {
        const val A2A_PROTOCOL: String = "0.3.0"
        const val A2A_PATH: String = "/api/a2a"
        /** A2A discovery reads the card at the origin's well-known path; the server aliases it to [CARD_ROUTE]. */
        const val CARD_PATH: String = "/.well-known/agent-card.json"
        const val CARD_ROUTE: String = "/api/exports/agent-card.json"
        const val INDEX_PATH: String = "/api/exports"

        /** OpenAI function names are `[A-Za-z0-9_-]{1,64}`; every other character of an MCP tool name becomes `_`. */
        fun openAiName(tool: String): String = tool.map { if (it.isLetterOrDigit() && it.code < 128 || it == '_' || it == '-') it else '_' }
            .joinToString("").take(64).ifEmpty { "_" }

        fun failure(id: Any?, code: Int, message: String): String =
            jsonOf(_m["jsonrpc" j "2.0", "id" j id, "error" j _m["code" j code, "message" j message]])

        /** Where each face is served, relative to the daemon's origin. */
        val INDEX: Map<String, Any?> = _m[
            "openapi" j "/api/exports/openapi.json",
            "typescript" j "/api/exports/client.ts",
            "spacegraph" j "/api/exports/spacegraph",
            "mcp" j "/api/mcp",
            "a2a" j _m["card" j CARD_PATH, "rpc" j A2A_PATH],
            "openai" j _m["models" j "/v1/models", "chat" j "/v1/chat/completions", "tools" j "/api/exports/openai-tools"],
        ]
    }
}
