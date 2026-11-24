package borg.trikeshed.torrent

import borg.trikeshed.htx.client.ipfs.MemoryBlockStore
import borg.trikeshed.lib.*
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.*

/** Engines of this tree in one swarm on loopback: real sockets, no outside network. */
class TorrentSwarmTest {
    /** 32 KiB pieces, two blocks each; nine pieces, the last 10000 bytes. */
    val pieceLength = 32768
    val content = ByteArray(8 * 32768 + 10000) { ((it * 31) xor (it ushr 9)).toByte() }
    val metainfo = metainfo("swarm.bin", content, pieceLength)
    val infoHash: ByteArray = MessageDigest.getInstance("SHA-1").digest(metainfo.copyOfRange(7, metainfo.size - 1))

    @Test fun seedToLeech(): Unit = runBlocking {
        val seed = engine(); val leech = engine()
        try {
            seed.a.seedTorrent(metainfo, content)
            val session = leech.a.addTorrent(metainfo) as TorrentSession
            session.addPeers(_l[seed.b])
            assertContentEquals(content, withTimeout(60_000) { session.downloadAll() })
            assertEquals(9, session.haveBitfield().numSet())
        } finally { leech.a.close(); seed.a.close() }
    }

    /** Two leeches take the halves from a seed that then leaves; a third completes from the two halves. */
    @Test fun halvesFromTwoPeers(): Unit = runBlocking {
        val seed = engine(); val first = engine(); val second = engine(); val third = engine()
        try {
            seed.a.seedTorrent(metainfo, content)
            val low = first.a.addTorrent(metainfo) as TorrentSession
            val high = second.a.addTorrent(metainfo) as TorrentSession
            low.addPeers(_l[seed.b]); high.addPeers(_l[seed.b])
            withTimeout(60_000) {
                for (index in 0 until 4) low.downloadPiece(index)
                for (index in 4 until 9) high.downloadPiece(index)
            }
            seed.a.close()
            val whole = third.a.addTorrent(metainfo) as TorrentSession
            whole.addPeers(_l[first.b, second.b])
            assertContentEquals(content, withTimeout(60_000) { whole.downloadAll() })
            assertEquals(4L * 32768, low.stats.value.uploadedBytes)
            assertEquals(4L * 32768 + 10000, high.stats.value.uploadedBytes)
        } finally { for (e in _l[third, second, first, seed]) e.a.close() }
    }

    /** A seed that changes one byte of every block it sends is passed over for an honest one. */
    @Test fun corruptSeedPassedOver(): Unit = runBlocking {
        val honest = engine(); val leech = engine()
        val corrupt = CorruptSeed()
        try {
            honest.a.seedTorrent(metainfo, content)
            val session = leech.a.addTorrent(metainfo) as TorrentSession
            session.addPeers(_l[PeerAddress("127.0.0.1", corrupt.server.localPort)])
            session.addPeers(_l[honest.b])
            assertContentEquals(content, withTimeout(60_000) { session.downloadAll() })
            assertTrue(corrupt.requests.get() > 0, "the corrupt seed was asked for a block")
            withTimeout(10_000) { corrupt.dropped.await() }
        } finally { leech.a.close(); honest.a.close(); corrupt.server.close() }
    }

    fun CoroutineScope.engine(): Join<TorrentEngine, PeerAddress> {
        val address = PeerAddress("127.0.0.1", ServerSocket(0).use { it.localPort })
        return TorrentEngine(MemoryBlockStore(), this, address) j address
    }

    /** Every piece, unchoked, and each block answered with its first byte changed. */
    inner class CorruptSeed {
        val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        val requests = AtomicInteger()
        val dropped = CompletableDeferred<Unit>()
        init {
            thread(isDaemon = true) {
                try {
                    server.accept().use { socket ->
                        val input = DataInputStream(socket.getInputStream()); val output = socket.getOutputStream()
                        input.readFully(ByteArray(68))
                        output.write(_a[19.toByte()] + "BitTorrent protocol".encodeToByteArray() + ByteArray(8) + infoHash + "-XX0001-corruptseed!".encodeToByteArray())
                        output.write(frame(5, _a[(-1).toByte(), 0x80.toByte()]))
                        output.write(frame(1, ByteArray(0)))
                        while (true) {
                            val length = input.readInt()
                            if (length == 0) continue
                            val id = input.readByte().toInt()
                            val payload = ByteArray(length - 1).also { input.readFully(it) }
                            if (id != 6) continue
                            requests.incrementAndGet()
                            val index = int(payload, 0); val begin = int(payload, 4); val size = int(payload, 8)
                            val at = index * pieceLength + begin
                            val block = content.copyOfRange(at, at + size).also { it[0] = (it[0] + 1).toByte() }
                            output.write(frame(7, payload.copyOfRange(0, 8) + block))
                        }
                    }
                } catch (ended: Exception) { dropped.complete(Unit) }
            }
        }
    }

    companion object {
        /** A single-file v1 metainfo, bencoded by hand: info keys in their sorted order. */
        fun metainfo(name: String, content: ByteArray, pieceLength: Int): ByteArray {
            val sha1 = MessageDigest.getInstance("SHA-1")
            val hashes = ByteArrayOutputStream()
            for (at in 0 until content.size step pieceLength) hashes.write(sha1.digest(content.copyOfRange(at, minOf(content.size, at + pieceLength))))
            return ByteArrayOutputStream().apply {
                write("d4:infod6:lengthi${content.size}e4:name${name.length}:${name}12:piece lengthi${pieceLength}e6:pieces${hashes.size()}:".encodeToByteArray())
                write(hashes.toByteArray()); write("ee".encodeToByteArray())
            }.toByteArray()
        }
        fun frame(id: Int, payload: ByteArray): ByteArray = int(payload.size + 1) + _a[id.toByte()] + payload
        fun int(value: Int): ByteArray = _a[(value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte()]
        fun int(bytes: ByteArray, at: Int): Int = (0 until 4).fold(0) { value, k -> (value shl 8) or (bytes[at + k].toInt() and 255) }
    }
}
