package borg.trikeshed.daemon

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import kotlin.test.assertTrue

/** A port the OS says is free right now — isolates this boot from a dev daemon
 *  on 8888 and from other tests, without relying on port-0 ephemeral binding. */
private fun freePort(): Int = java.net.ServerSocket(0).use { it.localPort }

class OroborosDaemonHealthTest {
    private lateinit var tempDir: File
    private lateinit var forgeHome: File
    private lateinit var repoDir: File

    @BeforeEach
    fun setup() {
        tempDir = File.createTempFile("odt", "")
        tempDir.delete()
        tempDir.mkdirs()
        forgeHome = File(tempDir, "forge")
        forgeHome.mkdirs()
        repoDir = File(tempDir, "repo")
        repoDir.mkdirs()
        File(repoDir, ".git").mkdirs()
    }

    @AfterEach
    fun teardown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun testHealthEndpoint(): Unit = runBlocking {
        System.setProperty("JULES_API_KEY", "dummy-key")

        
        val job = launch(Dispatchers.IO) {
            // --kanban-port 0 = let the OS pick. Without it the daemon takes
            // DEFAULT_KANBAN_PORT (8888), so running this suite while a dev
            // daemon is up burned five bind retries with stack traces and then
            // ran HEADLESS — the test passed against a daemon with no HTTP tier,
            // which is worse than failing. An ephemeral port also lets these
            // tests run concurrently without fighting each other.
            OroborosDaemon.main(arrayOf("--once", "--interval-ms", "1000", "--kanban-port", freePort().toString(), forgeHome.absolutePath, repoDir.absolutePath))
        }

        val healthSock = File(forgeHome, ".oroboros/health.sock")
        var retries = 50
        while (!healthSock.exists() && retries > 0) {
            delay(100)
            retries--
        }
        assertTrue(healthSock.exists(), "health.sock was not created")

        val client = withContext(Dispatchers.IO) {
            SocketChannel.open(StandardProtocolFamily.UNIX)
        }
        withContext(Dispatchers.IO) {
            client.connect(UnixDomainSocketAddress.of(healthSock.toPath()))
        }

        val buf = ByteBuffer.allocate(1024)
        var bytesRead = 0
        withContext(Dispatchers.IO) {
            bytesRead = client.read(buf)
            client.close()
        } 

        assertTrue(bytesRead > 0, "No bytes read from health socket")
        val response = String(buf.array(), 0, bytesRead)

        System.err.println("Daemon response: $response")

        // The reply is TWO lines: the backward-compatible ALIVE line and a METRICS
        // line. This used to split the whole response on spaces and expect 7
        // parts, which counted the METRICS line's tokens too — it predates that
        // line. Parse per line.
        assertTrue(response.startsWith("ALIVE"), "Response should start with ALIVE")
        val lines = response.trim().lines()
        val alive = lines[0].split(" ")
        assertTrue(alive.size == 7, "ALIVE line should have 7 parts, got ${alive.size}: ${lines[0]}")
        val uptimeMs = alive[1].toLongOrNull()
        assertTrue(uptimeMs != null && uptimeMs >= 0, "uptimeMs should be valid")

        // METRICS is the payload worth having: health that answers DURING boot is
        // the whole point of binding this socket before the slow work.
        val metrics = lines.firstOrNull { it.startsWith("METRICS ") }
        assertTrue(metrics != null, "Response should carry a METRICS line, got: $response")
        assertTrue(metrics!!.contains("uptimeMs"), "METRICS should report uptimeMs: $metrics")

        job.cancel()
        job.join()
        System.clearProperty("JULES_API_KEY")
    }
}
