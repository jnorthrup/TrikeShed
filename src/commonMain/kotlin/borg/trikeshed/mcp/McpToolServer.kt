package borg.trikeshed.mcp

import borg.trikeshed.parse.jsonOf
import borg.trikeshed.parse.reifyStrict
import kotlinx.coroutines.CancellationException
import kotlin.time.TimeSource

/** A protocol-level fault: surfaces as a JSON-RPC error object with [code]. */
class McpFault(val code: Int, message: String) : Exception(message)

/**
 * A tool-level failure the model should see and may act on. It surfaces as a normal result with `isError: true`,
 * not as a JSON-RPC error: the call was well-formed, the work failed. Only put text here that is safe to show a
 * client; anything else thrown from a tool is reported by class name alone.
 */
class ToolFailure(message: String) : Exception(message)

class McpToolResult(val structured: Map<String, Any?>, val isError: Boolean = false)

class McpTool(
    val name: String,
    val description: String,
    /** JSON Schema subset enforced before the handler runs: type, required, properties, additionalProperties, enum, minimum, maximum, minLength, maxLength, maxItems, items. */
    val inputSchema: Map<String, Any?>,
    /** Advertised as `readOnlyHint`: a tool that changes nothing outside this process's own caches. */
    val readOnly: Boolean = true,
    val handler: suspend (arguments: Map<String, Any?>) -> McpToolResult,
)

/**
 * One finished request, for instrumentation. Deliberately carries no arguments, no results and no error text:
 * what a tool was given and what it said can include commands, document text or credentials, and a log line is
 * the wrong place for any of them.
 */
class McpCallRecord(
    val method: String,
    /** The tool name for `tools/call`; null otherwise. */
    val tool: String?,
    /** `ok`, `tool-error`, `protocol-error:<code>` or `cancelled`. */
    val outcome: String,
    val millis: Long,
)

/**
 * A transport-agnostic MCP tool server: one JSON-RPC 2.0 request document in, one response document out.
 *
 * It owns the protocol and nothing else. Framing belongs to the transport (stdio, a socket under tcpd, an HTTP
 * route); tools belong to whoever registers them; permissions belong to whoever decides which tools to register.
 * A tool that is not registered does not exist: it is absent from `tools/list` and `tools/call` refuses it as
 * unknown, so a read-only deployment is simply one that never registered the mutating tools.
 *
 * Contract (MCP 2025-06-18 core): batching is refused; a request without an `id` is a notification and gets the
 * empty string, never an error; ids are strings or integers exactly representable in a JSON number; `initialize`
 * negotiates DOWN to a revision we speak (the version policy has one owner, [LcncKanbanMcp.negotiateProtocol]);
 * a tool's own failure is a result with `isError`, a malformed call is a protocol error. The reply is always one
 * line of JSON with no raw U+2028/U+2029 either, so any line-oriented reader splits it exactly once.
 *
 * The server is lenient about ordering: it answers the `tools` methods before `initialize` rather than refusing, because a
 * one-shot wrapper has no handshake to offer.
 */
