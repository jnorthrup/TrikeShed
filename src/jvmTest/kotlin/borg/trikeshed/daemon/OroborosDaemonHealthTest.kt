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

class OroborosDaemonHealthTest {
    private lateinit var tempDir: File
    private lateinit var forgeHome: File
    private lateinit var repoDir: File

    @BeforeEach
    fun setup() {
        tempDir = File.createTempFile("oroboros_test", "")
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
            OroborosDaemon.main(arrayOf("--once", "--interval-ms", "1000", forgeHome.absolutePath, repoDir.absolutePath))
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
        
        assertTrue(response.startsWith("ALIVE"), "Response should start with ALIVE")
        val parts = response.trim().split(" ")
        assertTrue(parts.size == 7, "Response should have 7 parts")
        val uptimeMs = parts[1].toLongOrNull()
        assertTrue(uptimeMs != null && uptimeMs >= 0, "uptimeMs should be valid")

        job.cancel()
        job.join()
        System.clearProperty("JULES_API_KEY")
    }
}
