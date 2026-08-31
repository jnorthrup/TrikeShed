package borg.trikeshed.lcnc

import borg.trikeshed.couch.CouchDatabase
import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.job.CasStore
import borg.trikeshed.lib.toSeries
import borg.trikeshed.relaxfactory.RelaxTransport
import borg.trikeshed.relaxfactory.RequestFactoryProxy
import borg.trikeshed.relaxfactory.RequestFactoryRpcTarget
import borg.trikeshed.relaxfactory.RequestFactoryServerProxy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RequestFactoryNodesTest {

    private fun registry(): Map<String, LcncNodeRunner> {
        val cas = CasStore.inMemory()
        val db = CouchDatabase("trikeshed", CouchStoreFactory.casBacked(cas), cas)
        val server = RequestFactoryServerProxy(
            mapOf(
                "session.echo" to RequestFactoryRpcTarget { args ->
                    mapOf("echo" to args["text"], "size" to args.size)
                },
            ),
        )
        return RequestFactoryNodes.registry(RequestFactoryProxy(RelaxTransport.local(server.bind(db))))
    }

    @Test
    fun everyServedTypeHasAContractAndRunner() {
        val contracts = LcncContracts.all().map { it.type }.toSet()
        val served = RequestFactoryNodes.servedTypes()
        assertEquals(setOf("rf.rpc", "rf.batch"), served)
        for (type in served) assertTrue(type in contracts, "$type is served but has no contract")
        assertEquals(served, contracts.filter { it.startsWith("rf.") }.toSet())
    }

    @Test
    fun rpcNodeCallsKotlinTargetThroughRequestFactoryProxy() = runTest {
        val out = registry().getValue("rf.rpc").run(
            LcncNode("rpc", "rf.rpc", params = mapOf("target" to "session.echo", "args" to """{"text":"hi"}""")),
            emptyMap(),
        )

        @Suppress("UNCHECKED_CAST")
        val receipt = out["receipt"] as Map<String, Any?>
        assertEquals(true, receipt["ok"], "receipt failed: $receipt")
        @Suppress("UNCHECKED_CAST")
        val result = out["result"] as Map<String, Any?>
        assertEquals("hi", result["echo"])
        assertEquals(1L, (result["size"] as Number).toLong())
    }

    @Test
    fun batchNodeSubmitsRawRequestFactoryOperations() = runTest {
        val out = registry().getValue("rf.batch").run(
            LcncNode(
                "batch",
                "rf.batch",
                params = mapOf("operations" to """[{"op":"rpc","target":"session.echo","args":{"text":"batch"}}]"""),
            ),
            emptyMap(),
        )

        assertEquals(true, out["ok"])
        val receipts = out["receipts"] as List<*>
        val receipt = receipts.single() as Map<*, *>
        assertEquals(true, receipt["ok"])
        assertEquals("batch", ((receipt["result"] as Map<*, *>)["echo"]))
    }

    @Test
    fun requestFactoryPortsParticipateInTypeCheck() {
        val program = LcncProgram(
            name = "rf",
            nodes = listOf(
                LcncNode("target", "text.value"),
                LcncNode("args", "http.get"),
                LcncNode("rpc", "rf.rpc"),
                LcncNode("display", "display"),
            ).toSeries(),
            wires = listOf(
                LcncWire("target", "value", "rpc", "target?"),
                LcncWire("args", "json", "rpc", "args?"),
                LcncWire("rpc", "result", "display", "x"),
            ).toSeries(),
        )

        val violations = LcncTypeCheck.check(program)
        assertTrue(violations.isEmpty(), violations.joinToString("\n") { it.render() })
    }
}
