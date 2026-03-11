# Track: Stream Transport TDD Contracts

**Track ID:** `stream-transport-contracts_20260310`
**Branch:** `master`
**Status:** ✅ Completed

---

## Purpose

The CCEK transport capability carriers (`QuicChannelService`, `NgSctpService`) still have
`openStream(): StreamHandle` returning `TODO()`. The red TDD contracts are already in place;
the remaining work is to implement the stream factories and green the contract tests.

## Invariants

- No NIO/platform-specific imports in commonMain
- `StreamHandle` factories preserve the existing transport service public shapes
- Contract proof remains focused on `StreamTransportContractTest`

## Bounded Corpus

- `src/commonMain/kotlin/borg/trikeshed/ccek/transport/StreamTransport.kt` (read only)
- `src/commonMain/kotlin/borg/trikeshed/ccek/transport/QuicChannelService.kt` (read only)
- `src/commonMain/kotlin/borg/trikeshed/ccek/transport/NgSctpService.kt` (read only)
- `src/jvmTest/kotlin/borg/trikeshed/ccek/transport/StreamTransportContractTest.kt` (create)

## Slice Schema

### stream-01 — openStream() failing contracts
**Status:** [x] closed
**Owner:** slave

**Contracts to document:**
- `QuicChannelService().openStream()` returns `StreamHandle` with valid `id` and channels
- `NgSctpService().openStream()` returns `StreamHandle` with valid `id` and channels
- `activeStreams` increments after each successful `openStream()`
- `StreamHandle.id` is non-negative
- `StreamHandle.send` is not closed on open
- `StreamHandle.recv` is not closed on open

**Verification:** `./gradlew jvmTest -PfocusedTransportSlice=true --tests '*.StreamTransportContractTest'`
→ tests compile, all fail with `NotImplementedError` (correct red state)

### stream-02 — QUIC stream factory
**Status:** [x] closed
**Owner:** slave

**Corpus:**
- `src/commonMain/kotlin/borg/trikeshed/ccek/transport/QuicChannelService.kt`
- `src/jvmTest/kotlin/borg/trikeshed/ccek/transport/StreamTransportContractTest.kt` (read only unless a test adjustment is required by repo reality)

**Goal:**
- Replace `TODO("QUIC stream factory")` with a concrete `StreamHandle` factory
- Preserve the current public shape of `QuicChannelService`
- Green the QUIC contracts in `StreamTransportContractTest`

**Verification:** `./gradlew jvmTest -PfocusedTransportSlice=true --tests 'borg.trikeshed.ccek.transport.StreamTransportContractTest.quic*'`
→ BUILD SUCCESSFUL

### stream-03 — SCTP stream factory
**Status:** [x] closed
**Owner:** slave

**Corpus:**
- `src/commonMain/kotlin/borg/trikeshed/ccek/transport/NgSctpService.kt`
- `src/jvmTest/kotlin/borg/trikeshed/ccek/transport/StreamTransportContractTest.kt` (read only unless a test adjustment is required by repo reality)

**Goal:**
- Replace `TODO("SCTP stream factory")` with a concrete `StreamHandle` factory
- Preserve the current public shape of `NgSctpService`
- Green the SCTP contracts in `StreamTransportContractTest`

**Verification:** `./gradlew jvmTest -PfocusedTransportSlice=true --tests 'borg.trikeshed.ccek.transport.StreamTransportContractTest.sctp*'`
→ BUILD SUCCESSFUL

---

## Evidence Log

- 2026-03-10: Track created from CCEK TODO inventory. QuicChannelService and NgSctpService both have TODO("...stream factory").
- 2026-03-10: stream-01 closed — `StreamTransportContractTest.kt` written with 6 failing contracts (4 QUIC, 2 SCTP). All 6 fail with `NotImplementedError` (correct red TDD state).
- 2026-03-10: Corrected plan status after red phase. Track remains open until `stream-02` and `stream-03` are implemented and verified green.
- 2026-03-10: stream-02 closed — `QuicChannelService.openStream()` now allocates a non-negative stream id, opens buffered send/recv channels, and records the handle in the default mutable stream map. QUIC-focused contract verification passed.
- 2026-03-10: stream-03 closed — `NgSctpService.openStream()` now allocates a non-negative stream id, opens buffered send/recv channels, and records the handle in the default mutable stream map. SCTP-focused contract verification passed.
- 2026-03-10: Track closed — `./gradlew jvmTest -PfocusedTransportSlice=true --tests '*.StreamTransportContractTest'` passed with all 6 stream transport contracts green.
