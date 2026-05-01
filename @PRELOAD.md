@PRELOAD.md — scaffold: libs/ipfs & libs/torrent

Purpose
-------
Seed design notes and minimal API sketches for two new libraries: libs/ipfs and libs/torrent. Use nearby legacy implementations in `old/v2superbikeshed*` as source material to accelerate scaffolding.

What was found (high-value candidates)
-------------------------------------
- trikeshed-torrent (old/v2superbikeshed/trikeshed-torrent)
  - protocol/BitTorrentPeerWire.kt — full peer-wire implementation (handshake, messages, piece handling)
  - TorrentHost.kt — host lifecycle (start/stop/add/announce)
  - TrikeDownloader.kt — unified downloader with TorrentDownload task types
  - TorrentKettle.kt, TorrentDemo.kt, client RPC server and tests (commonTest/*)
  - README, CLIENT_IMPLEMENTATIONS_README.md, EVOLUTION_PLAN.md, TEST_SUMMARY.md

- trikeshed-dht (old/v2superbikeshed/trikeshed-dht)
  - ChannelizedKademlia.kt, RoutingTable.kt, KademliaCodec.kt, DHTNetworkSimulation.kt
  - Kademlia id/NUID, routing/KBucket, codec and integration helpers

- Misc design notes
  - CLAUDE.md and other project docs reference "trikeshed-net, torrent, dht, ipfs" — useful architecturally
  - TrikeDownloader & client README provide UX/CLI personas and feature minimums to reuse

Recommended repo layout (initial)
---------------------------------
- libs/torrent
  - core/ (models, piece, infohash, torrent-file parsing)
  - protocol/ (peer-wire implementation, handshake, message types)
  - host/ (TorrentHost, seeding API)
  - client/ (TrikeDownloader integration, aria-like clients)
  - rpc/ (RPC server/transformer)
  - test-fixtures/ (torrent fixtures, small torrents)

- libs/ipfs
  - core/ (CID, multihash, block format)
  - storage/ (block store, BlobHosting-like API)
  - network/ (libp2p-like wiring, transports abstraction)
  - naming/ (DHT name lookups, integration with trikeshed-dht code)
  - cli/ (small ipfs-ish tool)

Design goals
------------
- Kotlin multiplatform-first (commonMain APIs), reuse existing commonMain code where possible
- Small, well-typed public surface: Host/Client/Codec/Router/Store
- Tests ported from old/ as fixtures to confirm behavior
- Start minimal: seed, fetch, announce, find peers, piece verification

Actionable API sketches (copy small snippets to iterate)
--------------------------------------------------------
1) Torrent host - minimal lifecycle (adapted)

```kotlin
class TorrentHost(
    internal val port: Int = 6881,
    internal val uploadDir: String = ".",
    internal val downloadDir: String = "."
) : CoroutineContext.Element {
    suspend fun start()
    suspend fun stop()
    suspend fun addTorrent(torrentInfo: TorrentInfo)
    suspend fun removeTorrent(infoHash: InfoHash)
}
```

2) BitTorrent peer-wire essentials (message types + sealed messages)

```kotlin
enum class MessageType(val id: Byte) {
    CHOKE(0), UNCHOKE(1), INTERESTED(2), NOT_INTERESTED(3),
    HAVE(4), BITFIELD(5), REQUEST(6), PIECE(7), CANCEL(8), PORT(9), EXTENSION(20)
}

sealed class PeerMessage {
    data class Handshake(...): PeerMessage()
    data class KeepAlive(): PeerMessage()
    data class Request(val pieceIndex:Int, val offset:Int, val length:Int): PeerMessage()
    data class Piece(val pieceIndex:Int, val offset:Int, val data:ByteIndexed): PeerMessage()
    // ...
}
```

3) Kademlia codec interface (encode/decode)

```kotlin
interface Codec<T,R> {
    fun encode(obj: T): R
    fun decode(data: R): T
}

class KademliaCodec : Codec<KademliaEvent, Indexed<Byte>> {
    override fun encode(event: KademliaEvent): Indexed<Byte> = TODO()
    override fun decode(data: Indexed<Byte>): KademliaEvent = TODO()
}
```

4) TrikeDownloader - torrent task sketch

```kotlin
sealed class DownloadTask {
  abstract val id: String
  @Serializable
  data class TorrentDownload(
    override val id: String,
    override val url: String, // magnet or torrent URL
    override val outputPath: String,
    val trackers: List<String> = emptyList(),
    val maxPeers: Int = 50
  ) : DownloadTask()
}
```

Porting candidates (priority)
----------------------------
- [high] trikeshed-torrent/protocol/BitTorrentPeerWire.kt (protocol engine)
- [high] trikeshed-torrent/TorrentHost.kt (host API)
- [high] trikeshed-dht/* (routing table, codec, integration)
- [med] TrikeDownloader integration for example client UX
- [med] client READMEs & CLIENT_IMPLEMENTATIONS_README.md for CLI/UX guidance
- [low] simulations and demos (useful for tests)

Tests and examples
------------------
- Port tests from: trikeshed-torrent/src/commonTest/**/* (TorrentHostTest, TrikeDownloader tests, protocol TDD tests)
- Create small fixtures (tiny torrent with a couple of pieces) under libs/torrent/test-fixtures

Next steps (suggested)
----------------------
1) Create libs/torrent module skeleton and copy over minimal models + protocol interfaces
2) Create libs/dht (or libs/ipfs naming) reusing KademliaCodec + RoutingTable for name/discovery
3) Port/trim BitTorrentPeerWire to protocol/ with small integration tests
4) Wire TrikeDownloader example to libs/torrent client for a demo script
5) Iterate on API boundaries; prefer small, platform-neutral interfaces first

Notes
-----
- Many legacy files are in `old/v2superbikeshed*` — use them as reference, not drop-in code. Prefer extracting small, well-tested units and refactoring into clean multiplatform APIs.
- CLAUDE.md and README files contain additional architectural context — review before final API decisions.

Prepared-by: scan of old/ and parent projects; next: create libs skeleton and port a minimal handshake + test.
