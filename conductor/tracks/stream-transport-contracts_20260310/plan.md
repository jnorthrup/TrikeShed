# Track: Stream Transport TDD Contracts

**Track ID:** `stream-transport-contracts_20260310`
**Branch:** `master`
**Status:** ✅ Completed

---

## Purpose

The CCEK transport capability carriers (`QuicChannelService`, `NgSctpService`) have
`openStream(): StreamHandle` returning `TODO()`. Before implementing, establish
failing TDD contracts that document the exact API expectations.

This is a pure contract-writing slice: failing tests only, no implementation.

## Invariants

- No implementation inside `QuicChannelService` or `NgSctpService` (still `TODO`)
- Tests compile but fail at runtime with `NotImplementedError`
- No NIO/platform-specific imports in commonMain
- Stubs defined in test file (same pattern as arrange-03)

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

---

## Evidence Log

- 2026-03-10: Track created from CCEK TODO inventory. QuicChannelService and NgSctpService both have TODO("...stream factory").
- 2026-03-10: stream-01 closed — `StreamTransportContractTest.kt` written with 6 failing contracts (4 QUIC, 2 SCTP). All 6 fail with `NotImplementedError` (correct red TDD state).
