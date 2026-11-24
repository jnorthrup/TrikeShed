package borg.trikeshed.cli.htx

import borg.trikeshed.htx.openHtxClientReactorElement
import borg.trikeshed.lib.*
import borg.trikeshed.transfer.*
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.userspace.nio.spi.NioSupervisor
import kotlinx.coroutines.*
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.*

class HtxAria2EngineTest {
    /** A file in four ranges from a local mirror whose last range waits: the row mid-flight, then complete. */
    @Test fun rowWhileItRuns(): Unit = runBlocking {
        val file = ByteArray(4 * 65536) { (it % 253).toByte() }
        val sha1 = MessageDigest.getInstance("SHA-1").digest(file).joinToString("") { "%02x".format(it) }
        val gate = CountDownLatch(1)
        val server = ServerSocket(0, 16, InetAddress.getLoopbackAddress())
        val url = "http://127.0.0.1:${server.localPort}/file.bin"
        thread(isDaemon = true) {
            while (true) {
                val socket = try { server.accept() } catch (closed: Exception) { break }
                thread(isDaemon = true) {
                    socket.use {
                        val input = it.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                        input.readLine()
                        var range = ""
                        while (true) {
                            val line = input.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.startsWith("Range:", ignoreCase = true)) range = line.substringAfter("bytes=")
                        }
                        val from = range.substringBefore('-').toInt(); val to = range.substringAfter('-').toInt()
                        if (from >= 3 * 65536) gate.await()
                        it.getOutputStream().write(("HTTP/1.1 206 Partial Content\r\nContent-Range: bytes $from-$to/${file.size}\r\n" +
                            "Content-Length: ${to - from + 1}\r\nConnection: close\r\n\r\n").encodeToByteArray() + file.copyOfRange(from, to + 1))
                    }
                }
            }
        }
        val supervisor = NioSupervisor(); supervisor.open()
        val client = openHtxClientReactorElement(nioSupervisor = supervisor)
        try {
            val engine = HtxAria2Engine(Aria2Options(uris = _l[url], split = 4, minSplitSize = 65536, checksum = "sha-1=$sha1"),
                client, requireNotNull(supervisor.service<FileOperations>()))
            assertEquals(TransferState.WAITING, engine.row().state)
            val fetching = async(Dispatchers.Default) { engine.fetch() }
            withTimeout(30_000) { while (engine.row().pieces?.have != 3) delay(10) }
            val mid = engine.row()
            assertEquals(TransferState.ACTIVE, mid.state)
            assertEquals(262144L, mid.total); assertEquals(196608L, mid.completed)
            assertEquals(TransferPieces(4, 3, 65536), mid.pieces)
            assertEquals(_l["$url HTTP TCP 196608"], mid.sources.view.map { "${it.address} ${it.protocol} ${it.transport} ${it.completed}" })
            gate.countDown()
            assertContentEquals(file, withTimeout(30_000) { fetching.await() })
            val end = engine.row()
            assertEquals(TransferState.COMPLETE, end.state)
            assertEquals(262144L, end.completed); assertEquals(TransferPieces(4, 4, 65536), end.pieces)
        } finally { gate.countDown(); client.close(); supervisor.close(); server.close() }
    }
}
