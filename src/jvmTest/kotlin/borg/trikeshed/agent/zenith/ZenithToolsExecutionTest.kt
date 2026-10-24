package borg.trikeshed.agent.zenith

import borg.trikeshed.mcp.McpToolServer
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.parse.reifyStrict
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Real Bash, real processes, through the MCP tool exactly as a client reaches it. */
class ZenithToolsExecutionTest {
    private val dir: File = Files.createTempDirectory("zenith-exec-").toFile()

    @AfterTest
    fun cleanup() { dir.deleteRecursively() }

    private fun server() = McpToolServer("fixture", "1", ZenithTools(allowExec = true, journalDir = dir.absolutePath).tools())

    private suspend fun McpToolServer.rpc(id: Int, tool: String, args: Map<String, Any?>): Map<*, *> =
        reifyStrict(handle(jsonOf(mapOf("jsonrpc" to "2.0", "id" to id, "method" to "tools/call", "params" to mapOf("name" to tool, "arguments" to args))))) as Map<*, *>

    private fun Map<*, *>.data() = (this["result"] as Map<*, *>)["structuredContent"] as Map<*, *>
    private fun Map<*, *>.failed() = (this["result"] as Map<*, *>)["isError"] == true
    private fun Map<*, *>.message() = (data()["error"] as? String).orEmpty()

    private fun task(id: String, script: String, dependsOn: List<String> = emptyList(), type: String? = null, targets: List<String> = emptyList()): Map<String, Any?> =
        buildMap {
            put("id", id); put("args", listOf("-c", script))
            if (dependsOn.isNotEmpty()) put("depends_on", dependsOn)
            if (type != null) put("type", type)
            if (targets.isNotEmpty()) put("targets", targets)
        }

    private fun plan(vararg tasks: Map<String, Any?>, assertions: List<String>? = null): String =
        jsonOf(buildMap { if (assertions != null) put("assertions", assertions); put("tasks", tasks.toList()) })

    private fun phases(data: Map<*, *>) = (data["events"] as List<*>).map { (it as Map<*, *>).let { e -> e["task"] as String to e["phase"] as String } }

