# TrikeShed Wide Area Data Networking — Organization

## Overview

This document maps the architecture and phase plan for integrating TrikeShed's
wide-area data networking: torrent streaming, IPFS content-addressed storage,
Pijul CRDT sync, CouchDB replication, GK Kademlia routing, io_uring/eBPF reactor,
and Hazelnut IMDG acceleration.

---

## Component Inventory (as discovered)

### libs/torrent — BitTorrent Streaming + DHT
- KademliaDht.kt — BEP 5 DHT with XOR-distance k-buckets (160 buckets, K=8)
- TorrentElement.kt — Swarm supervisor (Pattern A CCEK)
- TorrentPeer.kt — Wire protocol state machine, bitfield, request building
- TorrentPiece.kt — Piece tracker with SHA-1 verification, 16 KiB blocks
- Protocol.kt — MessageType enum, sealed PeerMessage hierarchy
- DhtProtocol.kt — Bencode DHT wire format (193 lines)
- StreamingTorrent.kt — Seekable media with cursor-based piece prioritization
- TorrentMediaServer.kt — HTTP Range server for ffplay integration
- TorrentTracker.kt — Tracker announce logic
- TorrentMetainfo.kt — .torrent file parsing
- TorrentApi.kt — TorrentHostAPI interface
- MagnetLink.kt — BEP 9 magnet link parsing (hex + base32)
- WebTorrentProtocol.kt — BEP 19 WebSocket bitTorrent
- HyperdlElement.kt — Root choreographer (SupervisorJob tree)
- HyperdlRpcServer.kt — JSON-RPC / aria2-compatible RPC over HTTP, ring I/O
- HyperdlRpcServer.kt uses ReactorOperations + ChannelOperations already

### libs/ipfs — Content-Addressed Block Store (skeleton)
- IpfsApi.kt — BlockStore + NameResolver interfaces, CID data class
- IpfsElement.kt — AsyncContextElement wrapping BlockStore + DhtService
- DhtService.kt — In-process provider registry (minimal)
- DhtTransport.kt — Optional network transport hooks interface

