package borg.trikeshed.agent.zenith

import borg.trikeshed.mcp.McpToolServer
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.parse.reifyStrict
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private suspend fun McpToolServer.rpc(id: Int, tool: String, args: Map<String, Any?> = emptyMap()): Map<*, *> =
    reifyStrict(handle(jsonOf(mapOf("jsonrpc" to "2.0", "id" to id, "method" to "tools/call", "params" to mapOf("name" to tool, "arguments" to args))))) as Map<*, *>

private fun Map<*, *>.result() = this["result"] as Map<*, *>
private fun Map<*, *>.data() = result()["structuredContent"] as Map<*, *>
private fun Map<*, *>.failed() = result()["isError"] == true
private fun Map<*, *>.protocolCode() = ((this["error"] as? Map<*, *>)?.get("code") as? Number)?.toInt()
private fun Map<*, *>.message() = (data()["error"] as? String).orEmpty()

private const val TWO_STEP = """{"tasks":[{"id":"one","args":["-c","printf one"]},{"id":"two","depends_on":["one"],"args":["-c","printf two"]}]}"""
private const val CONTRACT = """{"assertions":["ARTIFACT-CONTENT"],"tasks":[
  {"id":"write-artifact","type":"work","targets":["ARTIFACT-CONTENT"],"args":["-c","true"]},
  {"id":"check-artifact","type":"validate","targets":["ARTIFACT-CONTENT"],"depends_on":["write-artifact"],"args":["-c","true"]},
  {"id":"seal-artifact","type":"gate","targets":["ARTIFACT-CONTENT"],"depends_on":["check-artifact"],"args":["-c","true"]}]}"""
private const val MODEL_PLAN = """{"tasks":[{"id":"infer","kind":"model","model":"fixture-model","request":{"max_tokens":8,"messages":[{"role":"user","content":"x"}]}}]}"""

class ZenithToolsTest {
    private fun server(allowExec: Boolean = false, journalDir: String? = null) = McpToolServer("fixture", "1", ZenithTools(allowExec, journalDir).tools())

    @Test
    fun statusSaysWhatThePortDoesNotDoAndWhatThisServerEnables() = runTest {
        val off = server().rpc(1, "zenith_status").data()
        assertTrue((off["port"] as String).contains("mission subset"), off.toString())
        assertEquals(ZenithStatus.missing, off["missing"])
        assertEquals(ZenithStatus.implemented, off["implemented"])
        assertTrue((off["execution"] as String).startsWith("disabled"))
        assertEquals("disabled", off["journals"])

        val on = server(allowExec = true, journalDir = "/fixture/journals").rpc(1, "zenith_status").data()
        assertTrue((on["execution"] as String).startsWith("enabled"))
        assertEquals("enabled", on["journals"])
        assertTrue(ZenithStatus.missing.any { "resumable" in it }, "unfinished work is declared, not hidden")
    }

    @Test
    fun executionAndJournalsExistOnlyWhenTheyWereExplicitlyEnabled() = runTest {
        assertEquals(setOf("zenith_status", "zenith_plan_validate"), server().toolNames.toSet())
        assertEquals(setOf("zenith_status", "zenith_plan_validate", "zenith_mission_inspect"), server(journalDir = "/x").toolNames.toSet())
        assertEquals(setOf("zenith_status", "zenith_plan_validate", "zenith_mission_run"), server(allowExec = true).toolNames.toSet())

        val unreachable = server().rpc(1, "zenith_mission_run", mapOf("plan" to TWO_STEP))
        assertEquals(-32602, unreachable.protocolCode(), "an unregistered tool cannot be reached by any argument")
        assertTrue("unknown tool" in ((unreachable["error"] as Map<*, *>)["message"] as String))

        val listed = (reifyStrict(server(allowExec = true).handle("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")) as Map<*, *>).result()["tools"] as List<*>
        val run = listed.map { it as Map<*, *> }.first { it["name"] == "zenith_mission_run" }
        assertEquals(false, (run["annotations"] as Map<*, *>)["readOnlyHint"])
        assertTrue((run["description"] as String).startsWith("EXECUTES"), "the description says what the tool does")
    }

    @Test
    fun validateAdmitsAPlanAndSaysWhyItCannotRunHere() = runTest {
        val denied = server().rpc(1, "zenith_plan_validate", mapOf("plan" to TWO_STEP)).data()
        assertEquals(true, denied["valid"])
        assertEquals(listOf("one", "two"), denied["order"])
        assertEquals(false, denied["runnableHere"])
        assertEquals(listOf("zenith_mission_run is not enabled on this server"), denied["blockers"])
        val tasks = denied["tasks"] as List<*>
        assertEquals("bash", (tasks[0] as Map<*, *>)["kind"])
        assertEquals(listOf("one"), (tasks[1] as Map<*, *>)["dependsOn"])

        val allowed = server(allowExec = true).rpc(1, "zenith_plan_validate", mapOf("plan" to TWO_STEP)).data()
        assertEquals(true, allowed["runnableHere"]); assertEquals(emptyList<Any>(), allowed["blockers"])
    }

