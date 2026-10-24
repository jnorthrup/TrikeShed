package borg.trikeshed.mcp

import borg.trikeshed.parse.jsonOf
import borg.trikeshed.parse.reifyStrict
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class McpToolServerTest {
    private val echo = McpTool(
        name = "echo",
        description = "returns its message",
        inputSchema = mapOf(
            "type" to "object",
            "properties" to mapOf("message" to mapOf("type" to "string", "maxLength" to 50), "times" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 3)),
            "required" to listOf("message"),
            "additionalProperties" to false,
        ),
    ) { args -> McpToolResult(mapOf("echo" to args["message"], "times" to (args["times"] ?: 1))) }

    private val boom = McpTool("boom", "throws with a secret in the message", emptySchema()) { throw IllegalStateException("secret-token-123 leaked") }
    private val refuse = McpTool("refuse", "reports a tool failure", emptySchema(), readOnly = false) { throw ToolFailure("not allowed: exec is disabled") }
    private val big = McpTool("big", "returns a large string", emptySchema()) { McpToolResult(mapOf("blob" to "x".repeat(500))) }
    private val cancelled = McpTool("cancelled", "is cancelled", emptySchema()) { throw CancellationException("owner cancelled") }

    private fun emptySchema() = mapOf("type" to "object", "properties" to emptyMap<String, Any?>(), "additionalProperties" to false)

    private fun server(maxRequestChars: Int = 10_000, maxResultChars: Int = 10_000, tools: List<McpTool> = listOf(echo, boom, refuse, big, cancelled)) =
        McpToolServer("test-server", "9.9.9", tools, instructions = "use the echo tool", maxRequestChars = maxRequestChars, maxResultChars = maxResultChars)

    private fun request(id: Any?, method: String, params: Any? = null): String =
        jsonOf(buildMap { put("jsonrpc", "2.0"); put("id", id); put("method", method); if (params != null) put("params", params) })

    private suspend fun McpToolServer.rpc(text: String): Map<*, *> = reifyStrict(handle(text)) as Map<*, *>

    private fun Map<*, *>.error(): Map<*, *> = this["error"] as Map<*, *>
    private fun Map<*, *>.result(): Map<*, *> = this["result"] as Map<*, *>

    @Test
    fun initializeNegotiatesDownAndDescribesTheServer() = runTest {
        val s = server()
        val ok = s.rpc(request(1, "initialize", mapOf("protocolVersion" to "2025-03-26"))).result()
        assertEquals("2025-03-26", ok["protocolVersion"])
        assertEquals("test-server", (ok["serverInfo"] as Map<*, *>)["name"])
        assertEquals("9.9.9", (ok["serverInfo"] as Map<*, *>)["version"])
        assertTrue((ok["capabilities"] as Map<*, *>).containsKey("tools"))
        assertEquals("use the echo tool", ok["instructions"])

        // A revision from beyond our horizon is answered with the newest one we speak that is not newer; none asked gets the baseline.
        assertEquals("2025-06-18", s.rpc(request(2, "initialize", mapOf("protocolVersion" to "2025-11-25"))).result()["protocolVersion"])
        assertEquals("2025-06-18", s.rpc(request(3, "initialize", emptyMap<String, Any?>())).result()["protocolVersion"])
    }

    @Test
    fun notificationsGetNoResponseEvenWhenUnknown() = runTest {
        val s = server()
        assertEquals("", s.handle("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
        assertEquals("", s.handle("""{"jsonrpc":"2.0","method":"no/such/method"}"""))
        assertEquals("", s.handle("""{"jsonrpc":"2.0","method":"tools/call","params":{"name":"echo","arguments":{"message":"x"}}}"""), "no id means no execution reply")
    }

    @Test
    fun pingAnswersAnEmptyObject() = runTest {
        val r = server().rpc(request("p-1", "ping"))
        assertEquals("p-1", r["id"])
        assertEquals(emptyMap<Any?, Any?>(), r.result())
    }

    @Test
    fun toolsListDescribesEveryRegisteredToolAndNothingElse() = runTest {
        val listed = server().rpc(request(1, "tools/list")).result()["tools"] as List<*>
        assertEquals(listOf("echo", "boom", "refuse", "big", "cancelled"), listed.map { (it as Map<*, *>)["name"] })
        val first = listed[0] as Map<*, *>
        assertEquals("returns its message", first["description"])
        assertEquals("object", (first["inputSchema"] as Map<*, *>)["type"])
        assertEquals(true, (first["annotations"] as Map<*, *>)["readOnlyHint"])
        assertEquals(false, ((listed[2] as Map<*, *>)["annotations"] as Map<*, *>)["readOnlyHint"])

        val bare = McpToolServer("s", "1", emptyList())
        assertEquals(emptyList<Any?>(), bare.rpc(request(1, "tools/list")).result()["tools"])
    }

    @Test
    fun aToolThatIsNotRegisteredDoesNotExist() = runTest {
        val readOnly = server(tools = listOf(echo))
        assertFalse("refuse" in readOnly.toolNames)
        val r = readOnly.rpc(request(1, "tools/call", mapOf("name" to "refuse", "arguments" to emptyMap<String, Any?>())))
        assertEquals(McpToolServer.INVALID_PARAMS, (r.error()["code"] as Number).toInt())
        assertTrue((r.error()["message"] as String).contains("unknown tool"))
    }

    @Test
    fun callReturnsContentAndStructuredContent() = runTest {
        val r = server().rpc(request(5, "tools/call", mapOf("name" to "echo", "arguments" to mapOf("message" to "hello", "times" to 2)))).result()
        assertEquals(false, r["isError"])
        val structured = r["structuredContent"] as Map<*, *>
        assertEquals("hello", structured["echo"])
        val content = (r["content"] as List<*>).single() as Map<*, *>
        assertEquals("text", content["type"])
        assertEquals(structured, reifyStrict(content["text"] as String), "the text block is the same document as structuredContent")
    }

    @Test
    fun aToolFailureIsAResultAndAnUnexpectedErrorNeverLeaksItsMessage() = runTest {
        val s = server()
        val refused = s.rpc(request(1, "tools/call", mapOf("name" to "refuse"))).result()
        assertEquals(true, refused["isError"])
        assertEquals("not allowed: exec is disabled", (refused["structuredContent"] as Map<*, *>)["error"])

        val raw = s.handle(request(2, "tools/call", mapOf("name" to "boom")))
        val exploded = (reifyStrict(raw) as Map<*, *>).result()
        assertEquals(true, exploded["isError"])
        assertEquals("tool failed: IllegalStateException", (exploded["structuredContent"] as Map<*, *>)["error"])
        assertFalse(raw.contains("secret-token-123"), "an arbitrary exception message must not reach the client")
    }

    @Test
    fun malformedCallsAreProtocolErrors() = runTest {
        val s = server()
        fun code(r: Map<*, *>) = (r.error()["code"] as Number).toInt()
        suspend fun call(params: Any?) = s.rpc(request(1, "tools/call", params))

        assertEquals(McpToolServer.INVALID_PARAMS, code(call(emptyMap<String, Any?>())), "no name")
        assertEquals(McpToolServer.INVALID_PARAMS, code(call(mapOf("name" to "nope"))), "unknown tool")
        assertEquals(McpToolServer.INVALID_PARAMS, code(call(mapOf("name" to "echo", "arguments" to listOf(1)))), "arguments must be an object")
        assertEquals(McpToolServer.INVALID_PARAMS, code(call(mapOf("name" to "echo", "arguments" to emptyMap<String, Any?>()))), "missing required")
        assertEquals(McpToolServer.INVALID_PARAMS, code(call(mapOf("name" to "echo", "arguments" to mapOf("message" to 5)))), "wrong type")
        assertEquals(McpToolServer.INVALID_PARAMS, code(call(mapOf("name" to "echo", "arguments" to mapOf("message" to "x", "extra" to 1)))), "unknown property")
        assertEquals(McpToolServer.INVALID_PARAMS, code(call(mapOf("name" to "echo", "arguments" to mapOf("message" to "x".repeat(51))))), "too long")
        assertEquals(McpToolServer.INVALID_PARAMS, code(call(mapOf("name" to "echo", "arguments" to mapOf("message" to "x", "times" to 4)))), "above maximum")
        assertEquals(McpToolServer.INVALID_PARAMS, code(call(mapOf("name" to "echo", "arguments" to mapOf("message" to "x", "times" to 1.5)))), "not an integer")
        assertTrue((call(mapOf("name" to "echo", "arguments" to mapOf("message" to 5))).error()["message"] as String).contains("arguments.message"), "the path is reported")
    }

    @Test
    fun malformedEnvelopesAreRejected() = runTest {
        val s = server(maxRequestChars = 200)
        suspend fun code(text: String): Int = ((reifyStrict(s.handle(text)) as Map<*, *>).error()["code"] as Number).toInt()

        assertEquals(McpToolServer.PARSE_ERROR, code("{not json"))
        assertEquals(McpToolServer.INVALID_REQUEST, code("""[{"jsonrpc":"2.0","id":1,"method":"ping"}]"""), "batching is refused")
        assertEquals(McpToolServer.INVALID_REQUEST, code("42"))
        assertEquals(McpToolServer.INVALID_REQUEST, code("""{"jsonrpc":"2.0","id":1}"""), "no method")
        assertEquals(McpToolServer.INVALID_REQUEST, code("""{"jsonrpc":"1.0","id":1,"method":"ping"}"""))
        assertEquals(McpToolServer.INVALID_REQUEST, code("""{"id":1,"method":"ping"}"""))
        assertEquals(McpToolServer.INVALID_REQUEST, code("""{"jsonrpc":"2.0","id":{"a":1},"method":"ping"}"""))
        assertEquals(McpToolServer.INVALID_PARAMS, code("""{"jsonrpc":"2.0","id":1,"method":"ping","params":[1]}"""))
        assertEquals(McpToolServer.METHOD_NOT_FOUND, code("""{"jsonrpc":"2.0","id":1,"method":"resources/list"}"""))
        assertEquals(McpToolServer.INVALID_REQUEST, code("""{"jsonrpc":"2.0","id":1,"method":"ping","pad":"${"x".repeat(300)}"}"""), "over the request limit")
    }

    @Test
    fun idsAreEchoedExactlyOrRefused() = runTest {
        val s = server()
        assertTrue(s.handle(request(7, "ping")).contains(""""id":7,"""), "an integral id goes back as 7, not 7.0")
        assertTrue(s.handle(request(-5, "ping")).contains(""""id":-5,"""))
        assertTrue(s.handle(request("a-1", "ping")).contains(""""id":"a-1""""))
        assertTrue(s.handle("""{"jsonrpc":"2.0","id":9007199254740991,"method":"ping"}""").contains(""""id":9007199254740991,"""), "the largest exactly-representable id survives")

        // MCP ids are strings or integers. Anything we could not echo faithfully is refused, not silently altered.
        for (bad in listOf("1.5", "9007199254740993", "1e400", "true", """{"a":1}""", "[1]")) {
            val reply = s.handle("""{"jsonrpc":"2.0","id":$bad,"method":"ping"}""")
            assertTrue(reply.contains(""""id":null"""), "no usable id to echo for $bad")
            assertEquals(McpToolServer.INVALID_REQUEST, ((reifyStrict(reply) as Map<*, *>).error()["code"] as Number).toInt(), "id $bad")
        }
        assertTrue(s.handle("{not json").contains(""""id":null"""), "an unparseable request has no id to echo")
    }

    @Test
    fun idKeysUnifyEquivalentIdsAndSeparateTypes() {
        assertEquals(McpToolServer.idKey(7.0), McpToolServer.idKey(7))
        assertEquals("n:7", McpToolServer.idKey(7.0))
        assertEquals("s:7", McpToolServer.idKey("7"))
        assertTrue(McpToolServer.idKey("7") != McpToolServer.idKey(7), "a string id and a number id are different requests")
        assertNull(McpToolServer.idKey(1.5))
        assertNull(McpToolServer.idKey(null))
        assertNull(McpToolServer.idKey(9007199254740993.0))
    }

    @Test
    fun nonFiniteNumbersNeverReachTheWire() = runTest {
        val bad = McpTool("nan", "returns non-finite numbers", emptySchema()) {
            McpToolResult(mapOf("score" to Double.NaN, "more" to listOf(1.0, Double.POSITIVE_INFINITY, 2), "nested" to mapOf("x" to Float.NEGATIVE_INFINITY, "ok" to 0.5)))
        }
        val raw = server(tools = listOf(bad)).handle(request(1, "tools/call", mapOf("name" to "nan")))
        assertFalse(raw.contains("NaN") || raw.contains("Infinity"), raw)
        val structured = ((reifyStrict(raw) as Map<*, *>).result()["structuredContent"]) as Map<*, *>
        assertNull(structured["score"])
        assertEquals(listOf(1.0, null, 2.0), structured["more"])
        assertNull((structured["nested"] as Map<*, *>)["x"])
        assertEquals(0.5, (structured["nested"] as Map<*, *>)["ok"])
    }

    @Test
    fun theObserverSeesMethodToolAndOutcomeButNeverArgumentsOrResults() = runTest {
        val seen = ArrayList<McpCallRecord>()
        val s = McpToolServer("s", "1", listOf(echo, boom, refuse), observer = { seen.add(it) })
        s.handle(request(1, "ping"))
        s.handle(request(2, "tools/call", mapOf("name" to "echo", "arguments" to mapOf("message" to "SECRET-ARGUMENT"))))
        s.handle(request(3, "tools/call", mapOf("name" to "refuse")))
        s.handle(request(4, "tools/call", mapOf("name" to "nope")))
        s.handle(request(5, "no/such"))
        s.handle("{not json")
        s.handle("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")

        assertEquals(
            listOf("ping:null:ok", "tools/call:echo:ok", "tools/call:refuse:tool-error", "tools/call:nope:protocol-error:-32602", "no/such:null:protocol-error:-32601", "?:null:protocol-error:-32700", "notifications/initialized:null:ok"),
            seen.map { "${it.method}:${it.tool}:${it.outcome}" },
        )
        assertTrue(seen.all { it.millis >= 0 })
        assertFalse(seen.joinToString { "${it.method}${it.tool}${it.outcome}" }.contains("SECRET-ARGUMENT"))
    }

    @Test
    fun aThrowingObserverCannotBreakAReply() = runTest {
        val s = McpToolServer("s", "1", listOf(echo), observer = { throw IllegalStateException("observer broke") })
        assertEquals(emptyMap<Any?, Any?>(), s.rpc(request(1, "ping")).result())
    }

    @Test
    fun cancellationIsRecordedAsCancelled() = runTest {
        val seen = ArrayList<McpCallRecord>()
        val s = McpToolServer("s", "1", listOf(cancelled), observer = { seen.add(it) })
        assertFailsWith<CancellationException> { s.handle(request(1, "tools/call", mapOf("name" to "cancelled"))) }
        assertEquals(listOf("cancelled"), seen.map { it.outcome })
    }

    @Test
    fun repliesAreAlwaysSingleLine() = runTest {
        val awkward = "line1\nline2\r\n\ttabbed \"quoted\" back\\slash \u2028 \u0000 \u001f unicode é 😀"
        val echoText = McpTool("text", "returns awkward text", emptySchema()) { McpToolResult(mapOf("text" to awkward, "nested" to listOf(awkward))) }
        val s = server(tools = listOf(echoText))
        val raw = s.handle(request(1, "tools/call", mapOf("name" to "text")))
        assertFalse(raw.contains('\n') || raw.contains('\r'), "a raw line break would split one reply into two on a newline-delimited transport")
        assertFalse(raw.contains('\u2028') || raw.contains('\u2029'), "some line readers split on U+2028/U+2029 as well")
        val back = ((reifyStrict(raw) as Map<*, *>).result()["structuredContent"] as Map<*, *>)["text"]
        assertEquals(awkward, back, "and the text survives the round trip")
        assertFalse(s.handle(request("id\nwith newline", "ping")).contains('\n'))
        assertFalse(s.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"no\\nnewline\"}").contains('\n'))
    }

    @Test
    fun oversizeResultsBecomeErrorsNotHugeReplies() = runTest {
        val s = server(maxResultChars = 100)
        val r = s.rpc(request(1, "tools/call", mapOf("name" to "big"))).result()
        assertEquals(true, r["isError"])
        assertTrue(((r["structuredContent"] as Map<*, *>)["error"] as String).contains("exceeds 100"))
        assertTrue(s.handle(request(2, "tools/call", mapOf("name" to "big"))).length < 400)
    }

    @Test
    fun cancellationPropagatesOutOfTheServer() = runTest {
        val thrown = assertFailsWith<CancellationException> { server().handle(request(1, "tools/call", mapOf("name" to "cancelled"))) }
        assertEquals("owner cancelled", thrown.message)
    }

    @Test
    fun duplicateOrUnnamedToolsAreRejectedAtConstruction() {
        assertFailsWith<IllegalArgumentException> { McpToolServer("s", "1", listOf(echo, echo)) }
        assertFailsWith<IllegalArgumentException> { McpToolServer("s", "1", listOf(McpTool(" ", "d", emptySchema()) { McpToolResult(emptyMap()) })) }
    }

    @Test
    fun theSchemaSubsetCoversNestedObjectsArraysAndEnums() {
        val schema: Map<String, Any?> = mapOf(
            "type" to "object",
            "required" to listOf("docs"),
            "additionalProperties" to false,
            "properties" to mapOf(
                "docs" to mapOf(
                    "type" to "array", "maxItems" to 2,
                    "items" to mapOf(
                        "type" to "object", "required" to listOf("name"), "additionalProperties" to false,
                        "properties" to mapOf("name" to mapOf("type" to "string", "minLength" to 1), "mode" to mapOf("type" to "string", "enum" to listOf("a", "b"))),
                    ),
                ),
                "flag" to mapOf("type" to "boolean"),
            ),
        )
        fun check(v: Any?) = ToolArguments.validate(schema, v)

        assertNull(check(mapOf("docs" to listOf(mapOf("name" to "n", "mode" to "a")), "flag" to true)))
        assertTrue(check(mapOf("docs" to listOf(mapOf("name" to "n"), mapOf("name" to "m"), mapOf("name" to "o"))))!!.contains("more than 2"))
        assertTrue(check(mapOf("docs" to listOf(mapOf("name" to "")))) !!.contains("arguments.docs[0].name"))
        assertTrue(check(mapOf("docs" to listOf(mapOf("name" to "n", "mode" to "z"))))!!.contains("one of"))
        assertTrue(check(mapOf("docs" to listOf(mapOf("name" to "n", "color" to 1))))!!.contains("not a known property"))
        assertTrue(check(mapOf("docs" to "nope"))!!.contains("must be an array"))
        assertTrue(check(mapOf("flag" to true))!!.contains("arguments.docs is required"))
        assertTrue(check(mapOf("docs" to emptyList<Any?>(), "flag" to "yes"))!!.contains("must be a boolean"))
        assertTrue(check("not an object")!!.contains("must be an object"))
    }
}
