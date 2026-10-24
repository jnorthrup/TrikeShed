package borg.trikeshed.cas

import borg.trikeshed.agent.modelmux.BreadcrumbProjector
import borg.trikeshed.agent.modelmux.ContextBlockException
import borg.trikeshed.agent.modelmux.ContextReadPolicy
import borg.trikeshed.agent.modelmux.ContextRejection
import borg.trikeshed.agent.modelmux.PublicationAudience
import borg.trikeshed.agent.modelmux.PublicationPolicy
import borg.trikeshed.agent.modelmux.PublicationStatus
import borg.trikeshed.agent.modelmux.SharedContext
import borg.trikeshed.agent.modelmux.SharedContextMode
import borg.trikeshed.agent.modelmux.SharedContextPublication
import borg.trikeshed.agent.modelmux.revision
import borg.trikeshed.job.CasStore
import borg.trikeshed.lib.*
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.parse.reifyStrict
import borg.trikeshed.userspace.nio.channels.spi.JvmProcessOperations
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.lang.System
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Opt-in: two REAL Kubo nodes exchanging blocks, started by this test on loopback only.
 *
 * Isolation, all of it enforced by the node's own `--profile=test` plus the checks below:
 *  - each node has its own throwaway repository under a fresh temporary directory (IPFS_PATH is always that one);
 *  - API, gateway and swarm listen on 127.0.0.1 with kernel-assigned ports; bootstrap is empty, mDNS and autoconf are off;
 *  - the nodes are connected to each other explicitly, and the test fails if either sees any other peer;
 *  - both daemons are stopped (then killed if necessary) in `finally`, and the temporary directory is deleted.
 * Nothing is installed, nothing reaches a public network, and ~/.ipfs is never touched.
 *
 *   TRIKESHED_TEST_KUBO_BIN=/opt/homebrew/bin/ipfs TRIKESHED_TEST_KUBO_LOOPBACK_PEERS=1 \
 *     ./gradlew --offline :jvmTest -PkotlinJsApps= --rerun --tests 'borg.trikeshed.cas.SharedContextPeerIntegrationTest'
 *
 * Without BOTH variables every test is SKIPPED (not passed). Operator-provided nodes are a different fixture and are not
 * supported here.
 */
class SharedContextPeerIntegrationTest {
    private val binary: String? = System.getenv("TRIKESHED_TEST_KUBO_BIN")
    private val allowed: Boolean = System.getenv("TRIKESHED_TEST_KUBO_LOOPBACK_PEERS") == "1"
    private val scope = "team/a"

    /** One daemon on loopback, driven only through its CLI. */
    private class Node(private val binary: String, parent: File, val name: String) : AutoCloseable {
        val repo: String = File(parent, name).absolutePath
        private val output = File(parent, "$name.out")
        private var daemon: Process? = null
        lateinit var api: String
            private set

        private fun cli(vararg args: String, apiTarget: Boolean = true): String {
            val command = listOf(binary) + (if (apiTarget) listOf("--api=$api") else emptyList()) + args
            val builder = ProcessBuilder(command).redirectErrorStream(false)
            builder.environment().clear()
            builder.environment()["IPFS_PATH"] = repo
            val child = builder.start()
            val text = child.inputStream.readBytes().decodeToString()
            val errors = child.errorStream.readBytes().decodeToString()
            check(child.waitFor(60, TimeUnit.SECONDS)) { "`ipfs ${args.joinToString(" ")}` did not finish in 60s" }
            check(child.exitValue() == 0) { "`ipfs ${args.joinToString(" ")}` on $name exited ${child.exitValue()}: ${errors.take(300)}" }
            return text
        }

