package borg.trikeshed.lcnc

import borg.trikeshed.kanban.InvokeLowering
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SurfaceCommandNodesTest {
    @Test
    fun jobCommandUsesHandlerPayloadAndStableIdempotency() = runTest {
        val sent = ArrayList<Map<*, *>>()
        val committed = mapOf("verdict" to "committed", "jobId" to "card", "revision" to 8L)
        val registry = SurfaceNodes.registry { method, path, body ->
            assertEquals("POST", method)
            assertEquals("/api/invoke", path)
            val command = ((body as Map<*, *>)["commands"] as List<*>).single() as Map<*, *>
            assertTrue(InvokeLowering.lower(command) is InvokeLowering.Outcome.Lowered)
            sent.add(command)
            mapOf("ok" to true, "results" to listOf(committed))
        }
        val runner = registry.getValue("job.command")
        val node = LcncNode("command", "job.command", params = mapOf(
            "verb" to "fail", "jobId" to "wrong", "reason" to "provided reason", "idempotencyKey" to "supplied",
        ))
        val inputs = mapOf("verb" to "cancel", "jobId" to "card", "expectedRevision?" to 7L)
        val out = runner.run(node, inputs)
        assertEquals(committed, out["result"])
        assertEquals(mapOf("type" to "cancel", "jobId" to "card", "expectedRevision" to 7L,
            "reason" to "provided reason", "idempotencyKey" to "supplied"), sent.single())

        val generated = LcncNode("command", "job.command")
        runner.run(generated, inputs)
        runner.run(generated, inputs)
        runner.run(generated, inputs + ("expectedRevision?" to 8L))
        assertEquals(sent[1]["idempotencyKey"], sent[2]["idempotencyKey"])
        assertNotEquals(sent[2]["idempotencyKey"], sent[3]["idempotencyKey"])
    }

    @Test
    fun jobBatchPreservesCommandsAndPerCommandRejections() = runTest {
        val commands = listOf(mapOf("type" to "cancel", "jobId" to "card", "idempotencyKey" to "one"), "invalid")
        val results = listOf(mapOf("verdict" to "rejected", "reason" to "cancel without expectedRevision"),
            mapOf("verdict" to "rejected", "reason" to "command is not an object"))
        val runner = SurfaceNodes.registry { method, path, body ->
            assertEquals("POST", method)
            assertEquals("/api/invoke", path)
            assertEquals(commands, (body as Map<*, *>)["commands"])
            mapOf("ok" to false, "accepted" to 0, "rejected" to 2, "results" to results)
        }.getValue("job.batch")
        val out = runner.run(LcncNode("batch", "job.batch"), mapOf("commands" to commands.toTypedArray()))
        assertEquals(results, out["results"])
    }

    @Test
    fun projectAdaptersPreservePendingReceiptAndUseCallerDispatcher() = runTest {
        val registry = SurfaceNodes.registry { _, _, _ -> error("default must not run") }
        val pending = mapOf("verdict" to "mounting", "path" to "/caller/path")
        val removed = mapOf("verdict" to "unmounted", "name" to "project")
        val requests = ArrayList<Triple<String, String, Any?>>()
        val call = SurfaceNodes.Call { method, path, body ->
            requests.add(Triple(method, path, body))
            if (method == "POST") pending else removed
        }
        withContext(SurfaceCallKey(call)) {
            val mount = registry.getValue("project.mount").run(
                LcncNode("mount", "project.mount", params = mapOf("path" to "/wrong")), mapOf("path?" to "/caller/path"),
            )
            assertEquals(pending, mount["scope"])
            val kill = registry.getValue("project.kill").run(
                LcncNode("kill", "project.kill", params = mapOf("name" to "project")), emptyMap(),
            )
            assertEquals(removed, kill["verdict"])
        }
        assertEquals(listOf<Triple<String, String, Any?>>(Triple("POST", "/api/projects", mapOf("path" to "/caller/path")),
            Triple("DELETE", "/api/projects/project", null)), requests)
    }

    @Test
    fun missingMalformedAndRefusedResponsesCannotReportSuccess() = runTest {
        val command = LcncNode("command", "job.command")
        val inputs = mapOf("verb" to "cancel", "jobId" to "card")
        for (reply in listOf(null, mapOf("error" to "unavailable"), mapOf("ok" to true),
            mapOf("results" to emptyList<Any?>()), mapOf("results" to listOf(null)),
            mapOf("results" to listOf(mapOf("verdict" to "unknown"))))) {
            val runner = SurfaceNodes.registry { _, _, _ -> reply }.getValue("job.command")
            assertFailsWith<IllegalStateException> { runner.run(command, inputs) }
        }
        val refused = SurfaceNodes.registry { _, _, _ -> mapOf("verdict" to "refused", "detail" to "not a directory") }
        assertFailsWith<IllegalStateException> {
            refused.getValue("project.mount").run(LcncNode("mount", "project.mount"), mapOf("path" to "/missing"))
        }
        val missing = SurfaceNodes.registry { _, _, _ -> mapOf("error" to "no such scope") }
        assertFailsWith<IllegalStateException> {
            missing.getValue("project.kill").run(LcncNode("kill", "project.kill", params = mapOf("name" to "missing")), emptyMap())
        }
    }
}
