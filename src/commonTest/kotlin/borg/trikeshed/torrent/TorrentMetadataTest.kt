package borg.trikeshed.torrent

import borg.trikeshed.job.sha256
import borg.trikeshed.htx.client.ipfs.MemoryBlockStore
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import borg.trikeshed.lib.view
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class TorrentMetadataTest {
    private fun str(value: String) = BencodeValue.Bytes(value.encodeToByteArray())
    private fun bin(value: ByteArray) = BencodeValue.Bytes(value)
    private fun int(value: Long) = BencodeValue.Integer(value)
    private fun dict(vararg entries: Pair<String, BencodeValue>) = TorrentBencode.dictionary(*entries)
    private fun list(vararg values: BencodeValue) = BencodeValue.ListValue(values.toList().toSeries())
    private fun encode(value: BencodeValue) = TorrentBencode.encode(value)
    private fun metainfo(info: BencodeValue, layers: BencodeValue? = null): ByteArray = encode(
        if (layers == null) dict("info" to info) else dict("info" to info, "piece layers" to layers))
    private fun v1(data: ByteArray, size: Int = 4, extra: Pair<String, BencodeValue>? = null): BencodeValue.Dictionary {
        val hashes = data.asList().chunked(size).flatMap { Sha1.digest(it.toByteArray()).asList() }.toByteArray()
        val entries = mutableListOf("name" to str("sample"), "length" to int(data.size.toLong()), "piece length" to int(size.toLong()), "pieces" to bin(hashes))
        extra?.let { entries += it }
        return dict(*entries.toTypedArray())
    }
    private fun v2Info(length: Long, root: ByteArray, pieceSize: Int = 32768): BencodeValue.Dictionary = dict(
        "file tree" to dict("file" to dict("" to dict("length" to int(length), "pieces root" to bin(root)))),
        "meta version" to int(2), "name" to str("sample"), "piece length" to int(pieceSize.toLong()))
    private fun layer(root: ByteArray, hashes: ByteArray): BencodeValue.Dictionary =
        BencodeValue.Dictionary(listOf(root j bin(hashes)).toSeries())

    @Test fun rawInfoIncludesUnknownBinaryFieldsAndIdentityHasContentEquality() {
        val data = byteArrayOf(0, -1, 127, -128, 1)
        val info = v1(data, extra = "unknown" to dict("binary" to bin(byteArrayOf(-1, 0, -128))))
        val raw = encode(info)
        val torrent = TorrentFile.parse(metainfo(info))
        assertContentEquals(raw, torrent.infoBytes())
        assertContentEquals(Sha1.digest(raw), torrent.infoHash.bytes)
        assertEquals(InfoHash(Sha1.digest(raw)), torrent.infoHash)
        assertEquals("kept", hashMapOf(torrent.infoHash to "kept")[InfoHash(Sha1.digest(raw))])
        val hash = torrent.infoHash.bytes; hash.fill(0)
        val copied = torrent.infoBytes(); copied.fill(0)
        assertContentEquals(raw, torrent.infoBytes())
        assertContentEquals(Sha1.digest(raw), torrent.infoHash.bytes)
        assertTrue(torrent.verifyPiece(0, data.copyOf(4)))
        assertTrue(torrent.verifyPiece(1, byteArrayOf(1)))
        assertFalse(torrent.verifyPiece(1, byteArrayOf(2)))
        assertFalse(torrent.verifyPiece(1, ByteArray(4)))
        assertEquals(1, torrent.pieceLength(1))
        assertFailsWith<IllegalArgumentException> { torrent.infoHashV2() }
        val full = ByteArray(32) { it.toByte() }; val id = InfoHash(full); full.fill(0)
        assertContentEquals(ByteArray(20) { it.toByte() }, id.wireBytes)
        assertNotEquals(InfoHash(id.wireBytes), id)
    }

    @Test fun bencodeIsBinarySafeAndRejectsMalformedWire() {
        val binary = BencodeValue.Dictionary(listOf(byteArrayOf(0) j bin(byteArrayOf(-1)), byteArrayOf(-1) j int(1)).toSeries())
        assertContentEquals(encode(binary), encode(TorrentBencode.decode(encode(binary))))
        val nested = "d1:ali1eli2eli3eeeee".encodeToByteArray()
        assertContentEquals(nested, encode(TorrentBencode.decode(nested)))
        for (bad in listOf("i-0e", "i01e", "i9223372036854775808e", "i1", "3:ab", "01:a", "l1:a", "d1:bi1e1:ai2ee", "d1:ai1e1:ai2ee", "degarbage", "di1e1:ae"))
            assertFailsWith<IllegalArgumentException>(bad) { TorrentBencode.decode(bad.encodeToByteArray()) }
        val prefix = TorrentBencode.decodePrefix("d5:piecei0ee".encodeToByteArray() + byteArrayOf(-1, 0))
        assertEquals(12, prefix.b)
        assertFailsWith<IllegalArgumentException> { TorrentBencode.decode(("l".repeat(66) + "e".repeat(66)).encodeToByteArray()) }
    }

    @Test fun v1MultifilePiecesCrossFileBoundariesAndValidateLayout() {
        val payload = "abcdef".encodeToByteArray()
        val files = list(dict("length" to int(2), "path" to list(str("a"))),
            dict("length" to int(0), "path" to list(str("empty"))), dict("length" to int(4), "path" to list(str("b"))))
        val info = dict("files" to files, "name" to str("root"), "piece length" to int(4),
            "pieces" to bin(Sha1.digest(payload.copyOf(4)) + Sha1.digest(payload.copyOfRange(4, 6))))
        val torrent = TorrentFile.parse(metainfo(info))
        assertEquals(6L, torrent.totalSize); assertEquals(2, torrent.numPieces)
        assertEquals(listOf(0L, 2L, 2L), torrent.files.view.map { it.offset })
        assertTrue(torrent.verifyPiece(0, "abcd".encodeToByteArray()))
        assertTrue(torrent.verifyPiece(1, "ef".encodeToByteArray()))
        for (bad in listOf(
            dict("length" to int(1), "files" to files, "name" to str("x"), "piece length" to int(4), "pieces" to bin(ByteArray(20))),
            dict("length" to int(1), "name" to str("x"), "piece length" to int(4), "pieces" to bin(ByteArray(40))),
            dict("length" to int(-1), "name" to str("x"), "piece length" to int(4), "pieces" to bin(byteArrayOf())),
            dict("files" to list(dict("length" to int(0), "path" to list(str("..")))), "name" to str("x"), "piece length" to int(4), "pieces" to bin(byteArrayOf()))))
            assertFailsWith<IllegalArgumentException> { TorrentFile.parse(metainfo(bad)) }
    }

    @Test fun v2ShortFileRootUsesActualBlockTreeAndRawSha256Identity() {
        val data = "hello".encodeToByteArray()
        val info = v2Info(data.size.toLong(), sha256(data))
        val torrent = TorrentFile.parse(metainfo(info, dict()))
        assertTrue(torrent.isV2Only); assertEquals(5L, torrent.totalSize)
        assertEquals(5, torrent.pieceLength(0)); assertEquals(1, torrent.numPieces)
        assertContentEquals(sha256(encode(info)), torrent.infoHash.bytes)
        assertContentEquals(sha256(encode(info)).copyOf(20), torrent.wireInfoHash)
        assertTrue(torrent.verifyPiece(0, data)); assertFalse(torrent.verifyPiece(0, "Hello".encodeToByteArray()))
        assertFailsWith<IllegalArgumentException> { torrent.infoHashV1() }
        assertFailsWith<IllegalArgumentException> { TorrentFile.parse(metainfo(info)) }
    }

    @Test fun v2PieceLayerRootPaddingAndMetadataOnlyState() = runTest {
        // Three 32-KiB pieces: final piece is one byte. Padding hashes are zero leaves, not hashes of zero blocks.
        val blockA = ByteArray(16384) { 1 }; val blockB = ByteArray(16384) { 2 }
        val p0 = sha256(sha256(blockA) + sha256(blockB))
        val p1 = sha256(sha256(blockB) + sha256(blockA))
        val p2 = sha256(sha256(byteArrayOf(3)) + ByteArray(32))
        val pad = sha256(ByteArray(64))
        val root = sha256(sha256(p0 + p1) + sha256(p2 + pad))
        val hashes = p0 + p1 + p2
        val info = v2Info(65537, root)
        val torrent = TorrentFile.parse(metainfo(info, layer(root, hashes)))
        assertTrue(torrent.pieceLayersComplete)
        assertEquals(3, torrent.numPieces)
        assertTrue(torrent.verifyPiece(0, blockA + blockB)); assertTrue(torrent.verifyPiece(1, blockB + blockA))
        assertTrue(torrent.verifyPiece(2, byteArrayOf(3)))
        assertFalse(torrent.verifyPiece(2, byteArrayOf(4)))
        assertFailsWith<IllegalArgumentException> { TorrentFile.parse(metainfo(info, layer(root, hashes.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }))) }
        val metadata = TorrentFile.fromInfoBytes(encode(info), torrent.infoHash)
        assertFalse(metadata.pieceLayersComplete); assertFalse(metadata.verifyPiece(0, blockA + blockB))
        val requests = mutableListOf<PeerHashRange>()
        val acquired = metadata.acquirePieceLayers { range -> requests += range; assertNotNull(torrent.hashes(range)) }
        assertEquals(1, requests.size); assertEquals(1, requests.single().baseLayer); assertEquals(4, requests.single().length)
        assertTrue(acquired.verifyPiece(2, byteArrayOf(3)))
        val proof = assertNotNull(torrent.hashes(PeerHashRange(root, 1, 0, 2, 1)))
        assertContentEquals(p0 + p1 + sha256(p2 + pad), proof)
        assertNull(torrent.hashes(PeerHashRange(root, 0, 0, 2, 0)))
        assertFailsWith<IllegalArgumentException> { metadata.acquirePieceLayers { ByteArray(it.length * 32) } }
    }

    @Test fun v2FileAlignmentAndHybridBothHashesAreRequired() {
        val a = byteArrayOf(7); val b = byteArrayOf(8, 9)
        val tree = dict("a" to dict("" to dict("length" to int(1), "pieces root" to bin(sha256(a)))),
            "b" to dict("" to dict("length" to int(2), "pieces root" to bin(sha256(b)))))
        val files = list(dict("length" to int(1), "path" to list(str("a"))),
            dict("attr" to str("p"), "length" to int(16383), "path" to list(str(".pad"), str("16383"))),
            dict("length" to int(2), "path" to list(str("b"))))
        val pieces = Sha1.digest(a.copyOf(16384)) + Sha1.digest(b)
        fun info(hashes: ByteArray) = dict("file tree" to tree, "files" to files, "meta version" to int(2),
            "name" to str("root"), "piece length" to int(16384), "pieces" to bin(hashes))
        val torrent = TorrentFile.parse(metainfo(info(pieces), dict()))
        assertTrue(torrent.isHybrid); assertEquals(3L, torrent.totalSize)
        assertEquals(listOf(0L, 16384L), torrent.files.view.map { it.offset })
        assertTrue(torrent.verifyPiece(0, a)); assertTrue(torrent.verifyPiece(1, b))
        val v1Mode = TorrentFile.fromInfoBytes(torrent.infoBytes(), torrent.v1InfoHash!!)
        assertEquals(torrent.v1InfoHash, v1Mode.infoHash)
        assertEquals(16384, v1Mode.pieceLength(0)); assertTrue(v1Mode.verifyPiece(0, a.copyOf(16384)))
        val corruptV1 = TorrentFile.parse(metainfo(info(ByteArray(40)), dict()))
        assertFalse(corruptV1.verifyPiece(0, a))
    }

    @Test fun magnetsPreserveRequestedTopicsAndRepeatedPeerSources() {
        val hash = InfoHash(ByteArray(20) { it.toByte() })
        val uri = "magnet:?xt=urn:btih:${hash.hex()}&dn=hello%20world&tr=https%3A%2F%2Ft%2Fa%3Fx%3D1%2B2&tr=udp%3A%2F%2Fu%3A80&tr=udp%3A%2F%2Fu%3A80&x.pe=example.org%3A6881&x.pe=%5B%3A%3A1%5D%3A6882"
        val magnet = MagnetUri.parse(uri)
        assertEquals(hash, magnet.infoHash); assertEquals("hello world", magnet.displayName)
        assertEquals(listOf("https://t/a?x=1+2", "udp://u:80", "udp://u:80"), magnet.trackers.view.toList())
        assertEquals(listOf(PeerAddress("example.org", 6881), PeerAddress("::1", 6882)), magnet.peers.view.toList())
        assertEquals(InfoHash(ByteArray(20)), MagnetUri.parse("magnet:?xt=urn:btih:" + "A".repeat(32)).infoHash)
        val v2 = InfoHash(ByteArray(32) { (it + 1).toByte() })
        val both = MagnetUri.parse("magnet:?xt=urn:btih:${hash.hex()}&xt=urn:btmh:1220${v2.hex()}")
        assertEquals(v2, both.infoHash); assertEquals(2, both.infoHashes.size)
        for (bad in listOf("magnet:?dn=oops", "magnet:?xt=urn:btih:00", "magnet:?xt=urn:btmh:1120${v2.hex()}",
            "magnet:?xt=urn:btih:${hash.hex()}&x.pe=host:0", "magnet:?xt=urn:btih:${hash.hex()}&dn=%zz"))
            assertFailsWith<IllegalArgumentException>(bad) { MagnetUri.parse(bad) }
    }

    @Test fun metadataAssemblyChecksLastWidthDuplicatesBoundsAndExactHash() {
        val info = encode(v1(byteArrayOf(1), extra = "extra" to bin(ByteArray(20000) { (it % 251).toByte() })))
        val expected = InfoHash(Sha1.digest(info))
        val metadata = TorrentMetadata(expected, info.size)
        val first = info.copyOf(16384); val last = info.copyOfRange(16384, info.size)
        metadata.accept(1, last)
        assertFalse(metadata.complete)
        assertFailsWith<IllegalStateException> { metadata.infoBytes() }
        assertFailsWith<IllegalArgumentException> { metadata.accept(0, ByteArray(1)) }
        metadata.accept(0, first); metadata.accept(0, first)
        assertTrue(metadata.complete); assertContentEquals(info, metadata.infoBytes())
        assertEquals(expected, metadata.torrentFile().infoHash)
        assertFailsWith<IllegalArgumentException> { metadata.accept(0, first.copyOf().also { it[0]++ }) }
        assertFailsWith<IllegalArgumentException> { metadata.accept(2, byteArrayOf()) }
        val wrong = TorrentMetadata(InfoHash(ByteArray(20)), info.size)
        wrong.accept(0, first); wrong.accept(1, last)
        assertFailsWith<IllegalArgumentException> { wrong.infoBytes() }
        assertFailsWith<IllegalArgumentException> { TorrentMetadata(expected, TorrentFile.MAX_METADATA_BYTES + 1) }
        val badMagnet = MagnetUri.parse("magnet:?xt=urn:btih:${expected.hex()}&xt=urn:btmh:1220${"00".repeat(32)}")
        assertFailsWith<IllegalArgumentException> { badMagnet.validateMetadata(info) }
    }

    @Test fun seedValidatesAllPiecesBeforeStorageAndOmitsPaddingFromOutput() = runTest {
        val files = list(dict("length" to int(2), "path" to list(str("a"))),
            dict("attr" to str("p"), "length" to int(2), "path" to list(str("pad"))),
            dict("length" to int(3), "path" to list(str("b"))))
        val info = dict("files" to files, "name" to str("root"), "piece length" to int(4),
            "pieces" to bin(Sha1.digest(byteArrayOf(97, 98, 0, 0)) + Sha1.digest("cde".encodeToByteArray())))
        val wire = metainfo(info)
        val engine = TorrentEngine(MemoryBlockStore(), this, listenAddress = null)
        try {
            assertFailsWith<IllegalArgumentException> { engine.seedTorrent(wire, "abcdf".encodeToByteArray()) }
            val handle = engine.getAllTorrents().single()
            assertEquals(0, handle.haveBitfield().numSet())
            engine.seedTorrent(wire, "abcde".encodeToByteArray())
            assertEquals(2, handle.haveBitfield().numSet())
            assertContentEquals("abcde".encodeToByteArray(), handle.downloadAll())
        } finally { engine.close() }
        val empty = TorrentFile.parse(metainfo(v1(byteArrayOf())))
        assertEquals(0, BitField.empty(empty.numPieces).numSet())
    }
}
