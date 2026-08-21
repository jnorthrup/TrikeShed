# HTX token spine — frozen funnel generations over tokens, not headers

Status: design, 2026-08-21. Every claim cites current source; limits are stated as limits.

## 1. The unit is the token spine

HTX is the common tokenizer for HTTP/1.x, HTTP/2 and HTTP/3 (`src/README.md`). A request is therefore
already a spine of tokens — method, path segments, pseudo-headers, header names, header values — before
anything else sees it. That spine is the unit this design indexes, **not** a map of header names.

Each token is a `LineNode`-shaped row (`cas/LineCas.kt:214-218`): `contentCid` (sha256 of the token bytes),
`stamp` (neighbor stamp: prev/next token hashes, `LineCas.kt:53-54`), `ordinal`. The neighbor stamp is what
keeps `content-type → application/json` distinct from `content-type → text/html` without storing pairs —
the same trick the line merge uses to separate identical lines in different neighborhoods.

Because `residualsOf`, `topologyOf`, `gradeClusters` and `mergeResiduals`
(`cas/FunnelResidualMerge.kt:212, 264, 317, 389`) never look at text — only at `contentCid`/`stamp`/`ordinal` —
an `HtxTokenSpine` drops into that pipeline with no change downstream. Only the spine builder is new.

## 2. Key/value makes it a hierarchy

A header is `key → value`; a path is `seg₁ → seg₂ → …`; pseudo-headers group under `:`. So the spine is a
tree, and the token's address is a composite key whose prefix order is the rollup order — the cascade-key
discipline (`docs/…/recall plan §1`, `couchdbcascade` key shape):

    [scope, kind, key, value]            kind ∈ { PSEUDO, PATH, HEADER, TRAILER, BODY_TOKEN }
    [scope, HEADER, "content-type"]      → prefix: every value this header has carried in this scope
    [scope, PATH, "api", "v2"]           → prefix: every request under /api/v2
    [scope]                              → the whole vocabulary of the ring

`group_level` over that key is the header-family rollup, the path-subtree rollup, the ring rollup — no
second index. This is the `BtrfsKey(objectid, type, offset)` composition (`btrfs/BtrfsKey.kt:3`): `scope` is
the objectid (which ring), `kind` the type (which tree), the rest the offset (the probe). Each `kind` is an
independent tree and freezes independently.

## 3. What gets frozen, and what does not

- **Do not funnel the per-request spine.** ~20–40 tokens: an array scan beats a funnel and a map alike.
- **Freeze the vocabulary.** The set of `contentCid`s a scope has seen is frozen into a
  `FunnelHashIndex` generation (`collections/associative/FunnelHashIndex.kt:64`, `build(keys, seed, slack=0.20)`).
  A request's spine probes the current generations; its **residual** — tokens not in any frozen generation —
  is the only thing that costs anything (the merge's "a MISS is the signal", `doc/funnel-residual-merge.md §2`).
- A generation is byte-identical for the same key set + seed → it has a `ContentId`. Re-freezing the same
  staging is a no-op by CID: idempotent publish. Generations are immutable, so every core and the JS
  gateway share them without copying; they ship as blobs (`funnel/<cid>` on the blackboard).
- Per-generation seed: attacker-chosen header names cannot be made to collide (hash flooding).

## 4. Generations as COW roots (the ring)

Never mutate a generation; write a new one — btrfs transid semantics:

- generation 0: the static vocabulary (RFC 9110 names, pseudo-headers, app routes). Global, built once.
- staging per `(scope, kind)`: a small mutable set. At N entries (64–1024, a tuning knob) freeze it into a
  funnel and push it on that tree's ring. Readers on older generations are unaffected.
- lookup: newest → oldest. Funnels are cheapest on MISS, so K generations of fast misses is fine if K is
  bounded (4–8). That bound is HPACK's table size in disguise.
- compaction off the hot path: on CCEK `drain()`/tick (`context/AsyncContextElement.kt:62`), merge a ring
  into one larger frozen generation.
- backing: `MutableSeries` append + subscribe (as `couch/CouchChangesProjection.kt:17-38` uses for its frame
  log) with a bounded window; observers notified on freeze.

## 5. Scope is the subnet ring, and taint comes from the listener

`scope` binds to `Nuid.subnet` (`context/nuid/Nuid.kt:228`; `Subnet.contains` is prefix, authority flows
inward only, `:128-162`). Rings are per scope and live in the coroutine context as an `AsyncContextKey`
element under the `HtxReactorElement` scope — `core`/`local` rings are in-process, `mesh.worker.*` rings
per worker, `global.relay` at the gateway.

Today HTX carries no subnet at all: `HtxRequest` (`htx/HtxRequest.kt:64`) and `HtxExchangeState`
(`htx/HtxElement.kt:70`) have no `Nuid`, and there is no `HtxRequest → ReactorAction` bridge
(`reactor/ReactorCodec.kt:49-97` converts envelopes only). The subnet must come from **which listener
admitted the exchange**, never from a client header; that bridge is the prerequisite for §4's `scope`.

## 6. Concurrency cost

Reads of frozen generations are plain loads — no CAS, no coherence traffic; the static generation fits in
every core's L1/L2. CAS appears only at: publishing a generation (once per N inserts), and epoch
reclamation of retired generations (per-core padded counters, not a shared refcount). Staging is
scope-affine → single-writer → no CAS. Guard false sharing on the JVM: ring head, staging counter and epoch
slot must not share an object/line. On Kotlin/JS the question is moot (single-threaded), which is one more
reason rings are per scope with no platform branch.

## 7. Where it sits in the transport picture

HTX is the **outer face** of each ring's gateway: content-addressed, cacheable, NAT/proxy-proof,
browser-native with no NPM. The DHT/orchestration plane behind it is SCTP — `SctpElement`
(`context/sctp/SctpElement.kt:317`, package `borg.trikeshed.sctp`) over the `SctpWire` SPI
(`context/sctp/SctpWire.kt`; loopback default, `JvmKernelSctpWire` stub unregistered). The token-spine
generations are what the gateway dedupes across everything it admits; the DHT plane never sees HTX tokens.

## 8. Limits

- `mini64` (8 of 64 hex chars) and the 8-bit residual `NeighborPrefix` (`FunnelResidualMerge.kt`, doc §6)
  are coarse for lines; for structured tokens widen the prefix before relying on relocation grading.
- A funnel HIT is a candidate until the stored `contentCid` is compared; only MISS is authoritative.
- Order must ride alongside as `ordinal` — proxies must not reorder `Set-Cookie`; the funnel is a set.
- Nothing in §3–§5 exists yet except the pieces it composes; the spine builder, the ring element, and the
  listener-stamped subnet are the three new parts.