class McpToolServer(
    private val serverName: String,
    private val serverVersion: String,
    tools: List<McpTool>,
    private val instructions: String? = null,
    private val maxRequestChars: Int = 1_048_576,
    private val maxResultChars: Int = 4_194_304,
    private val observer: ((McpCallRecord) -> Unit)? = null,
) {
    private val byName: Map<String, McpTool>

    init {
        require(maxRequestChars > 0 && maxResultChars > 0)
        val names = tools.map { it.name }
        require(names.size == names.toSet().size) { "duplicate tool name" }
        require(names.all { it.isNotBlank() && it.length <= 128 }) { "tool names must be 1..128 characters" }
        byName = tools.associateBy { it.name }
    }

    val toolNames: List<String> get() = byName.keys.toList()

    /** One request in, one response document out. The empty string means "nothing to send" (a notification). */
    suspend fun handle(requestText: String): String {
        val started = TimeSource.Monotonic.markNow()
        var method = "?"
        var tool: String? = null
        var outcome = "ok"
        try {
            if (requestText.length > maxRequestChars) {
                outcome = "protocol-error:$INVALID_REQUEST"
                return errorJson(null, INVALID_REQUEST, "request exceeds $maxRequestChars characters")
            }
            val parsed = runCatching { reifyStrict(requestText) }.getOrNull()
            if (parsed == null) {
                outcome = "protocol-error:$PARSE_ERROR"
                return errorJson(null, PARSE_ERROR, "invalid JSON")
            }
            if (parsed is List<*>) {
                outcome = "protocol-error:$INVALID_REQUEST"
                return errorJson(null, INVALID_REQUEST, "JSON-RPC batching is not supported (removed in MCP 2025-06-18)")
            }
            val req = parsed as? Map<*, *>
            if (req == null) {
                outcome = "protocol-error:$INVALID_REQUEST"
                return errorJson(null, INVALID_REQUEST, "request must be a JSON object")
            }
            if (req["jsonrpc"] != "2.0") {
                outcome = "protocol-error:$INVALID_REQUEST"
                return errorJson(null, INVALID_REQUEST, "jsonrpc must be \"2.0\"")
            }
            val id = req["id"]
            idProblem(id)?.let {
                outcome = "protocol-error:$INVALID_REQUEST"
                return errorJson(null, INVALID_REQUEST, it)
            }
            val asked = req["method"] as? String
            if (asked == null) {
                outcome = "protocol-error:$INVALID_REQUEST"
                return errorJson(id, INVALID_REQUEST, "missing 'method'")
            }
            method = printable(asked)

            // A notification is fire-and-forget: `notifications/initialized` and kin get no document, not even for an unknown name.
            if (id == null) return ""

            val rawParams = req["params"]
            if (rawParams != null && rawParams !is Map<*, *>) {
                outcome = "protocol-error:$INVALID_PARAMS"
                return errorJson(id, INVALID_PARAMS, "params must be an object")
            }
            val params = rawParams as? Map<*, *> ?: emptyMap<Any?, Any?>()
            if (asked == "tools/call") tool = (params["name"] as? String)?.let { printable(it) }

            return try {
                val result = route(asked, params)
                if (asked == "tools/call" && result["isError"] == true) outcome = "tool-error"
                resultJson(id, result)
            } catch (fault: McpFault) {
                outcome = "protocol-error:${fault.code}"
                errorJson(id, fault.code, fault.message ?: "error")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                outcome = "protocol-error:$INTERNAL_ERROR"
                errorJson(id, INTERNAL_ERROR, "internal error: ${failure::class.simpleName}")
            }
        } catch (cancelled: CancellationException) {
            outcome = "cancelled"
            throw cancelled
        } finally {
            if (method != "?" || outcome != "ok") {
                try { observer?.invoke(McpCallRecord(method, tool, outcome, started.elapsedNow().inWholeMilliseconds)) } catch (_: Throwable) {}
            }
        }
    }

    /** Client-controlled text that ends up in a log line: bounded, with control characters neutralised. */
    private fun printable(text: String): String = buildString { for (c in text.take(80)) append(if (c < ' ' || c == '\u007f') '?' else c) }

    private suspend fun route(method: String, params: Map<*, *>): Map<String, Any?> = when (method) {
        "initialize" -> initialize(params)
        "ping" -> emptyMap()
        "tools/list" -> mapOf("tools" to byName.values.map { descriptor(it) })
        "tools/call" -> call(params)
        else -> throw McpFault(METHOD_NOT_FOUND, "method not found: $method")
    }

    private fun initialize(params: Map<*, *>): Map<String, Any?> {
        val asked = params["protocolVersion"] as? String
        val result = linkedMapOf<String, Any?>(
            "protocolVersion" to LcncKanbanMcp.negotiateProtocol(asked),
            "capabilities" to mapOf("tools" to mapOf("listChanged" to false)),
            "serverInfo" to mapOf("name" to serverName, "version" to serverVersion),
        )
        instructions?.let { result["instructions"] = it }
        return result
    }

    private fun descriptor(tool: McpTool): Map<String, Any?> = mapOf(
        "name" to tool.name,
        "description" to tool.description,
        "inputSchema" to tool.inputSchema,
        "annotations" to mapOf("readOnlyHint" to tool.readOnly),
    )

    private suspend fun call(params: Map<*, *>): Map<String, Any?> {
        val name = params["name"] as? String ?: throw McpFault(INVALID_PARAMS, "missing tool 'name'")
        val tool = byName[name] ?: throw McpFault(INVALID_PARAMS, "unknown tool: ${name.take(128)}")
        val rawArguments = params["arguments"]
        if (rawArguments != null && rawArguments !is Map<*, *>) throw McpFault(INVALID_PARAMS, "arguments must be an object")
        @Suppress("UNCHECKED_CAST")
        val arguments = (rawArguments as? Map<String, Any?>) ?: emptyMap()
        ToolArguments.validate(tool.inputSchema, arguments)?.let { throw McpFault(INVALID_PARAMS, "invalid arguments for $name: $it") }

        val result = try {
            tool.handler(arguments)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: ToolFailure) {
            McpToolResult(mapOf("error" to (failure.message ?: "tool failed")), isError = true)
        } catch (failure: Throwable) {
            // Never copy an arbitrary exception message to the client: it may carry command text or credentials.
            McpToolResult(mapOf("error" to "tool failed: ${failure::class.simpleName}"), isError = true)
        }
        @Suppress("UNCHECKED_CAST")
        val structured = JsonSafe.finite(result.structured) as Map<String, Any?>
        val text = jsonOf(structured)
        if (text.length > maxResultChars) {
            return callEnvelope(McpToolResult(mapOf("error" to "result exceeds $maxResultChars characters"), isError = true))
        }
        return callEnvelope(McpToolResult(structured, result.isError), text)
    }

    private fun callEnvelope(result: McpToolResult, text: String = jsonOf(result.structured)): Map<String, Any?> = mapOf(
        "content" to listOf(mapOf("type" to "text", "text" to text)),
        "structuredContent" to result.structured,
        "isError" to result.isError,
    )

    // ── JSON-RPC envelope ─────────────────────────────────────────────

    private fun resultJson(id: Any?, result: Map<String, Any?>): String =
        singleLine("""{"jsonrpc":"2.0","id":${idJson(id)},"result":${jsonOf(JsonSafe.finite(result))}}""")

    private fun errorJson(id: Any?, code: Int, message: String): String = errorDocument(id, code, message)

    companion object {
        /** A complete single-line JSON-RPC error document; [id] null means the request had no usable id. */
        fun errorDocument(id: Any?, code: Int, message: String): String =
            singleLine("""{"jsonrpc":"2.0","id":${idJson(id)},"error":${jsonOf(mapOf("code" to code, "message" to message))}}""")

        /**
         * Framing guard. `jsonOf` already escapes every control character below U+0020, so a raw line break can only
         * come from U+2028/U+2029, which JSON allows inside strings but several line readers treat as terminators.
         * They can only occur inside a string here, so escaping them is lossless. Anything still containing a break
         * after that is refused rather than sent, because one reply that becomes two would desynchronise the stream.
         */
        internal fun singleLine(document: String): String {
            val escaped = if (document.indexOf('\u2028') < 0 && document.indexOf('\u2029') < 0) document
            else document.replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
            if (escaped.indexOf('\n') < 0 && escaped.indexOf('\r') < 0) return escaped
            return """{"jsonrpc":"2.0","id":null,"error":{"code":$INTERNAL_ERROR,"message":"internal error: reply was not single-line"}}"""
        }

        internal fun idJson(id: Any?): String = when (id) {
            null -> "null"
            is Number -> id.toDouble().toLong().toString()
            else -> jsonOf(id)
        }

        const val PARSE_ERROR: Int = LcncKanbanMcp.PARSE_ERROR
        const val INVALID_REQUEST: Int = LcncKanbanMcp.INVALID_REQUEST
        const val METHOD_NOT_FOUND: Int = LcncKanbanMcp.METHOD_NOT_FOUND
        const val INVALID_PARAMS: Int = LcncKanbanMcp.INVALID_PARAMS
        const val INTERNAL_ERROR: Int = LcncKanbanMcp.INTERNAL_ERROR

        /** The largest integer a JSON number (an IEEE double) holds exactly; beyond it an id cannot be echoed faithfully. */
        private const val MAX_EXACT_ID: Double = 9_007_199_254_740_991.0

        /** Null when [id] is a legal request id (absent, a string, or an exactly-representable integer), otherwise why not. */
        fun idProblem(id: Any?): String? = when (id) {
            null, is String -> null
            is Number -> {
                val d = id.toDouble()
                when {
                    !d.isFinite() || d != kotlin.math.floor(d) -> "id must be a string or an integer"
                    kotlin.math.abs(d) > MAX_EXACT_ID -> "numeric id is not exactly representable; use a string"
                    else -> null
                }
            }
            else -> "id must be a string or an integer"
        }

        /** A canonical key for an id, so `7`, `7.0` and a cancellation's `requestId` of `7` all name one request. */
        fun idKey(id: Any?): String? = when {
            id is String -> "s:$id"
            id is Number && idProblem(id) == null -> "n:${id.toDouble().toLong()}"
            else -> null
        }
    }
}