        fun start() {
            cli("init", "--profile=test", "--empty-repo", apiTarget = false)
            val builder = ProcessBuilder(binary, "daemon").redirectErrorStream(true).redirectOutput(output)
            builder.environment().clear()
            builder.environment()["IPFS_PATH"] = repo
            val child = builder.start()
            daemon = child
            val deadline = System.nanoTime() + 30_000_000_000L
            while ("Daemon is ready" !in output.readText()) {
                check(child.isAlive) { "daemon $name exited early: ${output.readText().takeLast(300)}" }
                check(System.nanoTime() < deadline) { "daemon $name was not ready in 30s" }
                Thread.sleep(50)
            }
            api = File(repo, "api").readText().trim()
        }

        fun loopbackAddress(): String = cli("id", "-f", "<addrs>").lines().first { it.startsWith("/ip4/127.0.0.1/tcp/") }
        fun connectTo(address: String) { cli("swarm", "connect", address) }
        fun peers(): List<String> = cli("swarm", "peers").lines().filter { it.isNotBlank() }
        fun stop() {
            val child = daemon ?: return
            child.destroy()
            if (!child.waitFor(10, TimeUnit.SECONDS)) { child.destroyForcibly(); child.waitFor() }
            daemon = null
        }

        override fun close() = stop()
    }

    private class Peers(val a: Node, val b: Node, val exchangeA: KuboContextBlockExchange, val exchangeB: KuboContextBlockExchange)

    private fun <T> withTwoPeers(readerTimeoutSeconds: Int = 20, body: suspend (Peers) -> T): T {
        assumeTrue(allowed, "set TRIKESHED_TEST_KUBO_LOOPBACK_PEERS=1 (and TRIKESHED_TEST_KUBO_BIN) to run two loopback nodes in throwaway repositories")
        // Opting in without naming the executable is a setup error to be seen, not a skip to be missed.
        checkNotNull(binary) { "TRIKESHED_TEST_KUBO_LOOPBACK_PEERS=1 needs TRIKESHED_TEST_KUBO_BIN to name the ipfs executable" }
        val dir = Files.createTempDirectory("kubo-peers-").toFile()
        val a = Node(binary, dir, "a")
        val b = Node(binary, dir, "b")
        try {
            a.start(); b.start()
            b.connectTo(a.loopbackAddress())
            val process = JvmProcessOperations()
            val peers = Peers(
                a, b,
                KuboContextBlockExchange(process, KuboNode(a.repo, a.api), binary, timeoutSeconds = 20),
                KuboContextBlockExchange(process, KuboNode(b.repo, b.api), binary, timeoutSeconds = readerTimeoutSeconds),
            )
            return runBlocking { withTimeout(180_000) { body(peers) } }
        } finally {
            a.close(); b.close()
            dir.deleteRecursively()
        }
    }

    private fun assertOnlyEachOther(peers: Peers) {
        val seenByB = peers.b.peers()
        val seenByA = peers.a.peers()
        assertEquals(1, seenByB.size, "node b must be connected to exactly one peer, saw $seenByB")
        assertEquals(1, seenByA.size, "node a must be connected to exactly one peer, saw $seenByA")
        assertTrue(seenByB.single().startsWith("/ip4/127.0.0.1/"), "the only peer must be on loopback: $seenByB")
        assertTrue(seenByA.single().startsWith("/ip4/127.0.0.1/"), "the only peer must be on loopback: $seenByA")
    }

