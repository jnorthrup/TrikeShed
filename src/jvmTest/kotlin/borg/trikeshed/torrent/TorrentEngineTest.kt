package borg.trikeshed.torrent

import borg.trikeshed.htx.client.ipfs.MemoryBlockStore
import borg.trikeshed.job.sha256
import borg.trikeshed.lib.j
import borg.trikeshed.lib.toSeries
import kotlinx.coroutines.*
import java.net.ServerSocket
import kotlin.test.*

/** Port reservation is an alien test fixture; both torrent engines use only the common uring transport. */
class TorrentEngineTest {
    private fun endpoint(): PeerAddress = ServerSocket(0).use { PeerAddress("127.0.0.1", it.localPort) }
    private fun integer(value: Int) = BencodeValue.Integer(value.toLong())
    private fun bytes(value: ByteArray) = BencodeValue.Bytes(value)
    private fun text(value: String) = bytes(value.encodeToByteArray())
    private fun dict(vararg values: Pair<String, BencodeValue>) = TorrentBencode.dictionary(*values)
    private fun v1(content: ByteArray): ByteArray {
        val size = 65536
        val hashes = (content.indices step size).flatMap { start ->
            Sha1.digest(content.copyOfRange(start, minOf(content.size, start + size))).asList()
        }.toByteArray()
        return TorrentBencode.encode(dict("info" to dict("length" to integer(content.size),
            "name" to text("wire-data"), "piece length" to integer(size), "pieces" to bytes(hashes),
            "x-fixture" to bytes(ByteArray(20000) { (it * 31).toByte() }))))
    }
    private fun v2(content: ByteArray): ByteArray {
        require(content.size == 65537)
        val p0 = sha256(sha256(content.copyOfRange(0, 16384)) + sha256(content.copyOfRange(16384, 32768)))
        val p1 = sha256(sha256(content.copyOfRange(32768, 49152)) + sha256(content.copyOfRange(49152, 65536)))
        val p2 = sha256(sha256(content.copyOfRange(65536, 65537)) + ByteArray(32))
        val pad = sha256(ByteArray(64))
        val root = sha256(sha256(p0 + p1) + sha256(p2 + pad))
        val info = dict("file tree" to dict("data" to dict("" to dict("length" to integer(content.size), "pieces root" to bytes(root)))),
            "meta version" to integer(2), "name" to text("wire-v2"), "piece length" to integer(32768),
            "x-fixture" to bytes(ByteArray(20000) { (it * 17).toByte() }))
        val layers = BencodeValue.Dictionary(listOf(root j bytes(p0 + p1 + p2)).toSeries())
        return TorrentBencode.encode(dict("info" to info, "piece layers" to layers))
    }

    @Test fun v1SeedToDownloadTransfersPipelinedBlocksAndVerifiesLastPiece(): Unit = runBlocking {
        withTimeout(30000) {
            val content = ByteArray(100003) { ((it * 17 + 9) % 251).toByte() }
            val metadata = v1(content)
            val address = endpoint()
            val seed = TorrentEngine(MemoryBlockStore(), this, address)
            val client = TorrentEngine(MemoryBlockStore(), this, null)
            try {
                seed.seedTorrent(metadata, content)
                val download = client.addTorrent(metadata) as TorrentSession
                download.addPeers(listOf(address))
                assertContentEquals(content, download.downloadAll())
                assertEquals(2, download.numPieces)
                assertEquals(34467, download.torrentFile.pieceLength(1))
                assertEquals(2, download.haveBitfield().numSet())
                assertEquals(content.size.toLong(), download.stats.value.downloadedBytes)
                assertEquals(1000, download.stats.value.progressPermille)
            } finally { withContext(NonCancellable) { client.close(); seed.close() } }
        }
    }

    @Test fun v2MagnetFetchesMetadataAndHashLayerBeforeVerifiedPayload(): Unit = runBlocking {
        withTimeout(30000) {
            val content = ByteArray(65537) { ((it * 23 + 5) % 251).toByte() }
            val metadata = v2(content)
            val file = TorrentFile.parse(metadata)
            val address = endpoint()
            val seed = TorrentEngine(MemoryBlockStore(), this, address)
            val client = TorrentEngine(MemoryBlockStore(), this, null)
            try {
                seed.seedTorrent(metadata, content)
                val uri = "magnet:?xt=urn:btmh:1220${file.infoHash.hex()}&x.pe=${address.host}:${address.port}"
                val download = client.addMagnet(uri) as TorrentSession
                assertContentEquals(file.infoBytes(), download.torrentFile.infoBytes())
                assertTrue(download.torrentFile.pieceLayersComplete)
                assertContentEquals(content, download.downloadAll())
                assertEquals(3, download.haveBitfield().numSet())
            } finally { withContext(NonCancellable) { client.close(); seed.close() } }
        }
    }

    @Test fun invalidSeedPublishesNoPiecesAndDoesNotBecomeDownloadable(): Unit = runBlocking {
        withTimeout(15000) {
            val content = ByteArray(100003) { (it % 251).toByte() }
            val metadata = v1(content)
            val engine = TorrentEngine(MemoryBlockStore(), this, null)
            try {
                val bad = content.copyOf().also { it[70000] = (it[70000].toInt() xor 1).toByte() }
                assertFailsWith<IllegalArgumentException> { engine.seedTorrent(metadata, bad) }
                val session = engine.getTorrent(TorrentFile.parse(metadata).infoHash) as TorrentSession
                assertEquals(0, session.haveBitfield().numSet(), "All seed pieces must verify before any become available")
                assertEquals(0, session.stats.value.progressPermille)
                session.seed(content)
                assertContentEquals(content, session.downloadAll())
            } finally { withContext(NonCancellable) { engine.close() } }
        }
    }
}
