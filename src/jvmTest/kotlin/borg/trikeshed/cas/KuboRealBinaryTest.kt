package borg.trikeshed.cas

import borg.trikeshed.agent.modelmux.ContextReadPolicy
import borg.trikeshed.agent.modelmux.SharedContext
import borg.trikeshed.agent.modelmux.SharedContextMode
import borg.trikeshed.ipns.IpnsCid
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.userspace.nio.channels.spi.JvmProcessOperations
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.file.Files
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Opt-in: runs the INSTALLED Kubo binary, offline, against a repository this test creates in a fresh temporary directory.
 * It checks that our CID construction, argument vectors, bounded reads and pin receipts agree with the real node.
 * It starts no daemon, uses no network and never touches any other repository (IPFS_PATH is always the temporary one).
 *
 * It does NOT prove peer transfer; that needs two operator-provided nodes (see SharedContextPeerIntegrationTest).
 *
 *   TRIKESHED_TEST_KUBO_BIN=/opt/homebrew/bin/ipfs ./gradlew --offline :jvmTest --rerun \
 *       --tests 'borg.trikeshed.cas.KuboRealBinaryTest'
 *
 * Without the variable every test is SKIPPED (not passed).
 */
class KuboRealBinaryTest {
    private val binary: String? = System.getenv("TRIKESHED_TEST_KUBO_BIN")

    private fun <T> withNode(body: suspend (KuboContextBlockExchange, JvmProcessOperations, KuboNode) -> T): T {
        assumeTrue(binary != null, "set TRIKESHED_TEST_KUBO_BIN to the installed ipfs binary to run this against a throwaway repository")
        val dir = Files.createTempDirectory("kubo-real-").toFile()
        try {
            val repo = File(dir, "repo").absolutePath
            val node = KuboNode(repo)
            val process = JvmProcessOperations()
            return runBlocking {
                val init = process.exec(binary!!, listOf("init", "--profile=test", "--empty-repo"), null, mapOf("IPFS_PATH" to repo))
                assertEquals(0, init.exitCode, "ipfs init failed: ${init.stderr.decodeToString()}")
                body(KuboContextBlockExchange(process, node, binary, timeoutSeconds = 20), process, node)
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun pseudoRandom(size: Int) = ByteArray(size) { ((it.toLong() * 2654435761L) ushr 13).toByte() }

    @Test
    fun publishedIdentityEqualsTheLocalRawCidAndBytesComeBackIdentical() = withNode { exchange, _, _ ->
        val blocks = listOf(
            ByteArray(0),
            "hello\n".encodeToByteArray(),
            ByteArray(256) { it.toByte() } + byteArrayOf(0, 0, 0, 13, 10, 13, 10, -1, -2),
            pseudoRandom(1_000_000),
        )
        for (bytes in blocks) {
            val local = IpnsCid.raw(bytes)
            assertEquals(local, exchange.publish(bytes), "the node and the local construction name ${bytes.size} bytes identically")
            assertContentEquals(bytes, exchange.fetch(local, maxOf(1, bytes.size)), "${bytes.size} bytes round-trip through the real CLI")
        }
        assertEquals("bafkreicysg23kiwv34eg2d7qweipxwosdo2py4ldv42nbauguluen5v6am", IpnsCid.raw("hello\n".encodeToByteArray()).toString())
    }

    @Test
    fun theCeilingStopsARealOversizeFetchAndAnExactFitSucceeds() = withNode { exchange, _, _ ->
        val bytes = pseudoRandom(65_536)
        val cid = exchange.publish(bytes)
        val refused = assertFailsWith<BlockTooLarge> { exchange.fetch(cid, 1_024) }
        assertEquals(1_024, refused.limit)
        assertFailsWith<BlockTooLarge> { exchange.fetch(cid, 65_535) }
        assertContentEquals(bytes, exchange.fetch(cid, 65_536))
    }

    @Test
    fun aMissingBlockFailsFastWithTheNodesOwnDiagnostic() = withNode { exchange, _, _ ->
        val absent = IpnsCid.raw("never stored".encodeToByteArray())
        var failure: KuboCommandFailed? = null
        val millis = measureTimeMillis { failure = assertFailsWith<KuboCommandFailed> { exchange.fetch(absent, 1_024) } }
        assertEquals(1, failure!!.exitCode)
        assertTrue("not found" in failure!!.stderr, failure!!.stderr)
        assertTrue(millis < 15_000, "an offline miss must not wait for a network: $millis ms")
    }

    @Test
    fun aPinIsAReceiptForThatBlockOnly() = withNode { exchange, process, node ->
        val kept = exchange.publish("keep me\n".encodeToByteArray())
        val other = exchange.publish("not pinned\n".encodeToByteArray())
        exchange.pin(kept)
        val listed = process.exec(binary!!, listOf("--offline", "pin", "ls", "--type=direct"), null, mapOf("IPFS_PATH" to node.repo))
        val pins = listed.stdout.decodeToString()
        assertTrue(kept.toString() in pins, pins)
        assertTrue(other.toString() !in pins, "publishing never pins; only pin() does: $pins")
        assertFailsWith<KuboCommandFailed> { exchange.pin(IpnsCid.raw("absent".encodeToByteArray())) }
    }

    @Test
    fun aFreshLocalStoreAcquiresAndVerifiesABlockFromTheRealNode() = withNode { exchange, _, _ ->
        val bytes = "evidence line one\nevidence line two\n".encodeToByteArray()
        exchange.publish(bytes)
        val id = ContentId.of(bytes)
        val cas = CasStore.inMemory()
        val context = SharedContext(cas, exchange, ContextReadPolicy(mode = SharedContextMode.PEER, allowedScopes = setOf("team/a")))
        assertEquals(null, cas.get(id), "the local store starts empty")
        assertContentEquals(bytes, context.block(id).bytes)
        assertContentEquals(bytes, cas.get(id), "the verified block was admitted")
    }
}
