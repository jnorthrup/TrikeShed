package borg.trikeshed.mux

import borg.trikeshed.parse.jsonOf
import borg.trikeshed.parse.reifyStrict
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The server as a real operating-system process, spoken to over real pipes and a real socket: the claim that one
 * binary serves as an MCP stdio server, a one-shot wrapper and an inetd/tcpd service is checked, not assumed.
 */
class McpStdioProcessTest {
    private val java = File(System.getProperty("java.home"), "bin/java").absolutePath
    private val classpath: String = System.getProperty("java.class.path")
    private val secret = "fixture-secret-value-0451"

    private class Server(val process: Process) : AutoCloseable {
        val stderr = StringBuffer()
        private val reader = process.inputStream.bufferedReader(Charsets.UTF_8)
        private val errors = thread(isDaemon = true) { process.errorStream.bufferedReader().forEachLine { stderr.append(it).append('\n') } }

        fun send(line: String) = sendRaw(line + "\n")
        fun sendRaw(text: String) { process.outputStream.write(text.toByteArray()); process.outputStream.flush() }
        fun closeStdin() = process.outputStream.close()

        /** The next stdout line, or null at end of stream. A server that stops answering fails the test instead of hanging it. */
        fun readLine(timeoutMs: Long = 30_000): String? =
            try { CompletableFuture.supplyAsync { reader.readLine() }.get(timeoutMs, TimeUnit.MILLISECONDS) }
            catch (late: TimeoutException) { throw AssertionError("no reply within ${timeoutMs}ms; stderr so far:\n$stderr") }

        fun exit(timeoutMs: Long = 30_000): Int {
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) throw AssertionError("server did not exit; stderr:\n$stderr")
            errors.join(5_000)   // everything the process wrote is in `stderr` before anyone asserts on it
            return process.exitValue()
        }

