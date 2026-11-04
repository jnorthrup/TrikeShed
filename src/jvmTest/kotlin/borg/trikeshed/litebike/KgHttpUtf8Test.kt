package borg.trikeshed.litebike

import borg.trikeshed.lib.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.*

class KgHttpUtf8Test {
    @org.junit.jupiter.api.io.TempDir lateinit var directory: Path

    @Test fun malformedKgBodyRefusesBeforeRouteDispatch(): Unit = runBlocking {
        var calls = 0
        val route: ExtraRoute = { _, _, _, _ -> calls++; WireHttpResponse(200, "called") }
        val server = JvmKanbanServer(extraRoutes = _l[route], stateDir = directory.toFile())
        val malformed = s_[
            _a[0xc3.toByte(), 0x28.toByte()],
            _a[0xe2.toByte(), 0x82.toByte()],
            _a[0xed.toByte(), 0xa0.toByte(), 0x80.toByte()],
            _a[0xc0.toByte(), 0xaf.toByte()],
        ]
        for (bytes in malformed.view) {
            for (boundary in s_["\r\n", "\n"].view) {
                val body = "(pays Tenant \"".encodeToByteArray() + bytes + "\")".encodeToByteArray()
                val head = "POST /api/beliefs/kg?source=fixture HTTP/1.1${boundary}Content-Length: ${body.size}$boundary$boundary"
                val response = server.routeHttp(head.encodeToByteArray() + body)
                assertEquals(400, response.status)
                assertEquals("{\"error\":\"invalid_kg_utf8\"}", response.body)
                assertEquals(0, calls)
            }
        }
    }

    @Test fun validKgBodyKeepsOriginalUnicodeWhitespaceAndLineEndings(): Unit = runBlocking {
        var received: String? = null
        val route: ExtraRoute = { method, path, text, _ ->
            assertEquals("POST", method)
            assertEquals("/api/beliefs/kg", path)
            received = text
            WireHttpResponse(200, "called")
        }
        val server = JvmKanbanServer(extraRoutes = _l[route], stateDir = directory.toFile())
        val body = " \r\n\t(pays Tenant \"📚 café �\")\r\n "
        val head = "POST /api/beliefs/kg HTTP/1.1\r\nContent-Length: ${body.encodeToByteArray().size}\r\n\r\n"
        assertEquals(200, server.routeHttp((head + body).encodeToByteArray()).status)
        assertEquals(head + body, received)
    }

    @Test fun nonKgBodyKeepsExistingTextDecoding(): Unit = runBlocking {
        var received: String? = null
        val route: ExtraRoute = { _, _, text, _ -> received = text; WireHttpResponse(200, "called") }
        val server = JvmKanbanServer(extraRoutes = _l[route], stateDir = directory.toFile())
        val head = "POST /api/utf8-control HTTP/1.1\r\nContent-Length: 1\r\n\r\n"
        val response = server.routeHttp(head.encodeToByteArray() + _a[0xff.toByte()])
        assertEquals(200, response.status)
        assertEquals(head + "�", received)
    }
}
