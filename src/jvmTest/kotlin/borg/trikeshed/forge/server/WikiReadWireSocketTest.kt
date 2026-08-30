package borg.trikeshed.forge.server

import borg.trikeshed.litebike.JvmKanbanServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The wire's own unit tests call `route(...)` directly, which proves the handler and NOTHING
 * about whether the server ever reaches it — a route can be correct and still be shadowed by an
 * earlier entry in `rawRoutes` (CouchWire held `/` and `/sw.js` that way for two days) or lost
 * in request framing. This test goes over a real loopback socket with a real HTTP client, so a
 * dispatch or framing regression fails here rather than in front of an operator.
 */
class WikiReadWireSocketTest {

    private fun get(port: Int, path: String): Triple<Int, ByteArray, Map<String, List<String>>> {
        val conn = URI("http://127.0.0.1:$port$path").toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 5_000
        conn.readTimeout = 5_000
        return try {
            val status = conn.responseCode
            val body = (if (status < 400) conn.inputStream else conn.errorStream)?.readBytes() ?: ByteArray(0)
            Triple(status, body, conn.headerFields.filterKeys { it != null })
        } finally {
            conn.disconnect()
        }
    }

    @Test
    fun theCurationPlaneIsReadableOverRealHttp() {
        val plane = Files.createTempDirectory("wiki-socket-")
        val state = Files.createTempDirectory("wiki-socket-state-")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            plane.resolve("skills").createDirectories()
            val artifact = "# retrieval\n\nA curated skill, served as itself.\n"
            plane.resolve("skills/retrieval.md").writeText(artifact)
            plane.resolve("index.md").writeText("# index\n")

            val wire = WikiReadWire { plane.toFile() }
            val port = ServerSocket(0).use { it.localPort }
            val server = JvmKanbanServer(rawRoutes = listOf(wire::route), stateDir = state.toFile())
            scope.launch { server.run(port, null) }

            // The listener binds asynchronously; poll the surface rather than sleeping blind.
            var ready = false
            var lastError: String? = null
            for (attempt in 0 until 200) {
                val probe = runCatching { get(port, "/api/wiki/list") }
                probe.onFailure { lastError = "${it::class.simpleName}: ${it.message}" }
                    .onSuccess { lastError = "status ${it.first}: ${String(it.second).take(200)}" }
                ready = probe.getOrNull()?.first == 200
                if (ready) break
                Thread.sleep(50)
            }
            assertTrue(ready, "server did not answer /api/wiki/list on :$port — last: $lastError")

            val (listStatus, listBody, _) = get(port, "/api/wiki/list")
            assertEquals(200, listStatus)
            assertTrue(String(listBody).contains("\"path\":\"skills/retrieval.md\""), String(listBody))

            val expected = artifact.toByteArray()
            val (status, body, headers) = get(port, "/api/wiki/read?path=skills/retrieval.md")
            assertEquals(200, status)
            assertTrue(expected.contentEquals(body), "bytes over the wire must equal the file")
            // The cid travels with the bytes: the same check a validator runs against a freeze
            // record, available from the response alone.
            assertEquals(
                WikiReadWire.cidOf(expected),
                headers.entries.first { it.key.equals("X-Content-Id", ignoreCase = true) }.value.first(),
            )

            val (denied, deniedBody, _) = get(port, "/api/wiki/read?path=../../etc/passwd")
            assertEquals(403, denied)
            assertTrue(String(deniedBody).contains("outside_curation_plane"))
        } finally {
            scope.cancel()
            plane.toFile().deleteRecursively()
            state.toFile().deleteRecursively()
        }
    }
}