    @Test
    fun contractPlansReportTheirAssertionsAndRoles() = runTest {
        val admitted = server().rpc(1, "zenith_plan_validate", mapOf("plan" to CONTRACT)).data()
        assertEquals(true, admitted["valid"], admitted.toString())
        assertEquals(listOf("write-artifact", "check-artifact", "seal-artifact"), admitted["order"])
        assertEquals(listOf("ARTIFACT-CONTENT"), admitted["assertions"])
        assertEquals(listOf("work", "validate", "gate"), (admitted["tasks"] as List<*>).map { (it as Map<*, *>)["role"] })
    }

    @Test
    fun modelTasksAreAdmittedButNeverClaimedRunnable() = runTest {
        val admitted = server(allowExec = true).rpc(1, "zenith_plan_validate", mapOf("plan" to MODEL_PLAN)).data()
        assertEquals(true, admitted["valid"], admitted.toString())
        assertEquals("model", ((admitted["tasks"] as List<*>)[0] as Map<*, *>)["kind"])
        assertEquals(false, admitted["runnableHere"], "execution is enabled, but model tasks cannot run through this interface")
        assertTrue("model tasks cannot be run through this interface yet" in (admitted["blockers"] as List<*>))
    }

    @Test
    fun refusedPlansAreReportedWithABoundedReasonNotAnException() = runTest {
        val bad = listOf(
            "not json at all",
            """{"tasks":[]}""",
            """{"tasks":[{"id":"a","depends_on":["b"],"args":["-c","true"]},{"id":"b","depends_on":["a"],"args":["-c","true"]}]}""",
            """{"tasks":[{"id":"a","depends_on":["ghost"],"args":["-c","true"]}]}""",
            """{"tasks":[{"id":"a","args":["-c","true"]},{"id":"a","args":["-c","true"]}]}""",
            """{"tasks":[{"id":"bad","args":"eval me"}]}""",
            """{"tasks":[{"id":"a","args":["-c","true"]}],"keys":{"K":"v"}}""",
        )
        for (plan in bad) {
            val r = server().rpc(1, "zenith_plan_validate", mapOf("plan" to plan))
            assertFalse(r.failed(), "refusal is a normal answer, not a tool error: $plan")
            assertEquals(false, r.data()["valid"], plan)
            val why = r.data()["error"] as String
            assertTrue(why.isNotBlank() && why.length <= 200, "reason for $plan was '$why'")
        }
    }

    @Test
    fun journalNamesAreBareNamesAndNothingTouchesTheFilesystemBeforeTheyPass() = runTest {
        val server = server(journalDir = "/fixture/never-read")
        for (name in listOf("../escape", "a/b", ".hidden", "x y", "-rf", "a\u0000b")) {
            val r = server.rpc(1, "zenith_mission_inspect", mapOf("journal" to name))
            assertTrue(r.failed(), name)
            assertTrue("bare file name" in r.message(), "$name -> ${r.message()}")
        }
        assertEquals(-32602, server.rpc(2, "zenith_mission_inspect", mapOf("journal" to "")).protocolCode())
        assertEquals(-32602, server.rpc(3, "zenith_mission_inspect", mapOf("journal" to "x".repeat(65))).protocolCode())
        assertEquals(-32602, server.rpc(4, "zenith_mission_inspect", emptyMap()).protocolCode())
    }

    @Test
    fun runArgumentsAreBoundedBeforeAnythingStarts() = runTest {
        val server = server(allowExec = true)
        assertEquals(-32602, server.rpc(1, "zenith_mission_run", mapOf("plan" to TWO_STEP, "timeoutMs" to 99)).protocolCode())
        assertEquals(-32602, server.rpc(2, "zenith_mission_run", mapOf("plan" to TWO_STEP, "timeoutMs" to 300_001)).protocolCode())
        assertEquals(-32602, server.rpc(3, "zenith_mission_run", mapOf("plan" to TWO_STEP, "maxOutputChars" to -1)).protocolCode())
        assertEquals(-32602, server.rpc(4, "zenith_mission_run", mapOf("plan" to "x")).protocolCode())
        assertEquals(-32602, server.rpc(5, "zenith_mission_run", mapOf("plan" to TWO_STEP, "env" to mapOf("K" to "v"))).protocolCode(), "no way to pass environment or credentials")
        val noJournals = server.rpc(6, "zenith_mission_run", mapOf("plan" to TWO_STEP, "journal" to "x"))
        assertTrue(noJournals.failed(), "a journal name is refused when no journal directory is configured, before anything runs")
        assertEquals("journals are not configured on this server", noJournals.message())
    }
}
