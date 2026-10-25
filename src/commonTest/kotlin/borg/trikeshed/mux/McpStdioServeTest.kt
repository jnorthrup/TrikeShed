package borg.trikeshed.mux

import borg.trikeshed.mcp.LineRead
import borg.trikeshed.mcp.LineSink
import borg.trikeshed.mcp.LineSource
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.parse.reifyStrict
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.userspace.nio.spi.NioSupervisor
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private const val SCOPE = "team/a"
private const val SECRET = "SECRET-ARGUMENT-9f3c1a"
private const val NOTES = "The manifest must be verified before caching.\nfiller one\nfiller two\nCargo manifest lists forty crates of oranges.\n"

/** Both ends of the protocol channel, driven by the test one request at a time: a client with a pipe to the server and nothing else. */
private class Wire : LineSource, LineSink {
    private val requests = Channel<ByteArray?>(Channel.UNLIMITED)
    private val replies = Channel<String>(Channel.UNLIMITED)

    override suspend fun read(maxBytes: Int): LineRead = requests.receive()?.let { LineRead.Line(it) } ?: LineRead.Eof
    override suspend fun write(line: ByteArray) { replies.send(line.decodeToString()) }

    fun send(line: String) { requests.trySend(line.encodeToByteArray()) }
    fun hangUp() { requests.trySend(null) }
    suspend fun nextReply(): String = replies.receive()
}

private class FakeHost(val wire: Wire) : McpStdioHost {
    private val gate = SynchronizedObject()
    private val printedLines = mutableListOf<String>()
    private val diagnosticLines = mutableListOf<String>()
    var claimed = 0
        private set

    val printed: List<String> get() = synchronized(gate) { printedLines.toList() }
    val diagnostics: List<String> get() = synchronized(gate) { diagnosticLines.toList() }

    override fun print(text: String) { synchronized(gate) { printedLines += text } }
    override fun diagnose(line: String) { synchronized(gate) { diagnosticLines += line } }
    override fun claimProtocol(): ProtocolChannel { claimed++; return ProtocolChannel(wire, wire) }
}

private class Client(private val wire: Wire) {
    private var id = 0

    suspend fun call(method: String, params: Map<String, Any?> = emptyMap()): Map<*, *> {
        wire.send(jsonOf(mapOf("jsonrpc" to "2.0", "id" to ++id, "method" to method, "params" to params)))
        val reply = reifyStrict(wire.nextReply()) as Map<*, *>
        assertEquals("2.0", reply["jsonrpc"], reply.toString())
        assertEquals(id, (reply["id"] as Number).toInt(), "replies answer the request they were asked: $reply")
        return reply
    }

    suspend fun initialize(): Map<*, *> = call("initialize", mapOf("protocolVersion" to "2025-03-26")).also {
        wire.send(jsonOf(mapOf("jsonrpc" to "2.0", "method" to "notifications/initialized")))
    }

    suspend fun tool(name: String, args: Map<String, Any?> = emptyMap()) = call("tools/call", mapOf("name" to name, "arguments" to args))

    suspend fun toolNames(): Set<String> =
        ((call("tools/list").result()["tools"] as List<*>).map { (it as Map<*, *>)["name"] as String }).toSet()
}

private fun Map<*, *>.result() = this["result"] as Map<*, *>
private fun Map<*, *>.data() = result()["structuredContent"] as Map<*, *>
private fun Map<*, *>.failed() = result()["isError"] == true

/** Runs the whole server to the end of its input and returns its exit status, with the host it ran against. */
private suspend fun serve(vararg args: String, body: suspend (Client) -> Unit): Pair<Int, FakeHost> = coroutineScope {
    val wire = Wire()
    val host = FakeHost(wire)
    val running = async { McpStdio.run(arrayOf(*args), host) }
    try {
        body(Client(wire))
    } finally {
        wire.hangUp()
    }
    running.await() to host
}

