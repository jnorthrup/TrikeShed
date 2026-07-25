package borg.trikeshed.jules

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class FlywheelDriverDrainSerialTest {

    private var mockServer: HttpServer? = null
    private var mockPort: Int = 0
    private val concurrentRequests = AtomicInteger(0)
    private val maxConcurrentRequests = AtomicInteger(0)
    lateinit var testRepoDir: File

    @BeforeTest
    fun setup() {
        concurrentRequests.set(0)
        maxConcurrentRequests.set(0)
        val server = HttpServer.create(InetSocketAddress(0), 0)
        mockPort = server.address.port
        mockServer = server

        server.createContext("/v1alpha/sessions") { exchange ->
            val path = exchange.requestURI.path

            if (path == "/v1alpha/sessions") {
                val response = """{
                    "sessions": [
                        {"name": "projects/123/sessions/1001", "state": "COMPLETED", "title": "Task 1", "sourceContext": {"source": "sources/github/jnorthrup/TrikeShed"}},
                        {"name": "projects/123/sessions/1002", "state": "COMPLETED", "title": "Task 2", "sourceContext": {"source": "sources/github/jnorthrup/TrikeShed"}},
                        {"name": "projects/123/sessions/1003", "state": "COMPLETED", "title": "Task 3", "sourceContext": {"source": "sources/github/jnorthrup/TrikeShed"}},
                        {"name": "projects/123/sessions/1004", "state": "COMPLETED", "title": "Task 4", "sourceContext": {"source": "sources/github/jnorthrup/TrikeShed"}},
                        {"name": "projects/123/sessions/1005", "state": "COMPLETED", "title": "Task 5", "sourceContext": {"source": "sources/github/jnorthrup/TrikeShed"}}
                    ]
                }"""
                val bytes = response.toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.write(bytes)
                exchange.close()
                return@createContext
            }

            val current = concurrentRequests.incrementAndGet()
            synchronized(maxConcurrentRequests) {
                if (current > maxConcurrentRequests.get()) {
                    maxConcurrentRequests.set(current)
                }
            }

            Thread.sleep(100)

            val response = if (path.endsWith("/activities")) {
                val sessionId = path.split("/")[3]

                runGitCmd(testRepoDir, "add", ".")
                runGitCmd(testRepoDir, "commit", "-m", "mock commit")

                val patch = """
                    diff --git a/src/$sessionId.kt b/src/$sessionId.kt
                    new file mode 100644
                    index 0000000..3b18e51
                    --- /dev/null
                    +++ b/src/$sessionId.kt
                    @@ -0,0 +1 @@
                    +val x = 1
                """.trimIndent() + "\n"

                """{
                    "activities": [
                        {
                            "name": "projects/123/sessions/$sessionId/activities/act1",
                            "createTime": "2023-01-01T00:00:00Z",
                            "artifacts": [
                                {
                                    "changeSet": {
                                        "gitPatch": { "unidiffPatch": "$patch" }
                                    }
                                }
                            ]
                        }
                    ]
                }"""
            } else {
                "{}"
            }

            val bytes = response.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.write(bytes)
            exchange.close()

            concurrentRequests.decrementAndGet()
        }
        server.start()
    }

    @AfterTest
    fun teardown() {
        mockServer?.stop(0)
    }

    @Test
    fun `drainFanout processes items serially using drainGate`() = runBlocking {
        withTimeout(30_000) {
            val root = Files.createTempDirectory("drain-serial").toFile()
            val repoDir = File(root, "work"); repoDir.mkdirs()
            val forgeDir = File(root, "forge"); forgeDir.mkdirs()
            testRepoDir = repoDir

            runGitCmd(repoDir, "init", "-q")
            runGitCmd(repoDir, "config", "user.email", "agent@trikeshed.local")
            runGitCmd(repoDir, "config", "user.name", "Agent")
            File(repoDir, "test.txt").writeText("")
            runGitCmd(repoDir, "add", "test.txt")
            runGitCmd(repoDir, "commit", "-q", "-m", "initial")
            File(repoDir, "src").mkdirs()

            val driver = FlywheelDriver(
                apiKey = "test-key",
                repoDir = repoDir,
                forgeDir = forgeDir,
                intervalMs = 100,
            )

            val customClient = JulesRestClient("test-key", "http://localhost:${mockPort}/v1alpha")
            val clientField = FlywheelDriver::class.java.getDeclaredField("client")
            clientField.isAccessible = true
            clientField.set(driver, customClient)

            val cycleLog = driver.cycle()

            assertEquals(1, maxConcurrentRequests.get(), "drainOne should execute serially")

            val tags = runGitCmd(repoDir, "tag", "-l").output.trim().lines().filter { it.isNotBlank() }
            assertEquals(5, tags.size, "Should have created 5 tags sequentially")

            for (i in 1..5) {
                assertTrue(File(repoDir, "src/100$i.kt").exists(), "Patch for 100$i should have been applied")
            }
        }
    }

    private data class CmdResult(val exitCode: Int, val output: String)

    private fun runGitCmd(dir: File, vararg args: String): CmdResult {
        val pb = ProcessBuilder(listOf("git") + args.toList())
            .directory(dir).redirectErrorStream(true)
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        return CmdResult(p.exitValue(), out)
    }
}
