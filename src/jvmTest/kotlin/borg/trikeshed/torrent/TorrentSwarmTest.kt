package borg.trikeshed.torrent

import borg.trikeshed.cas.IpfsBridge
import borg.trikeshed.htx.client.ipfs.MemoryBlockStore
import borg.trikeshed.job.CasStore
import borg.trikeshed.htx.openHtxClientReactorElement
import borg.trikeshed.lib.*
import borg.trikeshed.transfer.*
import borg.trikeshed.userspace.nio.spi.NioSupervisor
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.time.TimeSource
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
        val corrupt = HandSeed(corrupt = true)
        try {
            honest.a.seedTorrent(metainfo, content)
            val session = leech.a.addTorrent(metainfo) as TorrentSession
            session.addPeers(_l[PeerAddress("127.0.0.1", corrupt.server.localPort)])
            session.addPeers(_l[honest.b])
            assertContentEquals(content, withTimeout(60_000) { session.downloadAll() })
            assertTrue(corrupt.requested.isNotEmpty(), "the corrupt seed was asked for a block")
            withTimeout(10_000) { corrupt.dropped.await() }
        } finally { leech.a.close(); honest.a.close(); corrupt.server.close() }
    }

    /** A leech that knows only a magnet (the info-hash and the seed's address) takes the metadata from the seed (BEP 9), then the content. */
    @Test fun magnetMetadataFromPeer(): Unit = runBlocking {
        val seed = engine(); val leech = engine()
        try {
            seed.a.seedTorrent(metainfo, content)
            val hex = infoHash.joinToString("") { "%02x".format(it) }
            val session = withTimeout(60_000) { leech.a.addMagnet("magnet:?xt=urn:btih:$hex&dn=swarm.bin&x.pe=127.0.0.1:${seed.b.port}") } as TorrentSession
            assertEquals("swarm.bin", session.torrentFile.info.name)
            assertEquals(9, session.torrentFile.numPieces)
            assertContentEquals(content, withTimeout(60_000) { session.downloadAll() })
        } finally { leech.a.close(); seed.a.close() }
    }

    /**
     * Wanted: all nine pieces. A slow peer that holds 0-3 is busy with piece 0 when a peer that holds all nine comes:
     * that one is first asked for piece 4, the rarest it holds, where index order would ask it for piece 1.
     */
    @Test fun rarestFirst(): Unit = runBlocking {
        val leech = engine()
        val partial = HandSeed(corrupt = false, delayMillis = 2_000, pieces = 0 until 4)
        val full = HandSeed(corrupt = false)
        try {
            val session = leech.a.addTorrent(metainfo) as TorrentSession
            val download = async { withTimeout(60_000) { session.downloadAll() } }
            delay(300)
            session.addPeers(_l[PeerAddress("127.0.0.1", partial.server.localPort)])
            delay(300)
            session.addPeers(_l[PeerAddress("127.0.0.1", full.server.localPort)])
            assertContentEquals(content, download.await())
            assertEquals(0, partial.requested.first())
            assertEquals(4, full.requested.first())
        } finally { leech.a.close(); partial.server.close(); full.server.close() }
    }

    /** The last piece in flight on a peer that answers after 20 s is taken again from a faster seed (endgame). */
    @Test fun endgame(): Unit = runBlocking {
        val fast = engine(); val leech = engine()
        val slow = HandSeed(corrupt = false, delayMillis = 20_000)
        try {
            fast.a.seedTorrent(metainfo, content)
            val session = leech.a.addTorrent(metainfo) as TorrentSession
            session.addPeers(_l[PeerAddress("127.0.0.1", slow.server.localPort)])
            session.addPeers(_l[fast.b])
            val started = TimeSource.Monotonic.markNow()
            assertContentEquals(content, withTimeout(60_000) { session.downloadAll() })
            assertTrue(slow.requested.isNotEmpty(), "the slow peer was asked")
            assertTrue(started.elapsedNow().inWholeMilliseconds < 10_000, "finished in ${started.elapsedNow()}")
        } finally { leech.a.close(); fast.a.close(); slow.server.close() }
    }

    /** A peer whose extension handshake (BEP 10) lists its keys unsorted, as some clients send it, is kept and serves the content. */
    @Test fun unsortedExtensionHandshake(): Unit = runBlocking {
        val leech = engine()
        val peer = HandSeed(corrupt = false, extension = "d1:v4:hand1:md11:ut_metadatai3eee")
        try {
            val session = leech.a.addTorrent(metainfo) as TorrentSession
            session.addPeers(_l[PeerAddress("127.0.0.1", peer.server.localPort)])
            assertContentEquals(content, withTimeout(30_000) { session.downloadAll() })
        } finally { leech.a.close(); peer.server.close() }
    }

    /** A peer that asks for all seventeen blocks at once, as a client that pipelines deeply does, is sent every one. */
    @Test fun deepRequestQueueServed(): Unit = runBlocking {
        val seed = engine()
        try {
            seed.a.seedTorrent(metainfo, content)
            val received = withContext(Dispatchers.IO) {
                Socket("127.0.0.1", seed.b.port).use { socket ->
                    socket.soTimeout = 10_000
                    val input = DataInputStream(socket.getInputStream()); val output = socket.getOutputStream()
                    output.write(_a[19.toByte()] + "BitTorrent protocol".encodeToByteArray() + ByteArray(8) + infoHash + "-XX0001-handleech001".encodeToByteArray())
                    input.readFully(ByteArray(68))
                    output.write(frame(2, ByteArray(0)))
                    while (true) {
                        val length = input.readInt()
                        if (length == 0) continue
                        val id = input.readByte().toInt(); input.readFully(ByteArray(length - 1))
                        if (id == 1) break
                    }
                    val blocks = (0 until content.size step 16384).toList()
                    for (at in blocks) output.write(frame(6, int(at / pieceLength) + int(at % pieceLength) + int(minOf(16384, content.size - at))))
                    val bytes = ByteArray(content.size)
                    var pieces = 0
                    while (pieces < blocks.size) {
                        val length = input.readInt()
                        if (length == 0) continue
                        val id = input.readByte().toInt()
                        val payload = ByteArray(length - 1).also { input.readFully(it) }
                        if (id != 7) continue
                        payload.copyInto(bytes, int(payload, 0) * pieceLength + int(payload, 4), 8, payload.size)
                        pieces++
                    }
                    bytes
                }
            }
            assertContentEquals(content, received)
        } finally { seed.a.close() }
    }

    /** Two seeds connected at once both serve the one download; at its end a piece may come from both (endgame). */
    @Test fun twoSeedsAtOnce(): Unit = runBlocking {
        val first = engine(); val second = engine(); val leech = engine()
        try {
            val one = first.a.seedTorrent(metainfo, content) as TorrentSession
            val two = second.a.seedTorrent(metainfo, content) as TorrentSession
            val session = leech.a.addTorrent(metainfo) as TorrentSession
            session.addPeers(_l[first.b, second.b])
            assertContentEquals(content, withTimeout(60_000) { session.downloadAll() })
            assertTrue(one.stats.value.uploadedBytes > 0 && two.stats.value.uploadedBytes > 0,
                "uploaded ${one.stats.value.uploadedBytes} and ${two.stats.value.uploadedBytes}")
        } finally { for (e in _l[leech, second, first]) e.a.close() }
    }

    /**
     * Two files, a piece across their edge, one peer and one web seed (BEP 19) feeding one picker; the row after it
     * holds both sources and the whole as verified.
     */
    @Test fun webSeedAndPeerFeedOnePicker(): Unit = runBlocking {
        val a = ByteArray(40000) { (it % 251).toByte() }
        val b = ByteArray(50000) { (it % 241 + 7).toByte() }
        val http = WebSeedServer(_m["swarm/a.txt" j a, "swarm/b.bin" j b])
        val both = metainfo("swarm", _l["a.txt" j a, "b.bin" j b], 32768, http.url)
        val supervisor = NioSupervisor(); supervisor.open()
        val client = openHtxClientReactorElement(nioSupervisor = supervisor)
        val seed = engine()
        val address = PeerAddress("127.0.0.1", ServerSocket(0).use { it.localPort })
        val leech = TorrentEngine(MemoryBlockStore(), CoroutineScope(coroutineContext + client), address)
        try {
            val seeding = seed.a.seedTorrent(both, a + b) as TorrentSession
            val session = leech.addTorrent(both) as TorrentSession
            session.addPeers(_l[seed.b])
            assertContentEquals(a + b, withTimeout(60_000) { session.downloadAll() })
            assertTrue(http.ranges.get() > 0 && seeding.stats.value.uploadedBytes > 0, "ranges ${http.ranges.get()}, uploaded ${seeding.stats.value.uploadedBytes}")
            val row = session.row()
            assertEquals(TransferState.COMPLETE, row.state)
            assertEquals(90000L, row.total); assertEquals(90000L, row.completed)
            assertEquals(TransferPieces(3, 3, 32768), row.pieces)
            assertEquals(_l["127.0.0.1:${seed.b.port} BITTORRENT TCP", "${http.url} HTTP TCP"],
                row.sources.view.map { "${it.address} ${it.protocol} ${it.transport}" })
            assertEquals(90000L, row.sources.view.sumOf { it.completed })
        } finally { leech.close(); seed.a.close(); client.close(); supervisor.close(); http.server.close() }
    }

    /** A leech's download lands in the IPFS store under the root Kubo 0.43.1 names the same bytes by, and its row says so. */
    @Test fun landsInIpfsStore(): Unit = runBlocking {
        val seed = engine(); val leech = engine()
        try {
            seed.a.seedTorrent(metainfo, content)
            val session = leech.a.addTorrent(metainfo) as TorrentSession
            session.addPeers(_l[seed.b])
            val store = IpfsBridge(CasStore.inMemory())
            val root = withTimeout(60_000) { session.downloadFile("swarm.bin", store) }
            assertEquals("bafybeidhrbmd7gycz7lp7nldz7go4dhnwy5cohvkih3llwdzuuacr4nocy", root.toString())
            assertContentEquals(content, store.getFile(root))
            assertEquals(root.toString(), session.row().content)
        } finally { leech.a.close(); seed.a.close() }
    }

    fun CoroutineScope.engine(): Join<TorrentEngine, PeerAddress> {
        val address = PeerAddress("127.0.0.1", ServerSocket(0).use { it.localPort })
        return TorrentEngine(MemoryBlockStore(), this, address) j address
    }

    /** Ranges of [files] under /seed/ over HTTP/1.1, one exchange a connection; it counts the ranges it served. */
    class WebSeedServer(val files: Map<String, ByteArray>) {
        val server = ServerSocket(0, 16, InetAddress.getLoopbackAddress())
        val ranges = AtomicInteger()
        val url = "http://127.0.0.1:${server.localPort}/seed/"
        init {
            thread(isDaemon = true) {
                while (true) {
                    val socket = try { server.accept() } catch (closed: Exception) { break }
                    thread(isDaemon = true) { socket.use { exchange(it) } }
                }
            }
        }
        fun exchange(socket: Socket) {
            val input = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val target = (input.readLine() ?: return).split(' ')[1]
            var range: String? = null
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Range:", ignoreCase = true)) range = line.substringAfter(':').trim().removePrefix("bytes=")
            }
            val body = files[target.removePrefix("/seed/")]
            val output = socket.getOutputStream()
            if (body == null || range == null) { output.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".encodeToByteArray()); return }
            val from = range.substringBefore('-').toInt(); val to = range.substringAfter('-').toInt()
            ranges.incrementAndGet()
            output.write("HTTP/1.1 206 Partial Content\r\nContent-Range: bytes $from-$to/${body.size}\r\nContent-Length: ${to - from + 1}\r\nConnection: close\r\n\r\n".encodeToByteArray())
            output.write(body, from, to - from + 1)
            output.flush()
        }
    }

    /**
     * A peer written by hand: [pieces], unchoked; each requested block answered after [delayMillis], with its first
     * byte changed when [corrupt]; when [extension] is given, it is the peer's extension handshake (BEP 10), sent
     * after its own handshake. [requested] lists the requested pieces in order.
     */
    inner class HandSeed(val corrupt: Boolean, val delayMillis: Long = 0, val pieces: IntRange = 0 until 9, val extension: String? = null) {
        val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        val requested: MutableList<Int> = java.util.Collections.synchronizedList(mutableListOf())
        val dropped = CompletableDeferred<Unit>()
        init {
            thread(isDaemon = true) {
                try {
                    server.accept().use { socket ->
                        val input = DataInputStream(socket.getInputStream()); val output = socket.getOutputStream()
                        fun send(bytes: ByteArray) = synchronized(output) { output.write(bytes) }
                        input.readFully(ByteArray(68))
                        send(_a[19.toByte()] + "BitTorrent protocol".encodeToByteArray() + ByteArray(8).also { if (extension != null) it[5] = 0x10 } +
                            infoHash + "-XX0001-handseed0001".encodeToByteArray())
                        extension?.let { send(frame(20, _a[0.toByte()] + it.encodeToByteArray())) }
                        send(frame(5, ByteArray(2).also { bits -> for (k in pieces) bits[k / 8] = (bits[k / 8].toInt() or (128 ushr (k % 8))).toByte() }))
                        send(frame(1, ByteArray(0)))
                        while (true) {
                            val length = input.readInt()
                            if (length == 0) continue
                            val id = input.readByte().toInt()
                            val payload = ByteArray(length - 1).also { input.readFully(it) }
                            if (id != 6) continue
                            val index = int(payload, 0); val begin = int(payload, 4); val size = int(payload, 8)
                            if (begin == 0) requested += index
                            val at = index * pieceLength + begin
                            val block = content.copyOfRange(at, at + size).also { if (corrupt) it[0] = (it[0] + 1).toByte() }
                            val reply = frame(7, payload.copyOfRange(0, 8) + block)
                            if (delayMillis == 0L) send(reply)
                            else thread(isDaemon = true) { Thread.sleep(delayMillis); runCatching { send(reply) } }
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
        /** A multi-file v1 metainfo with one web seed, bencoded by hand: keys in their sorted order. */
        fun metainfo(name: String, files: List<Join<String, ByteArray>>, pieceLength: Int, webSeed: String): ByteArray {
            val sha1 = MessageDigest.getInstance("SHA-1")
            val whole = files.fold(ByteArray(0)) { bytes, file -> bytes + file.b }
            val hashes = ByteArrayOutputStream()
            for (at in 0 until whole.size step pieceLength) hashes.write(sha1.digest(whole.copyOfRange(at, minOf(whole.size, at + pieceLength))))
            return ByteArrayOutputStream().apply {
                write("d4:infod5:filesl".encodeToByteArray())
                for (file in files) write("d6:lengthi${file.b.size}e4:pathl${file.a.length}:${file.a}ee".encodeToByteArray())
                write("e4:name${name.length}:${name}12:piece lengthi${pieceLength}e6:pieces${hashes.size()}:".encodeToByteArray())
                write(hashes.toByteArray())
                write("e8:url-listl${webSeed.length}:${webSeed}ee".encodeToByteArray())
            }.toByteArray()
        }
        fun frame(id: Int, payload: ByteArray): ByteArray = int(payload.size + 1) + _a[id.toByte()] + payload
        fun int(value: Int): ByteArray = _a[(value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte()]
        fun int(bytes: ByteArray, at: Int): Int = (0 until 4).fold(0) { value, k -> (value shl 8) or (bytes[at + k].toInt() and 255) }
    }
}
