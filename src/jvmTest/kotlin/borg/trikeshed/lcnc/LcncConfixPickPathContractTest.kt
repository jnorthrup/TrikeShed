package borg.trikeshed.lcnc

import borg.trikeshed.job.*
import borg.trikeshed.kanban.BoardStoreElement
import borg.trikeshed.lib.*
import borg.trikeshed.parse.confix.Syntax
import kotlinx.coroutines.*
import kotlin.test.*

class LcncConfixPickPathContractTest {
    private suspend fun registry(body: suspend (Map<String, LcncNodeRunner>) -> Unit) {
        val store = BoardStoreElement(null, CasStore(), parentJob = currentCoroutineContext()[Job])
        store.open()
        try { body(PureNodes.registry { 0L } + LcncKanbanExperience(store).registry()) }
        finally { store.drain(); assertTrue(store.supervisor.isCompleted) }
    }

    /** The default registered node must actually feed its advertised value through a wire. */
    private suspend fun select(registry: Map<String, LcncNodeRunner>, json: String, path: String): LcncRunner.ScopeResult {
        val program = LcncProgram("kotlin-confix-selection", s_[
            LcncNode("text", "text.value", _m["value" j json]),
            LcncNode("selection", "confix.pickPath", _m["path" j path]),
            LcncNode("result", LcncContracts.SCOPE_OUT, _m["name" j "selected", "kind" j "json"]),
        ], s_[LcncWire("text", "value", "selection", "json?"), LcncWire("selection", "value", "result", "value")])
        return LcncRunner(registry).runProcedure(program)
    }

    @Test fun registeredDefaultProducesAllPortsAndWiresObjectsArraysScalarsFalseAndNull(): Unit = runBlocking {
        withTimeout(10_000) { registry { runners ->
            val json = "{\"obj\":{\"k\":7},\"arr\":[false,null,7],\"flag\":false,\"nil\":null}"
            val cases: Series<Triple<String, String, Any?>> = s_[
                Triple(json, "/obj", _m["k" j 7.0]), Triple(json, "arr", _l[false, null, 7.0]),
                Triple(json, "flag", false), Triple(json, "nil", null), Triple(json, "arr.0", false),
                Triple(json, "/arr/1", null), Triple(json, "arr.2", 7.0),
                Triple("false", "/", false), Triple("null", "", null), Triple("7.5", "/", 7.5),
                Triple("\"é水🙂\"", "", "é水🙂"), Triple("[false,null]", "/", _l[false, null]),
                Triple("{}", "/", emptyMap<String, Any?>()),
            ]
            val ports = checkNotNull(LcncContracts.find("confix.pickPath")).outputs.toSet()
            for ((source, path, expected) in cases.view) {
                val result = select(runners, source, path)
                val selected = result.nodeOutputs.getValue("selection")
                assertEquals(ports, selected.keys)
                assertEquals(true, selected["found"], "$source · $path")
                assertEquals(expected, selected["value"])
                assertTrue(result.returns.containsKey("selected"), "A present JSON null must still cross the wire")
                assertEquals(expected, result.returns["selected"])
                assertEquals("kotlin", selected["geometry"])
                assertEquals(ContentId.of(source.encodeToByteArray()).value, selected["sourceCid"])
                assertNotNull(selected["byteSpan"]); assertNotNull(selected["sheet"])
            }
            for (path in s_["absent", "nil.child", "arr.-1", "arr.9"].view) {
                val missing = select(runners, json, path).nodeOutputs.getValue("selection")
                assertEquals(false, missing["found"])
                assertTrue(missing.containsKey("value")); assertNull(missing["value"])
                assertNull(missing["byteSpan"]); assertNull(missing["sheet"])
                assertEquals(emptyList<Any?>(), missing["sheets"])
                assertEquals(ContentId.of(json.encodeToByteArray()).value, missing["sourceCid"])
            }
        } }
    }

    @Test fun sourceIdentityAndInclusiveSpanReferToOriginalUtf8NotTheSheetProjection(): Unit = runBlocking {
        withTimeout(10_000) { registry { runners ->
            val lexeme = "\"é水🙂 \\\"x\\\"\""
            val json = " \r\n{\"prefix\":\"π🙂\", \"nested\":{\"quote\":$lexeme}}\r\n"
            val selected = select(runners, json, "nested.quote").nodeOutputs.getValue("selection")
            val start = json.indexOf(lexeme)
            val open = json.substring(0, start).encodeToByteArray().size
            val close = open + lexeme.encodeToByteArray().size - 1
            assertTrue(open > start, "The prefix distinguishes byte offsets from UTF-16 offsets")
            assertEquals(_m["open" j open, "close" j close, "unit" j "utf8-byte-inclusive"], selected["byteSpan"])
            assertContentEquals(lexeme.encodeToByteArray(), json.encodeToByteArray().copyOfRange(open, close + 1))
            assertEquals("é水🙂 \"x\"", selected["value"])
            assertEquals(ContentId.of(json.encodeToByteArray()).value, selected["sourceCid"])
            val sheet = selected["sheet"] as Map<*, *>
            assertEquals(_l[_l["value", "é水🙂 \"x\""]], sheet["rows"], "Legacy scalar key/value sheet layout")
            for (literal in s_["false", "null"].view) {
                val scalar = select(runners, "{\"v\":$literal}", "v").nodeOutputs.getValue("selection")
                val rows = (scalar["sheet"] as Map<*, *>)["rows"] as List<*>
                assertEquals(_l["value", if (literal == "false") false else null], rows.single())
            }
            val large = "\"" + "é".repeat(33_000) + "\""
            val result = select(runners, large, "/").nodeOutputs.getValue("selection")
            assertEquals(true, result["found"], "The Kotlin path must not inherit the native 65536-byte scan limit")
            assertEquals("é".repeat(33_000), result["value"])
        } }
    }

    @Test fun nativeMetadataStillSelectsNativeAndCannotFallBackToKotlin(): Unit = runBlocking {
        withTimeout(10_000) { registry { runners ->
            val json = "{\"nil\":null,\"flag\":false}"
            val source = ContentId.of(json.encodeToByteArray()).value
            val rows = LlvmConfixTest.oracle(Syntax.JSON, json.encodeToByteArray())
            val runner = runners.getValue("confix.pickPath")
            val node = LcncNode("selection", "confix.pickPath", _m["json" j json, "path" j "nil"])
            val metadata: Map<String, Any?> = _m["rows?" j rows, "sourceCid?" j source, "ok?" j true, "format?" j "JSON"]
            val result = runner.run(node, metadata)
            assertEquals("native", result["geometry"]); assertEquals(source, result["sourceCid"])
            assertEquals(true, result["found"]); assertNull(result["value"]); assertNotNull(result["byteSpan"])
            assertFails { runner.run(node.copy(params = node.params + _m["geometry" j "native"]), emptyMap()) }
            val invalid = s_[metadata - "rows?", metadata - "sourceCid?", metadata + _m["ok?" j false],
                metadata + _m["format?" j "YAML"], metadata + _m["sourceCid?" j ContentId.of("other".encodeToByteArray()).value],
                metadata + _m["rows?" j emptyList<Any?>()], _m["rows?" j rows]]
            for (inputs in invalid.view) assertFails { runner.run(node.copy(params = node.params + _m["geometry" j "kotlin"]), inputs) }
        } }
    }
}