private suspend fun <T> withTempDir(body: suspend (String) -> T): T {
    val nio = NioSupervisor()
    try {
        nio.open()
        val files = checkNotNull(nio.service<FileOperations>()) { "no FileOperations on this target" }
        val dir = files.createTempDir("mcp-stdio-")
        try {
            return body(dir)
        } finally {
            files.deleteRecursively(dir)
        }
    } finally {
        withContext(NonCancellable) { nio.drain(); nio.close() }
    }
}

/**
 * The whole stdio server through its real entry, [McpStdio.run], with no process and no platform stream: the same code the JVM `main`
 * and the native executable's `main` call. If these pass on a target, the target's `main` can only differ in its descriptors.
 */
class McpStdioServeTest {
    @Test
    fun helpPrintsTheUsageAndNeverClaimsTheProtocolChannel() = runTest(timeout = 60.seconds) {
        val host = FakeHost(Wire())
        assertEquals(0, McpStdio.run(arrayOf("--help"), host))
        assertEquals(listOf(McpStdioOptions.USAGE), host.printed)
        assertEquals(0, host.claimed, "stdout is claimed by the protocol only once the server is actually serving")
    }

    @Test
    fun aBadOptionIsAUsageErrorBeforeAnythingIsServed() = runTest(timeout = 60.seconds) {
        val host = FakeHost(Wire())
        assertEquals(64, McpStdio.run(arrayOf("--nope"), host))
        assertTrue(host.diagnostics.any { it.startsWith("mcp-stdio: unknown option") }, host.diagnostics.toString())
        assertTrue("try: mcp-stdio --help" in host.diagnostics)
        assertEquals(0, host.claimed)
        assertTrue(host.printed.isEmpty())
    }

    @Test
    fun theRegisteredToolsAreTheFlagsAndNothingElse() = runTest(timeout = 60.seconds) {
        val readers = setOf("modelmux_context_acquire", "modelmux_context_read", "modelmux_breadcrumb_project")
        var bare = emptySet<String>()
        var reading = emptySet<String>()
        var writing = emptySet<String>()
        assertEquals(0, serve { c -> c.initialize(); bare = c.toolNames() }.first)
        assertEquals(0, serve("--scope", SCOPE) { c -> c.initialize(); reading = c.toolNames() }.first)
        assertEquals(0, serve("--scope", SCOPE, "--allow-write") { c -> c.initialize(); writing = c.toolNames() }.first)

        assertEquals(setOf("zenith_status", "zenith_plan_validate"), bare, "with no flags nothing can run a command or touch shared context")
        assertTrue(reading.containsAll(readers), reading.toString())
        assertFalse("modelmux_context_put" in reading, "a server without --allow-write cannot be made to write")
        assertEquals(reading + "modelmux_context_put", writing)
        for (names in listOf(bare, reading, writing)) {
            assertFalse("zenith_mission_run" in names, "a server without --allow-exec cannot be made to run a command: $names")
            assertFalse("modelmux_chat" in names, "a server without a model lane spends nothing: $names")
        }
    }