    private fun alive(pid: Long) = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)

    private suspend fun awaitGone(pid: Long) {
        repeat(100) { if (!alive(pid)) return; delay(50) }
        error("process $pid is still running")
    }

    @Test
    fun aPlanRunsInDependencyOrderAndEveryTaskMustPass() = runBlocking {
        val r = server().rpc(1, "zenith_mission_run", mapOf("plan" to plan(task("one", "printf one"), task("two", "printf two", listOf("one")))))
        assertFalse(r.failed(), r.toString())
        val d = r.data()
        assertEquals("COMPLETED", d["outcome"])
        val seen = phases(d)
        assertTrue(seen.indexOf("one" to "ADMITTED") < seen.indexOf("one" to "COMPLETED"), "$seen")
        assertTrue(seen.indexOf("one" to "COMPLETED") < seen.indexOf("two" to "ADMITTED"), "two is admitted only after one completed: $seen")
        val outputs = d["outputs"] as Map<*, *>
        assertEquals("one", (outputs["one"] as Map<*, *>)["stdout"]); assertEquals(0, ((outputs["one"] as Map<*, *>)["exitCode"] as Number).toInt())
        assertEquals("two", (outputs["two"] as Map<*, *>)["stdout"])
    }

    @Test
    fun aFailingTaskStopsTheRunAndNothingDependingOnItEverStarts() = runBlocking {
        val mark = File(dir, "dependent-ran")
        val r = server().rpc(1, "zenith_mission_run", mapOf("plan" to plan(task("a", "echo oops >&2; exit 3"), task("b", "touch ${mark.absolutePath}", listOf("a")))))
        assertTrue(r.failed(), "a failed mission is a tool error")
        val d = r.data()
        assertEquals("FAILED", d["outcome"]); assertEquals("a", d["failedTask"]); assertNotNull(d["reason"])
        assertEquals(3, (((d["outputs"] as Map<*, *>)["a"] as Map<*, *>)["exitCode"] as Number).toInt())
        assertTrue("oops" in (((d["outputs"] as Map<*, *>)["a"] as Map<*, *>)["stderr"] as String))
        assertTrue(phases(d).none { it.first == "b" && it.second == "ADMITTED" }, "b was never admitted: ${phases(d)}")
        assertFalse(mark.exists(), "b never ran")
    }

    @Test
    fun contractPlansProjectAssertionStateFromReceipts() = runBlocking {
        val goal = "ARTIFACT-CONTENT"
        val passing = server().rpc(1, "zenith_mission_run", mapOf("plan" to plan(
            task("w", "true", type = "work", targets = listOf(goal)),
            task("v", "true", listOf("w"), "validate", listOf(goal)),
            task("g", "true", listOf("v"), "gate", listOf(goal)),
            assertions = listOf(goal),
        )))
        assertFalse(passing.failed(), passing.toString())
        assertEquals(mapOf(goal to "PASSED"), passing.data()["assertions"]); assertEquals("PASSED", passing.data()["assertionOutcome"])

        val gateMark = File(dir, "gate-ran")
        val failing = server().rpc(2, "zenith_mission_run", mapOf("plan" to plan(
            task("w", "true", type = "work", targets = listOf(goal)),
            task("v", "false", listOf("w"), "validate", listOf(goal)),
            task("g", "touch ${gateMark.absolutePath}", listOf("v"), "gate", listOf(goal)),
            assertions = listOf(goal),
        )))
        assertTrue(failing.failed())
        assertEquals("FAILED", failing.data()["assertionOutcome"], failing.data().toString())
        assertEquals("FAILED", (failing.data()["assertions"] as Map<*, *>)[goal])
        assertFalse(gateMark.exists(), "a gate follows its validator: a failed validation means the gate never runs")
    }

    @Test
    fun aTimeoutKillsTheChildAndSaysSo() = runBlocking {
        val pidFile = File(dir, "pid")
        val started = System.nanoTime()
        val r = server().rpc(1, "zenith_mission_run", mapOf("plan" to plan(task("slow", "echo \$\$ > ${pidFile.absolutePath}; exec sleep 30")), "timeoutMs" to 300))
        assertTrue(r.failed())
        assertEquals("TIMED_OUT", r.data()["outcome"]); assertTrue("300ms" in (r.data()["reason"] as String))
        assertTrue((System.nanoTime() - started) / 1_000_000 < 15_000, "the call returned long before the sleep would have")
        awaitGone(pidFile.readText().trim().toLong())
    }

    @Test
    fun cancellingTheCallKillsAndReapsTheChild() = runBlocking {
        val pidFile = File(dir, "pid2")
        val server = server()
        val job = launch(Dispatchers.Default) {
            server.rpc(1, "zenith_mission_run", mapOf("plan" to plan(task("slow", "echo \$\$ > ${pidFile.absolutePath}; exec sleep 30"))))
        }
        repeat(100) { if (!pidFile.exists() || pidFile.length() == 0L) delay(50) }
        assertTrue(pidFile.exists() && pidFile.length() > 0, "the child started")
        val pid = pidFile.readText().trim().toLong()
        assertTrue(alive(pid))
        job.cancelAndJoin()
        awaitGone(pid)
    }

    @Test
    fun journalsAreExclusiveCarryNoArgumentsAndInspectToTheSameReceipts() = runBlocking {
        val ran = File(dir, "ran")
        val p = plan(task("a", "printf SECRET_ARG_MARKER > /dev/null; touch ${ran.absolutePath}"))
        val first = server().rpc(1, "zenith_mission_run", mapOf("plan" to p, "journal" to "m1.journal"))
        assertFalse(first.failed(), first.toString())
        val journal = File(dir, "m1.journal")
        assertTrue(journal.length() > 0)
        assertFalse("SECRET_ARG_MARKER" in journal.readText(), "a journal carries receipts, never arguments")
        val before = journal.readBytes()

        ran.delete()
        val second = server().rpc(2, "zenith_mission_run", mapOf("plan" to p, "journal" to "m1.journal"))
        assertTrue(second.failed(), "an existing journal is never overwritten")
        assertContentEquals(before, journal.readBytes(), "the first run's journal is untouched")
        assertFalse(ran.exists(), "a run whose journal cannot be written must not have started its effects")

        val inspected = server().rpc(3, "zenith_mission_inspect", mapOf("journal" to "m1.journal"))
        assertFalse(inspected.failed(), inspected.toString())
        assertEquals(phases(first.data()), phases(inspected.data()), "inspection reconstructs exactly the receipts the run reported")
        assertEquals(first.data()["assertions"], inspected.data()["assertions"])

        val missing = server().rpc(4, "zenith_mission_inspect", mapOf("journal" to "nope.journal"))
        assertTrue(missing.failed()); assertEquals("no such journal", missing.message())
    }

    @Test
    fun invalidPlansAreRefusedBeforeAnythingRuns() = runBlocking {
        val mark = File(dir, "first-task-ran")
        val first = task("a", "touch ${mark.absolutePath}")
        for (bad in listOf(
            plan(first, task("a", "true")),                                        // duplicate id
            plan(first, task("b", "true", listOf("ghost"))),                        // unknown dependency
            jsonOf(mapOf("tasks" to listOf(first), "keys" to mapOf("K" to "v"))),   // credentials in a plan
            """{"tasks":[{"id":"x","args":"eval me"}]}""",
            """{"tasks":[{"id":"infer","kind":"model","model":"m","request":{"max_tokens":8,"messages":[{"role":"user","content":"x"}]}}]}""",
            "not json",
        )) {
            val r = server().rpc(1, "zenith_mission_run", mapOf("plan" to bad))
            assertTrue(r.failed(), bad)
            assertTrue(r.message().startsWith("plan refused:") || r.data()["outcome"] != null, "${r.data()} for $bad")
            assertFalse(mark.exists(), "nothing ran for: $bad")
        }
    }

    @Test
    fun outputIsBoundedAndSaysWhenItWasCut() = runBlocking {
        val r = server().rpc(1, "zenith_mission_run", mapOf("plan" to plan(task("big", "head -c 10000 /dev/zero | tr '\\0' 'x'")), "maxOutputChars" to 100))
        val out = (r.data()["outputs"] as Map<*, *>)["big"] as Map<*, *>
        assertEquals(100, (out["stdout"] as String).length); assertEquals(true, out["truncated"])
    }

    @Test
    fun aMissionChildSeesNoAmbientEnvironment() = runBlocking {
        val r = server().rpc(1, "zenith_mission_run", mapOf("plan" to plan(task("env", "env"))))
        val stdout = ((r.data()["outputs"] as Map<*, *>)["env"] as Map<*, *>)["stdout"] as String
        val names = stdout.lines().filter { '=' in it }.map { it.substringBefore('=') }.toSet()
        assertTrue(names.all { it in setOf("TERM", "LANG", "PWD", "SHLVL", "_", "OLDPWD") }, "the child's whole environment was: $names")
        val secretShaped = System.getenv().keys.filter { k -> listOf("KEY", "TOKEN", "SECRET", "PASSWORD").any { it in k.uppercase() } }
        assertTrue(secretShaped.none { it in names }, "ambient secret-shaped variables leaked: ${secretShaped.filter { it in names }}")
    }
}
