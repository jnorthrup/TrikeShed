package borg.trikeshed.forge.ui

import com.sun.net.httpserver.*
import kotlin.test.*
import java.net.HttpURLConnection
import java.net.URL

/**
 * Test ReactorServer: verify the server works end-to-end.
 */
class ReactorServerTest {

    @Test
    fun serverObjectExists() {
        assertNotNull(ReactorServer)
        assertFalse(ReactorServer.isRunning, "Server should not be running initially")
    }

    @Test
    fun serverStartAndStop() {
        val httpServer = ReactorServer.start(port = 0)
        try {
            assertTrue(httpServer.address.port > 0, "Server should have a port assigned")
            assertTrue(ReactorServer.isRunning, "Server should be running after start")
        } finally {
            ReactorServer.stop()
        }
        assertFalse(ReactorServer.isRunning, "Server should not be running after stop")
    }

    @Test
    fun rootEndpointResponds() {
        val httpServer = ReactorServer.start(port = 0)
        try {
            val port = httpServer.address.port
            assertTrue(port > 0, "Server should have a port")
            
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
    fun sseEndpointExists() {
        val httpServer = ReactorServer.start(port = 0)
        try {
            val port = httpServer.address.port
            
            val url = URL("http://localhost:$port/events")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.connect()
            
            assertEquals(200, conn.responseCode, "SSE endpoint should return 200")
            assertEquals("text/event-stream", conn.contentType, "SSE should have event-stream content type")
        } finally {
            ReactorServer.stop()
        }
    }
}