    @Test
    fun aRevisionIsPutAcquiredAndProjectedWithItsSourceRangesAndNothingLeaksToTheLog() = runTest(timeout = 60.seconds) {
        val notes = NOTES + SECRET + "\n"
        val (status, host) = serve("--scope", SCOPE, "--allow-write") { c ->
            val init = c.initialize().result()
            assertEquals("trikeshed-modelmux-zenith", (init["serverInfo"] as Map<*, *>)["name"])
            assertEquals(McpStdio.VERSION, (init["serverInfo"] as Map<*, *>)["version"])

            val put = c.tool("modelmux_context_put", mapOf("scope" to SCOPE, "author" to "alice", "documents" to listOf(mapOf("name" to "notes.md", "text" to notes))))
            assertFalse(put.failed(), put.toString())
            val manifest = put.data()["manifest"] as String
            assertEquals(71, manifest.length)

            val acquired = c.tool("modelmux_context_acquire", mapOf("manifest" to manifest, "scope" to SCOPE))
            assertFalse(acquired.failed(), acquired.toString())
            val document = (acquired.data()["documents"] as List<*>)[0] as Map<*, *>
            assertEquals("notes.md", document["name"])

            val read = c.tool("modelmux_context_read", mapOf("manifest" to manifest, "scope" to SCOPE, "document" to "notes.md", "startLine" to 1, "endLine" to 2))
            assertEquals("The manifest must be verified before caching.\nfiller one", read.data()["text"])

            val projected = c.tool("modelmux_breadcrumb_project", mapOf("manifest" to manifest, "scope" to SCOPE, "question" to "manifest verified caching"))
            assertFalse(projected.failed(), projected.toString())
            val excerpt = (projected.data()["excerpts"] as List<*>)[0] as Map<*, *>
            assertEquals("notes.md", excerpt["document"])
            assertEquals(document["cid"], excerpt["documentCid"], "every excerpt names the exact bytes it was cut from")
            val range = excerpt["range"] as Map<*, *>
            assertTrue("manifest" in notes.substring((range["start"] as Number).toInt(), (range["end"] as Number).toInt()), "the range points at the cited source text")
        }
        assertEquals(0, status, "the end of the input ends the server cleanly")
        assertEquals(1, host.claimed)
        assertTrue(host.printed.isEmpty(), "nothing but protocol lines goes to stdout once serving")
        assertTrue(host.diagnostics.all { it.startsWith("mcp\t") || it.startsWith("mcp-stdio:") }, host.diagnostics.toString())
        assertTrue(host.diagnostics.any { it.startsWith("mcp\ttools/call\tmodelmux_context_put\t") }, "the log names the tool and the outcome: ${host.diagnostics}")
        assertTrue(host.diagnostics.none { SECRET in it || "oranges" in it }, "the log carries no arguments and no results: ${host.diagnostics}")
    }

    @Test
    fun theStartLineStatesTheSubstrateItIsReallyRunningOn() = runTest(timeout = 60.seconds) {
        val (status, host) = serve("--scope", SCOPE) { c -> c.initialize() }
        assertEquals(0, status)
        val start = host.diagnostics.first { it.startsWith("mcp\tstart\t") }
        assertTrue(Regex("""\tio=(.+/io_uring=(true|false)|unknown)$""").containsMatchIn(start), start)
        assertTrue("version=${McpStdio.VERSION}" in start, start)
    }

    @Test
    fun aStoreDirectoryLetsARevisionOutliveTheServerThatWroteIt() = runTest(timeout = 120.seconds) {
        withTempDir { store ->
            var manifest = ""
            val (first, _) = serve("--scope", SCOPE, "--allow-write", "--store", store) { c ->
                c.initialize()
                val put = c.tool("modelmux_context_put", mapOf("scope" to SCOPE, "author" to "alice", "documents" to listOf(mapOf("name" to "notes.md", "text" to NOTES))))
                assertFalse(put.failed(), put.toString())
                manifest = put.data()["manifest"] as String
            }
            assertEquals(0, first)

            var names = emptySet<String>()
            val (second, host) = serve("--scope", SCOPE, "--store", store) { c ->
                c.initialize()
                names = c.toolNames()
                val read = c.tool("modelmux_context_read", mapOf("manifest" to manifest, "scope" to SCOPE, "document" to "notes.md", "startLine" to 1, "endLine" to 4))
                assertFalse(read.failed(), read.toString())
                assertEquals(NOTES.trimEnd('\n'), read.data()["text"], "the restarted server answers with exactly the bytes the first one stored")
            }
            assertEquals(0, second)
            assertFalse("modelmux_context_put" in names, "the second server was started read-only")
            assertTrue(host.diagnostics.any { "store=durable" in it }, host.diagnostics.toString())
        }
    }
}
