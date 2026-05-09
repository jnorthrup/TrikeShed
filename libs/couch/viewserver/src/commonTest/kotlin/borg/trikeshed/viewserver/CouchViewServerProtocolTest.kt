package borg.trikeshed.viewserver

import borg.trikeshed.lib.j
import borg.trikeshed.parse.confix.Combinators
import borg.trikeshed.parse.confix.Syntax
import borg.trikeshed.parse.confix.contextOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import borg.trikeshed.viewserver.JsonSerializer

class CouchViewServerProtocolTest {

    private fun parseJsonLine(line: String): List<*> {
        val ctx = contextOf(Syntax.JSON, line.toSeries())
        @Suppress("UNCHECKED_CAST")
        return Combinators.reify(ctx) as List<*>
    }

    // ── Compilers ─────────────────────────────────────────────────────

    private fun countingCompiler(): (String) -> CompiledFunction {
        val counts = mutableMapOf<String, Int>()
        return { source ->
            val fn = object : CompiledFunction {
                override fun map(doc: Map<String, Any?>, emit: (key: Any?, value: Any?) -> Unit) {
                    val count = counts[source] ?: 0
                    counts[source] = count + 1
                    val id = doc["_id"] as? String ?: "unknown"
                    emit(id, count)
                }
                override fun reduce(sources: List<String>, values: List<Any?>, rereduce: Boolean): Any? {
                    return values.filterNotNull().sumOf { (it as Number).toInt() }
                }
            }
            fn
        }
    }

    // ── Tests ─────────────────────────────────────────────────────────

    @Test
    fun `reset command clears state and returns true`() {
        val server = CouchQueryServer(countingCompiler())
        val cmd = CouchCommand.parse(parseJsonLine("""["reset"]"""))
        val response = server.handle(cmd!!)
        assertTrue(response is CouchResponse.True)
    }

    @Test
    fun `add_fun accepts valid JS function`() {
        val server = CouchQueryServer(countingCompiler())
        server.handle(CouchCommand.Reset)

        val cmd = CouchCommand.parse(parseJsonLine(
            """["add_fun","function(doc){emit(doc._id,1)}"]"""
        ))
        val response = server.handle(cmd!!)
        assertTrue(response is CouchResponse.True)
    }

    @Test
    fun `map_doc emits key-value pairs`() {
        val server = CouchQueryServer(countingCompiler())
        server.handle(CouchCommand.Reset)

        server.handle(CouchCommand.parse(parseJsonLine(
            """["add_fun","function(doc){emit(doc._id,1)}"]"""
        ))!!)

        val cmd = CouchCommand.parse(parseJsonLine(
            """["map_doc",{"_id":"doc1","value":42}]"""
        ))
        val response = server.handle(cmd!!)
        assertTrue(response is CouchResponse.MapResults)
        val results = response as CouchResponse.MapResults
        assertEquals(1, results.perFunction.size)
        assertEquals(1, results.perFunction[0].size)
        assertEquals("doc1", results.perFunction[0][0][0])
        assertEquals(0, results.perFunction[0][0][1])  // first emit = count 0
    }

    @Test
    fun `map_doc across multiple functions`() {
        val server = CouchQueryServer(countingCompiler())
        server.handle(CouchCommand.Reset)

        server.handle(CouchCommand.AddFun("function(doc){emit(doc._id,1)}"))
        server.handle(CouchCommand.AddFun("function(doc){emit(doc.value,doc._id)}"))

        val response = server.handle(CouchCommand.MapDoc(mapOf("_id" to "x", "value" to 42)))
        assertTrue(response is CouchResponse.MapResults)
        val results = response as CouchResponse.MapResults
        assertEquals(2, results.perFunction.size)
    }

    @Test
    fun `reduce returns result array`() {
        val server = CouchQueryServer(countingCompiler())
        server.handle(CouchCommand.Reset)
        server.handle(CouchCommand.AddFun("function(doc){emit(doc._id,1)}"))

        val response = server.handle(CouchCommand.Reduce(
            sources = listOf("function(keys,values,rereduce){return sum(values)}"),
            values = listOf(1, 2, 3),
        ))
        assertTrue(response is CouchResponse.ReduceResult)
    }

    @Test
    fun `protocol round-trip: reset add map serialize`() {
        val server = CouchQueryServer(countingCompiler())

        // reset
        val r1 = JsonSerializer.serialize(server.handle(CouchCommand.Reset))
        assertEquals("true", r1)

        // add_fun
        val r2 = JsonSerializer.serialize(server.handle(
            CouchCommand.AddFun("function(doc){emit(doc._id,1)}")
        ))
        assertEquals("true", r2)

        // map_doc
        val r3 = server.handle(CouchCommand.MapDoc(mapOf("_id" to "abc")))
        val serialized = JsonSerializer.serialize(r3)
        assertTrue(serialized.startsWith("[["))
        assertTrue(serialized.contains("abc"))
    }

    @Test
    fun `error on bad command`() {
        val server = CouchQueryServer(countingCompiler())
        val response = server.handle(CouchCommand.AddFun("not valid js {{{"))
        assertTrue(response is CouchResponse.Error)
    }

    private fun Map<String, Any?>.toJsonString(): String {
        val sb = StringBuilder("{")
        entries.forEachIndexed { i, (k, v) ->
            if (i > 0) sb.append(",")
            sb.append("\"$k\":")
            sb.append(JsonSerializer.serializeValue(v))
        }
        sb.append("}")
        return sb.toString()
    }

    private fun String.toSeries(): borg.trikeshed.lib.Series<Char> {
        val n = length
        return n j { i: Int -> this[i] }
    }
}