    @Test
    fun aReaderWithNothingAcquiresExactlyWhatThePublisherApproved() = withTwoPeers { peers ->
        val rev = revision(
            scope,
            "notes.md" to "The manifest must be verified against its sha256 before any block is cached.\nPins are receipts, not trust.\n",
            "cargo.md" to "Cargo note: the manifest of the container ship lists forty crates of oranges.\r\nSecond line with CRLF.\r\n",
        )
        val casA = rev.intoCas(CasStore.inMemory())
        val publication = SharedContextPublication(casA, peers.exchangeA, null,
            PublicationPolicy(PublicationAudience.PRIVATE_PEERS, approvedScopes = setOf(scope)))
        val receipt = publication.publish(rev.cid)
        assertEquals(PublicationStatus.COMPLETE, receipt.status, receipt.blocks.joinToString { "${it.cid.value.take(14)} pub=${it.published} pin=${it.pinned} ok=${it.verified} ${it.failure}" })
        assertEquals(3, receipt.blocks.size, "the manifest and two documents")
        assertTrue(receipt.blocks.all { it.published && it.pinned && it.verified && it.failure == null })

        // Reader: a different node, an empty local store, and permission only for this scope.
        val casB = CasStore.inMemory()
        val reader = SharedContext(casB, peers.exchangeB, ContextReadPolicy(mode = SharedContextMode.PEER, allowedScopes = setOf(scope)))
        assertNull(casB.get(rev.cid), "the reader starts with nothing")
        val snapshot = reader.acquire(rev.cid, scope)
        assertEquals(3, reader.counters().peerFetches, "each block crossed the wire once")
        for ((id, bytes) in rev.blocks) assertContentEquals(bytes, casB.get(id), "block ${id.value.take(14)} is byte-identical and now local")
        assertEquals(listOf("cargo.md", "notes.md"), (0 until snapshot.documents.size).map { snapshot.documents[it].name }.sorted())
        assertEquals("Cargo note: the manifest of the container ship lists forty crates of oranges.\r\nSecond line with CRLF.\r\n",
            (0 until snapshot.documents.size).map { snapshot.documents[it] }.single { it.name == "cargo.md" }.text, "CRLF survives the wire")

        // The same task projection on both sides of the wire, with the same original ranges.
        val local = SharedContext(rev.intoCas(CasStore.inMemory()), null, ContextReadPolicy(allowedScopes = setOf(scope))).acquire(rev.cid, scope)
        val question = "how is the manifest verified before blocks are cached"
        val across = BreadcrumbProjector.project(snapshot, question)
        val direct = BreadcrumbProjector.project(local, question)
        assertTrue(across.excerpts.size > 0, "the question must select something")
        assertEquals(direct.render(), across.render(), "peer-acquired evidence projects exactly as local evidence")
        assertEquals(
            (0 until direct.excerpts.size).map { direct.excerpts[it].let { e -> Triple(e.document, e.range, e.text) } },
            (0 until across.excerpts.size).map { across.excerpts[it].let { e -> Triple(e.document, e.range, e.text) } },
        )

        // Continuity without the publisher: with node a gone, the reader still has everything it verified.
        peers.a.stop()
        val again = reader.acquire(rev.cid, scope)
        assertEquals(3, reader.counters().peerFetches, "a repeat acquisition is served locally and never touches the network")
        assertEquals(across.render(), BreadcrumbProjector.project(again, question).render())
    }

    /** One real MCP stdio server process, driven line by line; a reply that does not come fails the test instead of hanging it. */
    private class ServerProcess(private val process: Process) : AutoCloseable {
        private val reader = process.inputStream.bufferedReader(Charsets.UTF_8)

        fun ask(request: String, timeoutSeconds: Long = 90): Map<*, *> {
            process.outputStream.write((request + "\n").toByteArray())
            process.outputStream.flush()
            val line = try {
                CompletableFuture.supplyAsync { reader.readLine() }.get(timeoutSeconds, TimeUnit.SECONDS)
            } catch (late: TimeoutException) {
                throw AssertionError("the server gave no reply within ${timeoutSeconds}s")
            }
            return reifyStrict(checkNotNull(line) { "the server closed its output" }) as Map<*, *>
        }

        fun finish(): Int {
            process.outputStream.close()
            check(process.waitFor(30, TimeUnit.SECONDS)) { "the server did not exit after its input closed" }
            return process.exitValue()
        }

        override fun close() { if (process.isAlive) process.destroyForcibly() }
    }