### libs/couch — CouchDB + Pijul CRDT
**pijul/**:
- PijulSyncEngine.kt — coroutineScope fan-out/fan-in sync, KET negotiation, ChangeEmitter
- PijulIpfsStore.kt — IPFS staging (store/retrieve/pin/list), LEB128 serialization
- KetExtension.kt — KET capability negotiation (OFFER/ACK/NACK/REQ), StandardCapabilities
**other couch subpackages**: control/, stream/ (Change<T>, ChangeEmitter), htx/

### libs/hazelnut — Forum/Thread + RowVec Mapping
- HazelnutService.kt — Couch mapreduce workers, RowVec mapping (503 lines)
- HazelnutCrystallize.kt — Materialized view crystallization
- Uses UringFacade, UringOp, UringSubmission, ByteBuffer, SeekHandle patterns

### GK Kademlia (diminished, found in codex-backup)
Located: /Users/jim/codex-backup/worktrees/0d3f/TrikeShed/src/commonMain/kotlin/gk/kademlia/

Packages:
- gk.kademlia.routing — RoutingTable (generic over TNum, NetMask<TNum>)
- gk.kademlia.id — NUID<TNum>, WorkerNUID, ElectionNUID, SupportNUID + 9 impl variants
- gk.kademlia.bitops — BitOps interface + 10 implementations (Byte...BigInteger)
- gk.kademlia.net — NetMask<TNum>
- gk.kademlia.include — TypeDefs (Address, Route)
- gk.kademlia.agent — Agent, EventTypes
- gk.kademlia.codec — Wire codec

Key observation: GK RoutingTable uses `borg.trikeshed.lib.Join` and `↺` rotation.
It's already wired into the trikeshed algebra but was moved to codex-backup during
a migration. Needs porting back to libs/gk-kademlia.

### io_uring Reactor
- PosixUringIO.kt — Ring-based readAt/writeAt/fsync/closeFd with fallback to POSIX
- Liburing.linux.kt — Actual io_uring implementation (io_uring_queue_init etc.)
- Liburing.macos.kt — All unsupported() stubs
- HyperdlRpcServer.kt already uses ChannelOperations + ReactorOperations with ring paths
- ACCEPT_KEY / READ_KEY constants in HyperdlRpcServer for ring event dispatch

---

## Architecture: The Seven-Layer Stack

```
L7  hazelnut    — Couch mapreduce workers, RowVec mapping, crystallization
L6  couch/pijul  — CRDT sync engine, KET negotiation, IPFS staging, ChangeEmitter
L5  ipfs         — Content-addressed block store, CID, DHT service/provider
L4  torrent      — BitTorrent swarm, DHT, streaming priority, RPC server
L3  gk-kademlia  — Generic NUID routing tables, BitOps, Agent (from codex-backup)
L2  uring        — io_uring ring ops, ChannelOperations, ReactorOperations
L1  core         — Join/Series/Cursor algebra, ByteBuffer, SeekHandle, AsyncContextElement
```

---

## Phase Plan

### Phase 1: Torrent Dynamic Block Priority (libs/torrent)
- libs/torrent/src/commonMain/kotlin/.../torrent/
  - BlockPriority.kt — priority queue for pieces based on cursor position, rarity, urgency
  - TorrentStreamSelector.kt — decide which blocks to fetch based on streaming cursor
  - PeerBlockQueue.kt — per-peer pending block queue with unchoke logic

### Phase 2: GK Kademlia Port (libs/gk-kademlia)
- Port from codex-backup to libs/gk-kademlia
- Wire RoutingTable into KademliaDht (replace simple ByteArray buckets)
- Add NUID<TNum> → TorrentPeer.id mapping
- Integrate DhtTransport with GK Agent event loop

### Phase 3: IPFS-Side Pijul CRDT Gateway
- libs/couch/src/commonMain/kotlin/.../couch/
  - IpfsGateway.kt — IPFS pubsub + DHT announcement for Pijul patches
  - Replication Coordinator — master→{master,slave} topology
  - CouchViewServer.kt — live CRDT view server bridging to Couch RequestFactory

### Phase 4: nio-uring-ebpf SupervisorJob Reactor
- libs/torrent already has ring path in HyperdlRpcServer
- Wire UringFacade into TorrentPeer/TorrentElement socket I/O
- Add reactor event loop: accept → ring → read → parse → respond
- eBPF program attachment for protocol detection (future)

### Phase 5: Hazelnut IMDG Acceleration
- libs/hazelnut/src/commonMain/kotlin/.../hazelnut/
  - IMDG cluster topology over Couch mapreduce workers
  - Regional replication via CRDT
  - Crystallization + streaming materialized views

---

## GK Kademlia Architecture (from archived code)

The GK package implements a generic Kademlia DHT where NUID (Network Unique ID) is
parameterized over numeric types (Byte, Int, Long, BigInteger, etc.):

```
NUID<TNum> ──┐
  ├── BitOps<TNum>     — bitwise XOR, distance, comparison
  ├── NetMask<TNum>    — network mask for distance calculation
  └── RoutingTable     — k-buckets keyed by NUID bucket index
```

The RoutingTable uses the trikeshed `Join<NUID<TNum>, Address>` algebra — it's not
a simple HashMap but a compositional Join. This means GK routes are first-class
Join values in the core algebra.

### Porting Strategy:
1. Create libs/gk-kademlia/src/commonMain with all 7 packages
2. Keep generic signatures (NUID<TNum : Comparable<TNum>>)
3. Replace Join<NUID, Address> if those imports don't resolve — may need to
   depend on libs/torrent or core
4. Wire GK RoutingTable as the DHT backend for both torrent KademliaDht and
   ipfs DhtService

---

## IPFS ↔ Torrent Bridge

Both protocols use:
- Content-addressable blocks (SHA-1 hashes / CIDs)
- DHT for peer/content discovery
- Piece/block-level transfer with availability bitmaps
- Streaming: prioritize pieces near cursor / first CID in DAG

Bridge points:
1. Torrent's BlockSelector → IPFS BlockStore (same priority scoring)
2. KademliaDht + GK RoutingTable → unified DHT layer
3. PijulIpfsStore already stages patches as IPFS blocks
4. TorrentHyperdlElement → could also use IPFS as a fallback transport

---

## Transport Gateway Architecture (CRDT over IPFS)

```
PijulSyncEngine                          IPFS PubSub/DHT
     │                                        │
     ├── Patch ──┐                             │
     │           ├──→ store(Patch) → CID ──────┘
     │           │         │
     │     PijulIpfsStore ─┘
     │           │
     │     ┌─────┴──────┐
     │     │            │
     │  master ──→ slave │  (replication topology)
     │     │            │
     │     └─────┬──────┘
     │           │
     │     CrdtGateway ──→ CouchDB _changes ──→ RequestFactory views
```

Crucially, the GTI gateway provides a live, near-realtime Pijul CRDT stream
that flows into CouchDB's _changes feed, powering the RequestFactory view server.
