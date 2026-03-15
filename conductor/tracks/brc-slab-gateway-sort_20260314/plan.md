# Track: BRC Gateway Sequence Test

**Track ID:** `brc-slab-gateway-sort_20260314`
**Branch:** `master`
**Status:** 🔄 Open

---

## Purpose

Test all existing BRC variants in a sequence ordered by IO access type, verifying each produces identical output against the same fixture. Priority order: Cursor IO varieties first (they are the canonical TrikeShed approach), then mmap/parallel, then DuckDB.

All fixture creation/cleanup in `/tmp` only — nothing written to repo.

---

## Actual Variants (existing in codebase)

### JVM (`src/jvmMain/kotlin/borg/trikeshed/brc/`)

| Variant | IO Access | Accumulator |
|---------|-----------|-------------|
| `BrcCursor` | FileBuffer + Cursor/Series α/j | HashMap |
| `BrcPure` | FileBuffer + Series α/j fold | Series fold (no HashMap) |
| `BrcDiscoveryOrder` | FileBuffer | Array linear scan, no HashMap |
| `BrcHashArray` | FileBuffer | Open-address IntArray index |
| `BrcHeapBisect` | FileBuffer + cache prescan | Array + heap bisect |
| `BrcMmap` | FileBuffer mmap direct | HashMap |
| `BrcParallel` | FileBuffer mmap + coroutine chunks | HashMap per chunk |
| `BrcDuckDbJvm` | DuckSeries JDBC SQL | columnar GROUP BY |

### Native (`src/posixMain/kotlin/borg/trikeshed/brc/`)

| Variant | IO Access |
|---------|-----------|
| `BrcCursorNative` | FileBuffer posix + Cursor |
| `BrcIsamNative` | FileBuffer posix byte scan |
| `BrcCsvNative` | FileBuffer posix CSV |
| `BrcDuckDbNative` | DuckDB native |

---

## Slice Schema

### gatewayseq-01 — Cursor IO Varieties First (BrcCursor, BrcPure, BrcDiscoveryOrder)
**Status:** [ ] pending
**Owner:** slave
**Corpus:** `src/jvmTest/kotlin/borg/trikeshed/brc/BrcCursorGatewayTest.kt` (new), `src/jvmMain/kotlin/borg/trikeshed/brc/BrcCursor.kt`, `BrcPure.kt`, `BrcDiscoveryOrder.kt` (read)

**Deliverables:**
- Write fixture to `/tmp/brc_gateway_test.txt` (canonical 20-line dataset), delete after
- Capture stdout from each variant's `main(arrayOf("/tmp/brc_gateway_test.txt"))`
- Assert all three produce identical output
- Assert output matches `{Bridgetown=..., Bulawayo=..., ...}` format

**Verification:** `./gradlew jvmTest -PfocusedTransportSlice=true --tests '*.BrcCursorGatewayTest'`

---

### gatewayseq-02 — Remaining JVM Variants (HashArray, HeapBisect, Mmap, Parallel)
**Status:** [ ] pending (depends on gatewayseq-01 for fixture pattern)
**Owner:** slave
**Corpus:** `src/jvmTest/kotlin/borg/trikeshed/brc/BrcRemainingJvmGatewayTest.kt` (new)

**Deliverables:**
- Same fixture pattern as gatewayseq-01
- BrcHashArray, BrcHeapBisect, BrcMmap each compared against gatewayseq-01 reference output
- BrcParallel: capture stdout, compare — wrap in try/catch for runBlocking threading edge cases
- Assert all four match

**Verification:** `./gradlew jvmTest -PfocusedTransportSlice=true --tests '*.BrcRemainingJvmGatewayTest'`

---

### gatewayseq-03 — DuckDB Gateway
**Status:** [ ] pending (depends on gatewayseq-01)
**Owner:** slave
**Corpus:** `src/jvmTest/kotlin/borg/trikeshed/brc/BrcDuckDbGatewayTest.kt` (new)

**Deliverables:**
- Write fixture to `/tmp/brc_gateway_duck_test.txt`, delete after
- Run `BrcDuckDbJvm.main(arrayOf(...))`, capture stdout
- Assert output matches reference from gatewayseq-01
- Skip gracefully if DuckDB JDBC unavailable

**Verification:** `./gradlew jvmTest -PfocusedTransportSlice=true --tests '*.BrcDuckDbGatewayTest'`

---

### gatewayseq-04 — Full Sequence All-Match
**Status:** [ ] pending (depends on gatewayseq-01..03)
**Owner:** slave
**Corpus:** `src/jvmTest/kotlin/borg/trikeshed/brc/BrcAllVariantsSequenceTest.kt` (new)

**Deliverables:**
- Run all 7 JVM variants in priority sequence: Cursor → Pure → DiscoveryOrder → HashArray → HeapBisect → Mmap → Parallel → DuckDb
- Assert every output is byte-identical to the first
- Large-file variant: if `/tmp/measurements.txt` present, run all and compare — skip if absent

**Verification:** `./gradlew jvmTest -PfocusedTransportSlice=true --tests '*.BrcAllVariantsSequenceTest'`

---

## Next Slice

- **gatewayseq-01:** Cursor IO varieties first (open)

---

## Evidence Log

- 2026-03-14: Track created — user directed: test actual Cursor IO varieties in sequence, no invented abstractions
- 2026-03-14: Removed hallucinated "slab accumulator" test files (BrcSlabAccumulatorTest.kt, BrcJmpSlabGatewayTest.kt)
- 2026-03-14: Track renamed to gateway sequence; uses only existing variants in src/jvmMain/kotlin/borg/trikeshed/brc/
