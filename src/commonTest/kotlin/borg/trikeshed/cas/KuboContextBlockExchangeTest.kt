package borg.trikeshed.cas

import borg.trikeshed.agent.modelmux.ContextBlockException
import borg.trikeshed.agent.modelmux.ContextReadPolicy
import borg.trikeshed.agent.modelmux.ContextRejection
import borg.trikeshed.agent.modelmux.SharedContext
import borg.trikeshed.agent.modelmux.SharedContextMode
import borg.trikeshed.ipns.IpnsCid
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.userspace.nio.channels.spi.ProcessOperations
import borg.trikeshed.userspace.nio.channels.spi.ProcessOutput
import borg.trikeshed.userspace.nio.channels.spi.ProcessPipes
import borg.trikeshed.userspace.nio.channels.spi.ProcessResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Fixture paths: nothing here ever touches a real repository.
private val STANDALONE = KuboNode("/fixture/kubo-a")
private val CLIENT = KuboNode("/fixture/kubo-a", api = "/ip4/127.0.0.1/tcp/5011")

/** Scripted child output, served in small chunks so accumulation across reads is exercised. */
private class FakePipes(
    private val stdout: ByteArray = ByteArray(0),
    private val stderr: ByteArray = ByteArray(0),
    private val exit: Int = 0,
    private val endless: Boolean = false,
    private val chunk: Int = 7,
    private val stall: CompletableDeferred<Unit>? = null,
) : ProcessPipes {
    var outPos = 0
    var errPos = 0
    var served = 0L
    var closed = false

    override suspend fun write(bytes: ByteArray) = error("a fetch never writes")

    override suspend fun read(output: ProcessOutput, bytes: ByteArray): Int {
        stall?.await()
        val n = minOf(chunk, bytes.size)
        return when (output) {
            ProcessOutput.Stdout -> when {
                endless -> { for (i in 0 until n) bytes[i] = 0x41; served += n; n }
                outPos >= stdout.size -> -1
                else -> { val take = minOf(n, stdout.size - outPos); stdout.copyInto(bytes, 0, outPos, outPos + take); outPos += take; served += take; take }
            }
            ProcessOutput.Stderr -> if (errPos >= stderr.size) -1
            else { val take = minOf(n, stderr.size - errPos); stderr.copyInto(bytes, 0, errPos, errPos + take); errPos += take; take }
        }
    }

    override suspend fun closeInput() {}
    override suspend fun awaitExit(): Int = exit
    override suspend fun close() { closed = true }
}

private class FakeProcess : ProcessOperations {
    class Call(val kind: String, val command: String, val args: List<String>, val stdin: ByteArray?, val env: Map<String, String>)

    val calls = mutableListOf<Call>()
    var opened: FakePipes = FakePipes()
    var execResult: ProcessResult = ProcessResult(0, ByteArray(0), ByteArray(0))

    override suspend fun exec(command: String, args: List<String>, stdin: ByteArray?, env: Map<String, String>): ProcessResult {
        calls += Call("exec", command, args, stdin, env)
        return execResult
    }

    override suspend fun open(command: String, args: List<String>, env: Map<String, String>): ProcessPipes {
        calls += Call("open", command, args, null, env)
        return opened
    }
}

private fun text(value: String) = value.encodeToByteArray()

class KuboContextBlockExchangeTest {
    private val cid = IpnsCid.raw(byteArrayOf(1, 2, 3))

    @Test
    fun argumentVectorsAreExactShellFreeAndAlwaysNameTheRepository() = runTest {
        val standalone = FakeProcess().apply { opened = FakePipes(byteArrayOf(1, 2, 3)) }
        val client = FakeProcess().apply { opened = FakePipes(byteArrayOf(1, 2, 3)) }
        KuboContextBlockExchange(standalone, STANDALONE, "/fixture/bin/ipfs", 7).fetch(cid, 100)
        KuboContextBlockExchange(client, CLIENT, "/fixture/bin/ipfs", 7).fetch(cid, 100)

        val offline = standalone.calls.single()
        assertEquals("/fixture/bin/ipfs", offline.command)
        assertEquals(listOf("--offline", "--timeout=7s", "block", "get", "--", cid.toString()), offline.args)
        assertEquals(mapOf("IPFS_PATH" to "/fixture/kubo-a"), offline.env, "the repository is explicit; nothing is inherited")
        assertEquals(listOf("--api=/ip4/127.0.0.1/tcp/5011", "--timeout=7s", "block", "get", "--", cid.toString()), client.calls.single().args)
        assertEquals(mapOf("IPFS_PATH" to "/fixture/kubo-a"), client.calls.single().env, "a client is pinned to a repository too")

        val bytes = byteArrayOf(9, 8, 7)
        val writer = FakeProcess()
        val exchange = KuboContextBlockExchange(writer, STANDALONE, "/fixture/bin/ipfs", 7)
        writer.execResult = ProcessResult(0, text("${IpnsCid.raw(bytes)}\n"), ByteArray(0))
        exchange.publish(bytes)
        writer.execResult = ProcessResult(0, text("pinned $cid directly\n"), ByteArray(0))
        exchange.pin(cid)
        assertEquals(listOf("--offline", "--timeout=7s", "block", "put", "--cid-codec=raw", "--mhtype=sha2-256"), writer.calls[0].args)
        assertEquals(listOf("--offline", "--timeout=7s", "pin", "add", "--recursive=false", "--", cid.toString()), writer.calls[1].args)
    }

