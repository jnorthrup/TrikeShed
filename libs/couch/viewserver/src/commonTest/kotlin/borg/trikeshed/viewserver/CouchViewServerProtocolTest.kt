package borg.trikeshed.viewserver

import kotlin.test.Test
import kotlin.test.assertTrue

class CouchViewServerProtocolTest {

    // 9a — reset command clears state and returns true
    @Test
    fun `reset command clears state and returns true`() {
        val server = CouchQueryServer { source ->
            object : CompiledFunction {
                override fun map(doc: Map<CharSequence, Any?>, emit: (key: Any?, value: Any?) -> Unit) {}
                override fun reduce(sources: List<CharSequence>, values: List<Any?>, rereduce: Boolean): Any? = null
            }
        }
        val response = server.handle(CouchCommand.Reset)
        assertTrue(response is CouchResponse.True)
    }

    // 9b — add_fun and map_doc emits key-value pairs
    @Test
    fun `add_fun and map_doc emits key-value pairs`() {
        val server = CouchQueryServer { source ->
            object : CompiledFunction {
                override fun map(doc: Map<CharSequence, Any?>, emit: (key: Any?, value: Any?) -> Unit) {
                    emit(doc["_id"], 1)
                }
                override fun reduce(sources: List<CharSequence>, values: List<Any?>, rereduce: Boolean): Any? = null
            }
        }
        server.handle(CouchCommand.Reset)
        server.handle(CouchCommand.AddFun("function(doc){emit(doc._id,1)}"))
        val response = server.handle(CouchCommand.MapDoc(mapOf("_id" to "doc1")))
        assertTrue(response is CouchResponse.MapResults)
    }
}