/**
 * `jsonOf` writes a non-finite number as bare `NaN`/`Infinity`, which is not JSON: one such value would make a whole
 * reply unparseable. The protocol layer is the last serializer, so it is where that is made impossible: a
 * non-finite number becomes `null`.
 */
internal object JsonSafe {
    fun finite(value: Any?): Any? = when (value) {
        is Double -> if (value.isFinite()) value else null
        is Float -> if (value.isFinite()) value else null
        is Map<*, *> -> value.entries.associate { (k, v) -> k to finite(v) }
        is Iterable<*> -> value.map { finite(it) }
        is Array<*> -> value.map { finite(it) }
        else -> value
    }
}

/**
 * The JSON Schema subset tool inputs are held to before any handler runs. Returns null when the value conforms,
 * otherwise the first violation with its path. Unknown keywords are ignored; unknown PROPERTIES are rejected
 * whenever the schema says `additionalProperties: false`, which every registered tool does.
 */
internal object ToolArguments {
    fun validate(schema: Map<String, Any?>, value: Any?, path: String = "arguments"): String? {
        val type = schema["type"] as? String
        when (type) {
            "string" -> {
                if (value !is String) return "$path must be a string"
                (schema["minLength"] as? Number)?.let { if (value.length < it.toInt()) return "$path is shorter than ${it.toInt()}" }
                (schema["maxLength"] as? Number)?.let { if (value.length > it.toInt()) return "$path is longer than ${it.toInt()}" }
            }
            "boolean" -> if (value !is Boolean) return "$path must be a boolean"
            "integer", "number" -> {
                if (value !is Number) return "$path must be a $type"
                val d = value.toDouble()
                if (!d.isFinite()) return "$path must be finite"
                if (type == "integer" && d != kotlin.math.floor(d)) return "$path must be an integer"
                (schema["minimum"] as? Number)?.let { if (d < it.toDouble()) return "$path is below ${it.toInt()}" }
                (schema["maximum"] as? Number)?.let { if (d > it.toDouble()) return "$path is above ${it.toInt()}" }
            }
            "array" -> {
                if (value !is List<*>) return "$path must be an array"
                (schema["maxItems"] as? Number)?.let { if (value.size > it.toInt()) return "$path has more than ${it.toInt()} items" }
                @Suppress("UNCHECKED_CAST")
                val items = schema["items"] as? Map<String, Any?>
                if (items != null) value.forEachIndexed { i, element -> validate(items, element, "$path[$i]")?.let { return it } }
            }
            "object" -> {
                if (value !is Map<*, *>) return "$path must be an object"
                @Suppress("UNCHECKED_CAST")
                val properties = (schema["properties"] as? Map<String, Any?>) ?: emptyMap()
                (schema["required"] as? List<*>)?.forEach { name ->
                    if (name is String && (!value.containsKey(name) || value[name] == null)) return "$path.$name is required"
                }
                if (schema["additionalProperties"] == false) {
                    value.keys.forEach { key -> if (key !in properties) return "$path.$key is not a known property" }
                }
                for ((key, propertySchema) in properties) {
                    if (value.containsKey(key) && value[key] != null) {
                        @Suppress("UNCHECKED_CAST")
                        validate(propertySchema as Map<String, Any?>, value[key], "$path.$key")?.let { return it }
                    }
                }
            }
            null -> {}
            else -> return "$path has unsupported schema type $type"
        }
        (schema["enum"] as? List<*>)?.let { allowed -> if (value !in allowed) return "$path must be one of $allowed" }
        return null
    }
}