    @Test
    fun binaryRoundTripPreservesEveryByteValue() = runTest {
        val all = ByteArray(1024) { (it * 7 + 3).toByte() } + ByteArray(256) { it.toByte() }
        val process = FakeProcess().apply { opened = FakePipes(all) }
        val exchange = KuboContextBlockExchange(process, STANDALONE)
        assertContentEquals(all, exchange.fetch(IpnsCid.raw(all), all.size))

        process.execResult = ProcessResult(0, text("${IpnsCid.raw(all)}\n"), ByteArray(0))
        assertEquals(IpnsCid.raw(all), exchange.publish(all))
        assertContentEquals(all, process.calls.last().stdin, "the node received exactly the bytes, unmodified")
    }

    @Test
    fun streamCeilingStopsAnOversizeFetchAndTerminatesTheChild() = runTest {
        val pipes = FakePipes(endless = true)
        val process = FakeProcess().apply { opened = pipes }
        val failed = assertFailsWith<BlockTooLarge> { KuboContextBlockExchange(process, STANDALONE).fetch(cid, 100) }
        assertEquals(100, failed.limit)
        assertTrue(pipes.closed, "the child is terminated, not drained")
        assertTrue(pipes.served <= 101 + 7, "stopped one byte past the ceiling, but the child produced ${pipes.served}")
    }

    @Test
    fun aBlockExactlyAtTheCeilingIsAccepted() = runTest {
        val block = ByteArray(100) { it.toByte() }
        val process = FakeProcess().apply { opened = FakePipes(block) }
        assertContentEquals(block, KuboContextBlockExchange(process, STANDALONE).fetch(cid, 100))
    }

    @Test
    fun aNodeFailureIsReportedWithItsOwnBoundedDiagnosticAndTheChildIsReaped() = runTest {
        val huge = ByteArray(100_000) { 'e'.code.toByte() }
        val pipes = FakePipes(stderr = text("Error: not found ") + huge, exit = 1)
        val process = FakeProcess().apply { opened = pipes }
        val failed = assertFailsWith<KuboCommandFailed> { KuboContextBlockExchange(process, STANDALONE).fetch(cid, 100) }
        assertEquals(1, failed.exitCode)
        assertTrue("not found" in failed.stderr)
        assertTrue(failed.stderr.length <= 2_048, "the diagnostic is bounded: ${failed.stderr.length}")
        assertEquals(17 + 100_000, pipes.errPos, "the chatty child was drained, so it can never block on a full pipe")
        assertTrue(pipes.closed)
    }

    @Test
    fun publishRefusesAnyIdentityOtherThanTheOneComputedLocally() = runTest {
        val bytes = byteArrayOf(9, 9, 9)
        val process = FakeProcess()
        val exchange = KuboContextBlockExchange(process, STANDALONE)

        process.execResult = ProcessResult(0, text("${IpnsCid.raw(byteArrayOf(8))}\n"), ByteArray(0))
        assertFailsWith<KuboIdentityMismatch> { exchange.publish(bytes) }
        process.execResult = ProcessResult(0, text("this is not a cid"), ByteArray(0))
        assertFailsWith<KuboIdentityMismatch> { exchange.publish(bytes) }
        process.execResult = ProcessResult(0, ByteArray(0), ByteArray(0))
        assertFailsWith<KuboIdentityMismatch> { exchange.publish(bytes) }
        process.execResult = ProcessResult(1, ByteArray(0), text("Error: boom"))
        val failed = assertFailsWith<KuboCommandFailed> { exchange.publish(bytes) }
        assertEquals("Error: boom", failed.stderr)

        process.execResult = ProcessResult(0, text("  ${IpnsCid.raw(bytes)}  \n"), ByteArray(0))
        assertEquals(IpnsCid.raw(bytes), exchange.publish(bytes), "surrounding whitespace is the only tolerance")
    }

