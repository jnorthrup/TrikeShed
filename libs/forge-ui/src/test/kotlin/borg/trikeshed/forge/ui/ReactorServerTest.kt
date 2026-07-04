package borg.trikeshed.forge.ui

import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Test ReactorServer: verify the server works end-to-end.
 */
class ReactorServerTest {

    private fun awaitServerPort(): Int {
        repeat(300) {
            val port = ReactorServer.boundPort
            if (port > 0) return port
            Thread.sleep(20)
        }
        fail("Server never bound an ephemeral port")
    }

    private fun readSsePrefix(conn: HttpURLConnection, maxLines: Int = 256): String {
        val reader = conn.inputStream.bufferedReader()
        val lines = mutableListOf<String>()
        for (index in 0 until maxLines) {
            val line = reader.readLine() ?: break
            lines += line
            if (line.contains("\"type\":\"TaxonomyNodeCreated\"")) break
        }
        return lines.joinToString("\n")
    }

    @Test
    fun serverObjectExists() {
        assertNotNull(ReactorServer)
        assertFalse(ReactorServer.isRunning, "Server should not be running initially")
        assertEquals(-1, ReactorServer.boundPort)
    }

    @Test
    fun serverStartAndStop() {
        ReactorServer.start(port = 0)
        try {
            val port = awaitServerPort()
            assertTrue(port > 0, "Server should have a port assigned")
            assertTrue(ReactorServer.isRunning, "Server should be running after start")
        } finally {
            ReactorServer.stop()
        }
        assertFalse(ReactorServer.isRunning, "Server should not be running after stop")
        assertEquals(-1, ReactorServer.boundPort)
    }

    @Test
    fun serverCanRestartAfterStop() {
        ReactorServer.start(port = 0)
        try {
            assertTrue(awaitServerPort() > 0)
        } finally {
            ReactorServer.stop()
        }

        Thread.sleep(50)

        ReactorServer.start(port = 0)
        try {
            assertTrue(awaitServerPort() > 0, "Server should bind again after stop")
        } finally {
            ReactorServer.stop()
        }
    }

    @Test
    fun rootEndpointResponds() {
        ReactorServer.start(port = 0)
        try {
            val port = awaitServerPort()

            val url = URL("http://localhost:$port/")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.connect()

            assertEquals(200, conn.responseCode, "Root endpoint should return 200")

            val body = conn.inputStream.bufferedReader().readText()
            assertTrue(body.contains("Forge UI Reactor"), "Root should contain title")
        } finally {
            ReactorServer.stop()
        }
    }

    @Test
    fun taxonomyEndpointRespondsAndSseCarriesTaxonomyPayload() {
        ReactorServer.start(port = 0)
        try {
            val port = awaitServerPort()

            val taxonomyUrl = URL("http://localhost:$port/taxonomy?topic=REST+APIs")
            val taxonomyConn = taxonomyUrl.openConnection() as HttpURLConnection
            taxonomyConn.connectTimeout = 5000
            taxonomyConn.readTimeout = 5000
            taxonomyConn.connect()

            assertEquals(200, taxonomyConn.responseCode, "Taxonomy endpoint should return 200")
            val taxonomyBody = taxonomyConn.inputStream.bufferedReader().readText()
            assertTrue(taxonomyBody.contains("REST APIs"), "Taxonomy response should echo the topic")

            val sseUrl = URL("http://localhost:$port/events")
            val sseConn = sseUrl.openConnection() as HttpURLConnection
            sseConn.connectTimeout = 5000
            sseConn.readTimeout = 5000
            sseConn.connect()

            assertEquals(200, sseConn.responseCode, "SSE endpoint should return 200")
            assertEquals("text/event-stream", sseConn.contentType, "SSE should have event-stream content type")

            val payload = readSsePrefix(sseConn)
            assertTrue(payload.contains("\"type\":\"TaxonomyNodeCreated\""), "SSE should replay taxonomy events. Payload was: $payload")
            assertTrue(payload.contains("\"label\":\"REST APIs\""), "SSE should carry taxonomy labels. Payload was: $payload")
            assertTrue(payload.contains("\"kind\":"), "SSE should carry taxonomy kinds. Payload was: $payload")
        } finally {
            ReactorServer.stop()
        }
    }
}