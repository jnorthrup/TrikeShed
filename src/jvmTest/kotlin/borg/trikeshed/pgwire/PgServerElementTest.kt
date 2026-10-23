package borg.trikeshed.pgwire

import borg.trikeshed.couch.Couch
import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.couch.CouchTables
import borg.trikeshed.cursor.query.Catalog
import borg.trikeshed.cursor.query.PostgrestSurface
import borg.trikeshed.job.CasStore
import kotlinx.coroutines.runBlocking
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.ConnectException
import java.net.Socket
import kotlin.test.*

/** The listener end to end: a JDK socket speaks the bytes psql and pgjdbc send, the element answers over the userspace ring. */
class PgServerElementTest {
    private val cas = CasStore.inMemory()
    private val tables = CouchTables(Couch("trikeshed", CouchStoreFactory.casBacked(cas), cas))
    private val surface = PostgrestSurface(Catalog(listOf({ tables.tables() })), tables)

    private fun frame(type: Char, payload: ByteArray): ByteArray {
        val out = ByteArray(5 + payload.size)
        out[0] = type.code.toByte()
        val length = 4 + payload.size
        out[1] = (length shr 24).toByte(); out[2] = (length shr 16).toByte(); out[3] = (length shr 8).toByte(); out[4] = length.toByte()
        payload.copyInto(out, 5)
        return out
    }

    private fun cstrings(vararg parts: String): ByteArray = parts.joinToString("") { it + "\u0000" }.encodeToByteArray()

    private fun startup(vararg parameters: Pair<String, String>): ByteArray {
        val body = parameters.flatMap { listOf(it.first, it.second) }.toTypedArray().let { cstrings(*it) } + 0
        val length = 8 + body.size
        return byteArrayOf((length shr 24).toByte(), (length shr 16).toByte(), (length shr 8).toByte(), length.toByte(), 0, 3, 0, 0) + body
    }

    /** Backend frames up to and including ReadyForQuery. */
    private fun readUntilReady(input: DataInputStream): List<Pair<Char, ByteArray>> {
        val out = ArrayList<Pair<Char, ByteArray>>()
        while (true) {
            val type = input.readByte().toInt().toChar()
            val length = input.readInt()
            val payload = ByteArray(length - 4).also { input.readFully(it) }
            out += type to payload
            if (type == 'Z') return out
        }
    }

    private fun Socket.streams() = DataOutputStream(getOutputStream()) to DataInputStream(getInputStream())

    @Test fun psqlShapedClientsConnectQueryAndLeave() = runBlocking<Unit> {
        val element = PgServerElement(this, surface, "127.0.0.1:0", maxConnections = 4).open()
        val port = assertNotNull(element.localAddress).substringAfterLast(':').toInt()
        try {
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 15_000
                val (out, input) = socket.streams()
                // libpq's default sslmode=prefer: SSLRequest first, then the startup in two writes
                out.write(byteArrayOf(0, 0, 0, 8, 4, 0xd2.toByte(), 0x16, 0x2f)); out.flush()
                assertEquals('N'.code, input.read())
                val hello = startup("user" to "trikeshed", "database" to "trikeshed", "client_encoding" to "UTF8", "application_name" to "psql")
                out.write(hello, 0, 7); out.flush()
                Thread.sleep(60)
                out.write(hello, 7, hello.size - 7); out.flush()
                val ready = readUntilReady(input)
                assertEquals('R', ready.first().first)
                assertEquals("I", ready.last().second.decodeToString())
                assertTrue(ready.any { it.first == 'S' && it.second.decodeToString().startsWith("server_version\u0000") })
                out.write(frame('Q', cstrings("CREATE TABLE notes (id bigint primary key, body text)"))); out.flush()
                assertEquals("CZ", readUntilReady(input).joinToString("") { it.first.toString() })
                out.write(frame('Q', cstrings("SELECT 1 AS one; SELECT table_name FROM information_schema.tables"))); out.flush()
                val answered = readUntilReady(input)
                assertEquals("TDCTDCZ", answered.joinToString("") { it.first.toString() })
                assertEquals("\u0000\u0001\u0000\u0000\u0000\u0001" + "1", answered[1].second.decodeToString())
                assertTrue(answered[4].second.decodeToString().endsWith("notes"))
                // the extended protocol in one write, as pgjdbc sends it
                val batch = frame('P', cstrings("", "SELECT body FROM notes WHERE id = \$1") + byteArrayOf(0, 0)) +
                    frame('B', cstrings("", "") + byteArrayOf(0, 0, 0, 1, 0, 0, 0, 1) + "7".encodeToByteArray() + byteArrayOf(0, 0)) +
                    frame('D', byteArrayOf('P'.code.toByte()) + cstrings("")) +
                    frame('E', cstrings("") + byteArrayOf(0, 0, 0, 0)) +
                    frame('S', ByteArray(0))
                out.write(batch); out.flush()
                assertEquals("12TCZ", readUntilReady(input).joinToString("") { it.first.toString() })
                out.write(frame('X', ByteArray(0))); out.flush()
                assertEquals(-1, input.read(), "Terminate closes the connection")
            }
            // the accept stage goes on after a session ends; a malformed startup is refused and closed
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 15_000
                val (out, input) = socket.streams()
                out.write(byteArrayOf(0, 0, 0, 8, 0, 2, 0, 0)); out.flush()
                val type = input.readByte().toInt().toChar()
                assertEquals('E', type)
                val payload = ByteArray(input.readInt() - 4).also { input.readFully(it) }
                assertTrue("08P01" in payload.decodeToString(), payload.decodeToString())
                assertFailsWith<EOFException> { input.readByte() }
            }
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 15_000
                val (out, input) = socket.streams()
                out.write(startup("user" to "u", "database" to "trikeshed")); out.flush()
                assertEquals('R', readUntilReady(input).first().first)
            }
        } finally {
            element.drain()
        }
        assertFailsWith<ConnectException> { Socket("127.0.0.1", port).close() }
        assertEquals(0, element.connected())
    }

    @Test fun drainClosesIdleSessionsAndTheListener() = runBlocking<Unit> {
        val element = PgServerElement(this, surface, "127.0.0.1:0", maxConnections = 2).open()
        val port = element.localAddress!!.substringAfterLast(':').toInt()
        val idle = Socket("127.0.0.1", port).also { it.soTimeout = 15_000 }
        val (out, input) = idle.streams()
        out.write(startup("user" to "u", "database" to "trikeshed")); out.flush()
        readUntilReady(input)
        element.drain()
        assertEquals(-1, input.read(), "an idle session is closed by drain")
        idle.close()
        assertFailsWith<ConnectException> { Socket("127.0.0.1", port).close() }
    }
}