    @Test
    fun aPinIsOnlyAReceiptWhenTheNodeNamesThatBlock() = runTest {
        val process = FakeProcess()
        val exchange = KuboContextBlockExchange(process, STANDALONE)
        process.execResult = ProcessResult(0, text("pinned ${IpnsCid.raw(byteArrayOf(5))} directly\n"), ByteArray(0))
        assertFailsWith<KuboIdentityMismatch> { exchange.pin(cid) }
        process.execResult = ProcessResult(1, ByteArray(0), text("Error: pin failed"))
        assertFailsWith<KuboCommandFailed> { exchange.pin(cid) }
        process.execResult = ProcessResult(0, text("pinned $cid directly\n"), ByteArray(0))
        exchange.pin(cid)
    }

    @Test
    fun cancellingAFetchTerminatesTheChild() = runTest {
        val pipes = FakePipes(stall = CompletableDeferred())
        val process = FakeProcess().apply { opened = pipes }
        val job = launch { KuboContextBlockExchange(process, STANDALONE).fetch(cid, 100) }
        runCurrent()
        assertFalse(pipes.closed, "the child is running")
        job.cancel()
        job.join()
        assertTrue(pipes.closed, "cancellation killed and reaped the child")
    }

    @Test
    fun nodeConfigurationIsExplicitAndLocalByDefault() {
        assertFailsWith<IllegalArgumentException> { KuboNode("relative/path") }
        assertFailsWith<IllegalArgumentException> { KuboNode("/r", api = "/ip4/10.0.0.5/tcp/5001") }
        assertFailsWith<IllegalArgumentException> { KuboNode("/r", api = "http://127.0.0.1:5001") }
        assertFailsWith<IllegalArgumentException> { KuboNode("/r", api = "--api=evil") }
        assertFailsWith<IllegalArgumentException> { KuboNode("/r", api = "/ip4/127.0.0.1/tcp/99999999") }
        KuboNode("/r", api = "/ip4/127.0.0.1/tcp/5001")
        KuboNode("/r", api = "/ip6/::1/tcp/5001")
        KuboNode("/r", api = "/dns4/localhost/tcp/5001")
        KuboNode("/r", api = "/ip4/10.0.0.5/tcp/5001", remoteApiApproved = true)
        assertFailsWith<IllegalArgumentException> { KuboContextBlockExchange(FakeProcess(), KuboNode("/r"), binary = " ") }
        assertFailsWith<IllegalArgumentException> { KuboContextBlockExchange(FakeProcess(), KuboNode("/r"), timeoutSeconds = 0) }
    }

    @Test
    fun ceilingsAreEnforcedBeforeAnyProcessStarts() = runTest {
        val process = FakeProcess()
        val exchange = KuboContextBlockExchange(process, STANDALONE)
        assertFailsWith<IllegalArgumentException> { exchange.fetch(cid, 0) }
        assertFailsWith<IllegalArgumentException> { exchange.fetch(cid, KuboContextBlockExchange.MAX_BLOCK_BYTES + 1) }
        assertFailsWith<IllegalArgumentException> { exchange.publish(ByteArray(KuboContextBlockExchange.MAX_BLOCK_BYTES + 1)) }
        assertTrue(process.calls.isEmpty(), "nothing was spawned")
    }

    @Test
    fun anEmptyBlockIsAValidBlock() = runTest {
        val process = FakeProcess()
        val exchange = KuboContextBlockExchange(process, STANDALONE)
        process.execResult = ProcessResult(0, text("bafkreihdwdcefgh4dqkjv67uzcmw7ojee6xedzdetojuzjevtenxquvyku\n"), ByteArray(0))
        assertEquals(IpnsCid.raw(ByteArray(0)), exchange.publish(ByteArray(0)))
        assertEquals(0, process.calls.single().stdin!!.size)

        process.opened = FakePipes()
        assertEquals(0, exchange.fetch(IpnsCid.raw(ByteArray(0)), 100).size)
    }

    @Test
    fun whateverTheNodeReturnsIsStillVerifiedByTheCaller() = runTest {
        val wanted = text("the bytes we asked for\n")
        val process = FakeProcess().apply { opened = FakePipes(text("different bytes entirely\n")) }
        val context = SharedContext(
            CasStore.inMemory(), KuboContextBlockExchange(process, STANDALONE),
            ContextReadPolicy(mode = SharedContextMode.PEER, allowedScopes = setOf("team/a")),
        )
        val rejected = assertFailsWith<ContextBlockException> { context.block(ContentId.of(wanted)) }
        assertEquals(ContextRejection.DIGEST_MISMATCH, rejected.reason)
    }
}