        override fun close() { if (process.isAlive) process.destroyForcibly() }
    }

    private fun start(vararg args: String, env: Map<String, String> = emptyMap()): Server {
        val builder = ProcessBuilder(listOf(java, "-cp", classpath, "borg.trikeshed.mux.McpStdioMain") + args)
        builder.environment().clear()
        builder.environment().putAll(env)
        return Server(builder.start())
    }

    private fun rpc(id: Int, method: String, params: Map<String, Any?> = emptyMap()) =
        jsonOf(buildMap { put("jsonrpc", "2.0"); put("id", id); put("method", method); if (params.isNotEmpty()) put("params", params) })

    private fun callTool(id: Int, tool: String, args: Map<String, Any?> = emptyMap()) = rpc(id, "tools/call", mapOf("name" to tool, "arguments" to args))

    private fun parse(line: String?): Map<*, *> = reifyStrict(assertNotNull(line, "expected a reply line")) as Map<*, *>
    private fun Map<*, *>.result() = this["result"] as Map<*, *>
    private fun Map<*, *>.data() = result()["structuredContent"] as Map<*, *>
    private fun toolNames(doc: Map<*, *>) = (doc.result()["tools"] as List<*>).map { (it as Map<*, *>)["name"] as String }

    @Test
    fun asAOneShotWrapperAnUnterminatedRequestGetsOneReplyThenAnOrderlyExit() {
        start().use { s ->
            s.sendRaw("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")
            s.closeStdin()
            val reply = parse(s.readLine())
            assertEquals("2.0", reply["jsonrpc"]); assertEquals(1, (reply["id"] as Number).toInt())
            assertEquals(listOf("zenith_status", "zenith_plan_validate"), toolNames(reply))
            assertNull(s.readLine(), "one request, one reply, then end of stream")
            assertEquals(0, s.exit())
            val log = s.stderr.toString()
            assertTrue("mcp\tstart" in log, log)
            assertFalse("\"jsonrpc\"" in log, "requests are never echoed to the log")
        }
    }

    @Test
    fun asAnMcpStdioServerStdoutCarriesNothingButProtocolLines() {
        start("--max-line-bytes", "2048", "--scope", "team/a", "--allow-write").use { s ->
            s.send(rpc(1, "initialize", mapOf("protocolVersion" to "2025-06-18", "capabilities" to emptyMap<String, Any>(), "clientInfo" to mapOf("name" to "t", "version" to "1"))))
            val init = parse(s.readLine())
            assertEquals("trikeshed-modelmux-zenith", (init.result()["serverInfo"] as Map<*, *>)["name"])
            assertEquals("2025-06-18", init.result()["protocolVersion"])

            s.send("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")      // a notification: no reply
            s.send(rpc(2, "tools/list"))
            val listed = parse(s.readLine())
            assertEquals(2, (listed["id"] as Number).toInt(), "the notification produced no line")
            assertEquals(
                setOf("zenith_status", "zenith_plan_validate", "modelmux_context_acquire", "modelmux_context_read", "modelmux_breadcrumb_project", "modelmux_context_put"),
                toolNames(listed).toSet(),
            )

            s.send(callTool(3, "zenith_status"))
            assertTrue((parse(s.readLine()).data()["missing"] as List<*>).isNotEmpty())

            s.send("x".repeat(5_000))                                                    // beyond --max-line-bytes
            val tooLong = parse(s.readLine())
            assertEquals(-32600, ((tooLong["error"] as Map<*, *>)["code"] as Number).toInt())
            s.send("this is not json")
            assertEquals(-32700, ((parse(s.readLine())["error"] as Map<*, *>)["code"] as Number).toInt())

            s.send(rpc(4, "ping"))
            assertEquals(4, (parse(s.readLine())["id"] as Number).toInt(), "the stream resynchronised after bad input")

            // A write-and-read round trip through the real process.
            s.send(callTool(5, "modelmux_context_put", mapOf("scope" to "team/a", "author" to "t", "documents" to listOf(mapOf("name" to "n.txt", "text" to "the manifest must be verified\n")))))
            val manifest = parse(s.readLine()).data()["manifest"] as String
            s.send(callTool(6, "modelmux_breadcrumb_project", mapOf("manifest" to manifest, "scope" to "team/a", "question" to "manifest verified")))
            assertTrue("the manifest must be verified" in (parse(s.readLine()).data()["text"] as String))

            s.closeStdin()
            assertNull(s.readLine(), "nothing but replies was ever written to stdout")
            assertEquals(0, s.exit())
        }
    }

    @Test
    fun aRevisionPutBeforeARestartIsStillServedAfterIt() {
        val store = Files.createTempDirectory("mcp-store-").toFile()
        try {
            val text = "The manifest must be verified before caching.\nfiller\nCargo manifest lists forty crates of oranges.\n"
            var manifest = ""
            var firstText = ""
            start("--scope", "team/a", "--allow-write", "--store", store.absolutePath).use { s ->
                s.send(callTool(1, "modelmux_context_put", mapOf("scope" to "team/a", "author" to "t", "documents" to listOf(mapOf("name" to "n.txt", "text" to text)))))
                manifest = parse(s.readLine()).data()["manifest"] as String
                s.send(callTool(2, "modelmux_breadcrumb_project", mapOf("manifest" to manifest, "scope" to "team/a", "question" to "manifest verified caching")))
                firstText = parse(s.readLine()).data()["text"] as String
                s.closeStdin(); assertEquals(0, s.exit())
            }
            assertTrue(firstText.isNotEmpty())
            // A different process, read-only (no --allow-write): everything it knows came from the directory.
            start("--scope", "team/a", "--store", store.absolutePath).use { s ->
                assertFalse("modelmux_context_put" in toolNames(parse(run { s.send(rpc(1, "tools/list")); s.readLine() })))
                s.send(callTool(2, "modelmux_breadcrumb_project", mapOf("manifest" to manifest, "scope" to "team/a", "question" to "manifest verified caching")))
                assertEquals(firstText, parse(s.readLine()).data()["text"], "the restarted server answers exactly as the first did")
                s.send(callTool(3, "modelmux_context_read", mapOf("manifest" to manifest, "scope" to "team/a", "document" to "n.txt", "startLine" to 1, "endLine" to 1)))
                assertEquals("The manifest must be verified before caching.", parse(s.readLine()).data()["text"])
                s.closeStdin(); assertEquals(0, s.exit())
            }
        } finally {
            store.deleteRecursively()
        }
    }

    @Test
    fun realLocalMlxJevaReviewsBreadcrumbsThroughTheServerProcess() {
        assumeTrue(System.getenv("TRIKESHED_TEST_JEVA_MLX") == "1", "set TRIKESHED_TEST_JEVA_MLX=1 after sourcing config/jeva-mlx.env.sh")
        val launching = System.getenv().filterKeys { it.startsWith("TRIKESHED_JEVA_") || it in setOf("HF_HUB_OFFLINE", "TRANSFORMERS_OFFLINE", "HOME", "PATH") }
        assertEquals("mlx", launching["TRIKESHED_JEVA_DEVICE"], "source config/jeva-mlx.env.sh first")
        start("--scope", "team/a", "--allow-write", "--oversight", "mlx", env = launching).use { s ->
            s.send(callTool(1, "modelmux_context_put", mapOf("scope" to "team/a", "author" to "t", "documents" to listOf(
                mapOf("name" to "notes.md", "text" to "The manifest must be verified against its sha256 before any block is cached.\n"),
                mapOf("name" to "cargo.md", "text" to "Cargo note: the manifest of the container ship lists forty crates of oranges.\n"),
            ))))
            val manifest = parse(s.readLine()).data()["manifest"] as String
            s.send(callTool(2, "modelmux_breadcrumb_project", mapOf("manifest" to manifest, "scope" to "team/a", "question" to "How is the manifest verified before blocks are cached?", "oversight" to "required")))
            val reply = parse(s.readLine(timeoutMs = 300_000)).data()
            val oversight = reply["oversight"] as Map<*, *>
            println("MLX-SERVER oversight=$oversight")
            val probabilities = (reply["excerpts"] as List<*>).map { it as Map<*, *> }.associate { it["document"] to (it["probability"] as Number).toDouble() }
            println("MLX-SERVER probabilities=$probabilities")
            assertEquals("REVIEWED", oversight["status"], oversight.toString())
            assertNotNull(oversight["model"])
            assertTrue(probabilities.getValue("notes.md") > probabilities.getValue("cargo.md"), "the relevant line must outrank the irrelevant one: $probabilities")
            s.closeStdin(); assertEquals(0, s.exit(60_000))
        }
    }

    @Test
    fun missionChildrenNeverReceiveTheServersCredentials() {
        start("--allow-exec", env = mapOf("MISSION_TEST_KEY" to secret)).use { s ->
            val script = "printenv MISSION_TEST_KEY || echo absent; env"
            val plan = jsonOf(mapOf("tasks" to listOf(mapOf("id" to "peek", "args" to listOf("-c", script)))))
            s.send(callTool(1, "zenith_mission_run", mapOf("plan" to plan)))
            val raw = assertNotNull(s.readLine())
            val outputs = parse(raw).data()["outputs"] as Map<*, *>
            val stdout = (outputs["peek"] as Map<*, *>)["stdout"] as String
            assertTrue(stdout.startsWith("absent"), stdout)
            assertFalse(secret in raw, "the secret is nowhere in the reply")
            assertFalse("MISSION_TEST_KEY" in raw, "not even the variable's name reached the child")
            s.closeStdin()
            assertEquals(0, s.exit())
            assertFalse(secret in s.stderr.toString(), "nor in the log")
        }
    }

    @Test
    fun withoutAllowExecNoArgumentCanRunACommand() {
        start().use { s ->
            s.send(callTool(1, "zenith_mission_run", mapOf("plan" to """{"tasks":[{"id":"a","args":["-c","touch /tmp/should-not-exist-mcp"]}]}""")))
            val reply = parse(s.readLine())
            assertEquals(-32602, ((reply["error"] as Map<*, *>)["code"] as Number).toInt())
            assertFalse(File("/tmp/should-not-exist-mcp").exists())
            s.closeStdin(); assertEquals(0, s.exit())
        }
    }

    @Test
    fun malformedInvocationsFailFastWithAReasonOnStderrAndNothingOnStdout() {
        start("--allow-everything").use { s ->
            assertNull(s.readLine(), "stdout stays empty")
            assertEquals(64, s.exit())
            assertTrue("unknown option" in s.stderr.toString(), s.stderr.toString())
        }
        start("--help").use { s ->
            val usage = s.process.inputStream.readAllBytes().decodeToString()
            assertTrue("Usage: mcp-stdio" in usage && "ONLY protocol" in usage, usage)
            assertEquals(0, s.exit())
        }
    }

    @Test
    fun aTerminationSignalEndsAnIdleServerPromptly() {
        start().use { s ->
            s.send(rpc(1, "ping"))
            assertEquals(1, (parse(s.readLine())["id"] as Number).toInt())
            s.process.destroy()
            s.exit(15_000)
        }
    }

    @Test
    fun underAnInetdStyleSuperserverTheSocketIsSimplyStdinAndStdout() {
        val dir = Files.createTempDirectory("mcp-inetd-").toFile()
        try {
            val launcher = File(dir, "inetd.py")
            launcher.writeText(
                """
                import os, socket, sys
                srv = socket.socket()
                srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                srv.bind(("127.0.0.1", 0)); srv.listen(1)
                print(srv.getsockname()[1], flush=True)
                conn, _ = srv.accept()
                os.dup2(conn.fileno(), 0); os.dup2(conn.fileno(), 1)
                srv.close(); conn.close()
                os.execv(sys.argv[1], sys.argv[1:])
                """.trimIndent(),
            )
            val process = try {
                ProcessBuilder("python3", launcher.path, java, "-cp", classpath, "borg.trikeshed.mux.McpStdioMain").redirectError(File(dir, "stderr.txt")).start()
            } catch (missing: java.io.IOException) { assumeTrue(false, "python3 is needed to play the superserver"); return }
            try {
                val port = process.inputStream.bufferedReader().readLine().trim().toInt()
                Socket("127.0.0.1", port).use { sock ->
                    sock.soTimeout = 30_000
                    val out = sock.getOutputStream(); val input = sock.getInputStream().bufferedReader(Charsets.UTF_8)
                    out.write((rpc(1, "tools/list") + "\n" + callTool(2, "zenith_status") + "\n").toByteArray()); out.flush()
                    val replies = (1..2).map { parse(input.readLine()) }.associateBy { (it["id"] as Number).toInt() }
                    assertEquals(listOf("zenith_status", "zenith_plan_validate"), toolNames(replies.getValue(1)))
                    assertTrue((replies.getValue(2).data()["missing"] as List<*>).isNotEmpty())
                    sock.shutdownOutput()
                    assertNull(input.readLine(), "the server closed its side at end of input")
                }
                assertTrue(process.waitFor(30, TimeUnit.SECONDS), "the service ended with the connection")
                assertEquals(0, process.exitValue(), File(dir, "stderr.txt").readText())
            } finally {
                process.destroyForcibly()
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