    @Test
    fun anMcpServerBackedByOneNodeProjectsWhatAnotherNodePublished() = withTwoPeers { peers ->
        val rev = revision(
            scope,
            "notes.md" to "The manifest must be verified against its sha256 before any block is cached.\nPins are receipts, not trust.\n",
            "cargo.md" to "Cargo note: the manifest of the container ship lists forty crates of oranges.\n",
        )
        val publication = SharedContextPublication(rev.intoCas(CasStore.inMemory()), peers.exchangeA, null,
            PublicationPolicy(PublicationAudience.PRIVATE_PEERS, approvedScopes = setOf(scope)))
        assertEquals(PublicationStatus.COMPLETE, publication.publish(rev.cid).status)

        // The server is started exactly as an MCP client's launcher would: explicit flags, an empty environment, a local
        // store of its own (in memory), and node b as its only way to anything it does not already hold.
        val java = File(System.getProperty("java.home"), "bin/java").absolutePath
        val command = listOf(java, "-cp", System.getProperty("java.class.path"), "borg.trikeshed.mux.McpStdioMain",
            "--scope", scope, "--peer-kubo-repo", peers.b.repo, "--peer-kubo-api", peers.b.api, "--peer-kubo-bin", binary, "--peer-timeout-s", "20")
        val builder = ProcessBuilder(command).redirectError(File(peers.b.repo).parentFile.resolve("mcp-server.err"))
        builder.environment().clear()
        ServerProcess(builder.start()).use { server ->
            val question = "how is the manifest verified before blocks are cached"
            val reply = server.ask(jsonOf(mapOf(
                "jsonrpc" to "2.0", "id" to 1, "method" to "tools/call",
                "params" to mapOf("name" to "modelmux_breadcrumb_project", "arguments" to mapOf("manifest" to rev.cid.value, "scope" to scope, "question" to question)),
            )))
            val result = reply["result"] as Map<*, *>
            assertTrue(result["isError"] != true, "the tool failed: $result")
            val text = (result["structuredContent"] as Map<*, *>)["text"] as String

            val local = SharedContext(rev.intoCas(CasStore.inMemory()), null, ContextReadPolicy(allowedScopes = setOf(scope))).acquire(rev.cid, scope)
            assertEquals(BreadcrumbProjector.project(local, question).render(), text,
                "the server, which held nothing, answers exactly as a holder of the evidence would")
            assertTrue("verified against its sha256" in text)
            assertEquals(0, server.finish())
        }
        assertOnlyEachOther(peers)
    }

    @Test
    fun theNodesSeeOnlyEachOtherAndNothingUnapprovedEverReachesTheirStores() = withTwoPeers(readerTimeoutSeconds = 5) { peers ->
        assertOnlyEachOther(peers)
        val approved = revision(scope, "ok.md" to "approved evidence\n")
        val private = revision("team/private", "secret.md" to "never leaves this machine\n")
        val casA = approved.intoCas(CasStore.inMemory())
        private.intoCas(casA)
        val publication = SharedContextPublication(casA, peers.exchangeA, null, PublicationPolicy(PublicationAudience.PRIVATE_PEERS, approvedScopes = setOf(scope)))
        assertEquals(PublicationStatus.COMPLETE, publication.publish(approved.cid).status)

        // The refusal for the private scope happens before any block is sent, so node a never holds those blocks.
        val refused = assertFailsWith<borg.trikeshed.agent.modelmux.PublicationRefused> { publication.publish(private.cid) }
        assertEquals(borg.trikeshed.agent.modelmux.PublicationRefusal.SCOPE_NOT_APPROVED, refused.reason)

        // Even a reader that knows the private CID and is allowed to ask cannot get it: nothing ever advertised it.
        val casB = CasStore.inMemory()
        val reader = SharedContext(casB, peers.exchangeB, ContextReadPolicy(mode = SharedContextMode.PEER, allowedScopes = setOf(scope, "team/private")))
        val failure = assertFailsWith<ContextBlockException> { reader.acquire(private.cid, "team/private") }
        assertEquals(ContextRejection.EXCHANGE_FAILED, failure.reason)
        for (id in private.blocks.keys) assertNull(casB.get(id), "nothing partial was kept for ${id.value.take(14)}")
        assertOnlyEachOther(peers)
    }
}
