# TrikeShed Restructuring & Expansion — Progress Tracker

> **Guiding principles:**
> - **CCEK dominates refactor directions** — preserve the CCEK structure from literbike
> - **Reactor core is central to literbike; platforms live in trikeshed**
> - **liburing facade is write-once NIO for all Kotlin** — platform-specific actuals underneath
> - **Kotlin Kademlia (gk/kademlia) is higher quality** — preserve as-is, don't replace with literbike dht port
> - **TDD first** — failing tests gate completion
> - **Commit & push between major phases**

---

## Port Progress

### COMPLETED ✅

| Phase | Files | Lines | Description |
|-------|-------|-------|-------------|
| Gradle/Kotlin upgrade | 6 | - | Gradle 8.13→9.4.1, Kotlin 2.3.20→2.4.0-Beta1, benmanes 0.53.0 |
| Userspace NIO/kernel | 55 | ~11K | NIO backends, reactor, epoll, kqueue, uring, kernel syscalls, ebpf, tensor |
| CCEK core + htxke | 4 | ~600 | Element/Key/Context, X25519/HKDF tickets |
| CCEK store | 14 | ~4K | BlockStore, ObjectStore, CAS, backends, session |
| CCEK quic | 26 | ~4K | Engines, protocol, server, stream, crypto, WAM, TLS |
| CCEK json | 7 | ~2K | JsonValue, JsonParser, JsonBitmap, AtomicPool |
| CCEK sctp | 8 | ~1K | SCTP association, chunks, handler, socket, stream |
| CCEK api_translation | 3 | ~1K | UnifiedClient, Converter, 16 providers |
| CCEK keymux | 3 | ~1K | ModelFacade, MuxMenu, DSELBuilder, Types, Models |
| CCEK http | 3 | ~1K | HeaderParser zero-copy, Session, Server |
| CCEK agent8888 | 2 | ~500 | Protocol detection, CCEK Elements |
| CCEK store/couchdb | 4 | ~500 | Types, Error, Cursor, Tensor |
| reactor/session/channel | 15 | ~2K | Reactor, Selector, Timer, Session, Routes |
| concurrency | 6 | ~800 | CCEK channels, Flow, Scope, Bridge |
| couchdb | 13 | ~1K | Api, Attachments, Cursor, GitSync, Ipfs, M2M |
| request_factory | 6 | ~500 | Changes, Handler, Tracker, Types, Wire |
| curl_h2 | 5 | ~500 | Client, Error, Request, Response |
| betanet | 9 | ~1K | Anchor, AdaptiveTyping, DetectorPipeline, BabyPandas |
| dht | 4 | ~500 | PeerId, Multihash, IpfsClient, DhtService |
| modelmux | 9 | ~2K | ModelCache, ModelRegistry, Toolbar, Streaming, Decision |
| endgame | 1 | ~100 | EndgameCapabilities with OS detection |
| adapters | 1 | ~30 | Adapter constants |
| protocol | 2 | ~200 | Protocol enum, ProtocolDetector |
| htx | 1 | ~100 | X25519/HKDF ticket verification |
| htxke | 8 | ~500 | Channels, Delta, Elements, KotlinMirror, Traits |
| http-htx | 6 | ~1K | Protocol, Handler, Listener, Matcher, Reactor, Timer |
| SeaOfNodes MPP | 51 | ~4K | Java tests → commonTest, CCEK pipeline tests |
| **TOTAL PORTED** | **264+153** | **~57K** | **417 Kotlin files across trikeshed + literbike** |

### REMAINING ⬜

| Module | Files | Lines | Notes |
|--------|-------|-------|-------|
| betanet (remaining) | ~27 | ~8K | Larger files not yet ported |
| rbcursive | 18 | ~6K | Text editor components |
| bin | 9 | ~2K | CLI entry points |
| gates | 12 | ~2K | Feature gates |
| simd | 3 | ~1K | SIMD optimizations |
| quic (src/) | ~26 | ~11K | Main quic module (not ccek/quic) |
| modelmux (remaining) | ~2 | ~1K | Remaining files |
| **TOTAL REMAINING** | **~97** | **~31K** | |

---

## Architecture

### uring / liburing Placement
- **`src/commonMain/kotlin/.../platform/nio/UringFacade.kt`** — userspace liburing facade (ALL Kotlin can use)
- **`src/linuxMain/kotlin/linux_uring/include/`** — Linux kernel driver FFI (platform-specific)
- The facade provides `write once NIO liburing IO` for future deeper IO stacking
- All Kotlin targets (JVM, JS, Native) can use the liburing facade regardless of platform

### Kotlin Kademlia (gk/kademlia)
- **PRESERVED AS-IS** — higher quality than literbike dht port
- 20 files, ~733 lines: Agent, NUID, RoutingTable, NetMask, codecs, tests
- Located in `src/commonMain/kotlin/gk/kademlia/`

---

## Next Steps

1. **Port remaining ~97 Rust files** (betanet, rbcursive, bin, gates, simd, quic)
2. **Gradle build fix** — package declarations, imports, expect/actual resolution
3. **Normalize into parent lib** — composite builds, dependency resolution
4. **TDD remediation** — all tests green
5. **Final commit & push**

---

## Commits (this session)

| Commit | Description |
|--------|-------------|
| `04d7a49` | Gradle 9.4.1, Kotlin 2.4.0-Beta1, userspace→platform, literbike scaffold |
| `fe61b48` | SeaOfNodes MPP, CCEK pipeline tests, literbike CCEK core tests |
| `b282bfe` | 55 Kotlin files — CCEK core/htxke + NIO backend/reactor (manual port) |
| `1ecb4cf` | +29 files — reactor/session/channel + CCEK store/json |
| `d076dab` | +30 files — concurrency/couchdb/request_factory/curl_h2 |
| `3a1bfe7` | +26 files — CCEK QUIC protocol (engines, server, crypto, WAM) |
| `45fd7ad` | +26 files — betanet/dht/modelmux/endgame/adapters/protocol |
| `b1e8467` | +39 files — sctp/uring/htx/api_translation/keymux/http/couchdb/agent8888 |
