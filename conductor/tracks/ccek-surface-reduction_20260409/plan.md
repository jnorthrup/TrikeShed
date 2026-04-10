# Track: CoroutineContextElement.Key Surface Reduction

**Track ID:** `ccek-surface-reduction_20260409`
**Created:** 2026-04-09
**Status:** Planning

## Purpose

Reduce TrikeShed's top-level package surface area by refactoring concerns into `CoroutineContextElement.Key` abstractions. This moves from ad-hoc top-level singletons and scattered module state into a unified key-anchored context element model.

CCEK here means `CoroutineContextElement.Key` — the Kotlin standard library concept for typed identification of anchors in a `CoroutineContext`.

## Lineage

- **Literbike** (`../literbike/src/http-htx/`) implements `HtxKey`/`HtxElement` as a working example, with CCEK trait impls (feature-gated)
- **Literbike** (`../literbike/src/htxke/`) has the HTX key exchange infrastructure
- **RelaxFactory** (`../RelaxFactory/`) has the legacy `Rfc822HeaderState` HTTP parser being superseded

## Current Problem

TrikeShed's top-level packages expose too much surface area:
- `one.xio.*` — HTTP types, transport backends (partially migrated from RelaxFactory Java)
- `borg.trikeshed.net.*` — Protocol routing, middleware, channelization
- `borg.trikeshed.parse.*` — JSON parser, JSON bitmap, CSV utilities
- `borg.trikeshed.cursor.*` — Blackboard overlay abstractions
- `borg.trikeshed.context.*` — Handler registry
- `borg.trikeshed.lib.*` — Series, Join, Twin, etc. (core abstractions)
- `borg.trikeshed.ccek.*` — CCEK transport contracts

Many of these should be encapsulated behind `CoroutineContextElement.Key` → `CoroutineContextElement` pairs rather than exposed as free-floating top-level APIs.

## Target Architecture

### Key/Element Pairs to Create

Each `Key` is a `CoroutineContext.Key<T>` singleton; each `Element` is a `CoroutineContextElement` that carries operational state.

| Key | Element | Responsibility | Currently In |
|---|---|---|---|
| `HttpKey : CoroutineContext.Key<HttpElement>` | `HttpElement : CoroutineContextElement` | HTTP request/response state, HTX-normalized messages | `one.xio.*`, `borg.trikeshed.net.http.*` |
| `JsonKey : CoroutineContext.Key<JsonElement>` | `JsonElement : CoroutineContextElement` | JSON indexing, bitmap state, query results | `borg.trikeshed.parse.json.*` |
| `TransportKey : CoroutineContext.Key<TransportElement>` | `TransportElement : CoroutineContextElement` | Transport backend state, session tracking | `one.xio.spi.*`, `borg.trikeshed.net.*` |
| `CursorKey : CoroutineContext.Key<CursorElement>` | `CursorElement : CoroutineContextElement` | Blackboard overlay, overlay state | `borg.trikeshed.cursor.*` |
| `ProtocolKey : CoroutineContext.Key<ProtocolElement>` | `ProtocolElement : CoroutineContextElement` | Protocol detection, routing decisions | `borg.trikeshed.net.ProtocolRouter` |
| `ChannelKey : CoroutineContext.Key<ChannelElement>` | `ChannelElement : CoroutineContextElement` | Channelization session, block, graph state | `borg.trikeshed.net.channelization.*` |

### HTX Tokenizer Integration

Literbike has a working HTX tokenizer at `../literbike/src/http-htx/protocol/mod.rs` that:
- Normalizes HTTP/1, HTTP/2, HTTP/3 into a common `HtxMessage`
- Uses HAProxy's HTX block model (ReqSl, ResSl, Hdr, Eoh, Data, Tlr, Eot, Unused)
- Tracks per-version parse counts and bytes via `HtxElement`
- Has CCEK integration via `ccek_core::Key`/`ccek_core::Element` traits (feature-gated)

This needs to be ported to Kotlin as the replacement for RelaxFactory's `Rfc822HeaderState` legacy parser.

### Refactoring Steps

1. **Port HTX tokenizer** from Literbike Rust → TrikeShed Kotlin
   - `HtxBlockType`, `HtxBlock`, `HtxStartLine`, `HtxMessage`
   - HTTP/1 parser (`parse_http1`) as initial surface
   - `normalize_to_htx()` for protocol-agnostic ingestion

2. **Create `HttpKey`/`HttpElement`** in `borg.trikeshed.ccek.http.*`
   - `HttpKey : CoroutineContext.Key<HttpElement>` — the anchor
   - `HttpElement : CoroutineContextElement` — tracks parse counts, bytes, active requests
   - Exposes `HtxMessage` as the normalized representation
   - Replaces `one.xio.*` header tokens and `Rfc822HeaderState` lineage

3. **Encapsulate JSON concerns** behind `JsonKey`/`JsonElement`
   - `JsonParser` (indexer/reifier) → element state
   - `JsonBitmap` (parallel bitplane indexer) → element state for large-doc pipeline
   - `JsonScanner` (contract test stub) → thin convenience API on top

4. **Reduce top-level package exposure**
   - Move `one.xio.*` types into key-anchored modules or retire them
   - `ProtocolRouter` → `ProtocolKey`/`ProtocolElement`
   - Channelization types → `ChannelKey`/`ChannelElement`
   - Cursor types → `CursorKey`/`CursorElement`

5. **Update consumers** to use context access instead of direct imports
   - Handlers receive `CoroutineContext` and look up elements by Key
   - No more direct `import borg.trikeshed.net.*` sprawl

## Dependencies

- Literbike HTX implementation (Rust, working reference)
- RelaxFactory `Rfc822HeaderState` (Java, being replaced)
- TrikeShed CCEK infrastructure (`ccek/` package)
- JsonBitmap parallel indexing (existing, needs key-anchored encapsulation)

## Constraints

- Do not break existing passing tests
- HTX port must match Literbike's block model exactly (cross-lang compatibility)
- `Pair` replacement: use `Join<>` from `borg.trikeshed.lib` (already exists)
- `JsonBitmap` is designed for petabyte-scale parallel JSON indexing — do not simplify away the bitplane design

## Notes

- 2026-04-09: Track created during dust-settling phase after build fix + benmanes plugin addition
- 2026-04-09: User confirmed `Pair` → `Join<>` mapping, HTX port from Literbike as RelaxFactory replacement
- 2026-04-09: Build status: compiles clean, 36/297 JVM tests failing (pre-existing WIP), 2/57 wasmJs failing
- 2026-04-09: Corrected terminology — CCEK means `CoroutineContextElement.Key`, no branded names
