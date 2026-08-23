package borg.trikeshed.forge.server

import borg.trikeshed.litebike.JvmKanbanServer
import borg.trikeshed.parse.json.JsonSupport
import borg.trikeshed.vm.HypervisorVmHost
import borg.trikeshed.vm.VM_COLUMNS
import borg.trikeshed.vm.VmHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `/api/vm/…` at route level (no socket), and the same routes reached through JvmKanbanServer's extension seam. */
class VmWireTest {
    private fun http(method: String, path: String, body: String = ""): String =
        "$method $path HTTP/1.1\r\nHost: x\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\n\r\n$body"

    @Suppress("UNCHECKED_CAST")
    private fun obj(json: String) = JsonSupport.parse(json) as Map<String, Any?>
    private fun list(x: Any?): List<*> = (x as? List<*>) ?: (x as Array<*>).toList()

    @Test
    fun spawnEvalRevokeRoundTripOverTheWire() = runBlocking {
        val host = HypervisorVmHost()
        val wire = VmWire(host, CoroutineScope(Dispatchers.Default))
        try {
            val empty = obj(wire.route("GET", "/api/vm", http("GET", "/api/vm"), null)!!.body)
            assertEquals(VM_COLUMNS.map { it.first }, list(empty["columns"]).map { (it as Map<*, *>)["name"] })
            assertEquals(0, list(empty["rows"]).size)

            val spawned = wire.route("POST", "/api/vm/spawn", http("POST", "/api/vm/spawn", """{"id":"a","facet":"js"}"""), null)!!
            assertEquals(200, spawned.status)
            assertEquals("in-process", obj(spawned.body)["tier"])

            val evaluated = wire.route("POST", "/api/vm/a/eval", http("POST", "/api/vm/a/eval", """{"source":"6*7"}"""), null)!!
            assertEquals(200, evaluated.status, evaluated.body)
            assertTrue(evaluated.body.contains("\"value\":42"), evaluated.body)

            val rows = list(obj(wire.route("GET", "/api/vm", http("GET", "/api/vm"), null)!!.body)["rows"])
            assertEquals(1, rows.size)
            assertEquals("a", list(rows[0])[0])

            assertEquals(404, wire.route("POST", "/api/vm/zz/eval", http("POST", "/api/vm/zz/eval", """{"source":"1"}"""), null)!!.status)
            assertEquals(409, wire.route("POST", "/api/vm/spawn", http("POST", "/api/vm/spawn", """{"id":"a","facet":"js"}"""), null)!!.status)

            val revoked = wire.route("POST", "/api/vm/a/revoke", http("POST", "/api/vm/a/revoke", """{"reason":"test"}"""), null)!!
            assertEquals(200, revoked.status)
            assertEquals("revoked", list(list(obj(wire.route("GET", "/api/vm", http("GET", "/api/vm"), null)!!.body)["rows"])[0])[4])
            assertNull(wire.route("GET", "/api/board", http("GET", "/api/board"), null), "declines paths it does not own")
        } finally { host.close() }
    }

    @Test
    fun extraRoutesReachTheWireThroughTheServerSeam() = runBlocking {
        val host = HypervisorVmHost()
        val wire = VmWire(host, CoroutineScope(Dispatchers.Default))
        val server = JvmKanbanServer(extraRoutes = listOf(wire::route), streamingPaths = setOf(VmWire.EVENTS_PATH))
        try {
            val r = server.routeHttp(http("GET", "/api/vm").toByteArray())
            assertEquals(200, r.status)
            assertTrue(r.body.contains("\"columns\""))
            assertEquals(404, server.routeHttp(http("GET", "/nope").toByteArray()).status, "unowned paths still 404")
            assertEquals(200, server.routeHttp(http("GET", "/api/health").toByteArray()).status, "built-in routes unchanged")
        } finally { host.close() }
    }

    @Test
    fun deadHostAnswersHonestly() = runBlocking {
        val wire = VmWire(VmHost.NONE, CoroutineScope(Dispatchers.Default))
        val spawned = wire.route("POST", "/api/vm/spawn", http("POST", "/api/vm/spawn", """{"id":"a","facet":"js"}"""), null)!!
        assertEquals(409, spawned.status)
        assertTrue(spawned.body.contains("vm.spawn"), spawned.body)
    }
}
