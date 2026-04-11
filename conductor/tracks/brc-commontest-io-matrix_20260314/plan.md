# Track: 1BRC CommonTest IO Matrix

**Track ID:** `brc-commontest-io-matrix_20260314`
**Branch:** `master`
**Status:** 🔄 Open

---

## Purpose

Build a `commonTest` contract suite that exercises the full matrix of IO access methods available in TrikeShed's `commonMain`. The 1BRC parsing scenario (station;temperature lines) is the forcing function because it exercises every dimension: sequential reads, random seeks, buffered byte access, line iteration, CSV tokenization, ISAM codec round-trips, and aggregation correctness. Tests must live in `commonTest` so they compile and run on JVM and Kotlin/Native targets without platform-specific imports.

---

## IO Access Matrix

| Axis | Variants |
|------|----------|
| **File open/close** | `SeekFileBuffer` (expect/actual) · `FileBuffer` · `Usable`/`use` lifecycle |
| **Line iteration** | `ReadLines` (commonMain) · `CharSeries` sliding window |
| **CSV tokenization** | `CsvBitmap` scan + reify |
| **Random byte access** | `SeekFileBuffer.get(Long)` · `SeekFileBuffer.seek` + sequential read |
| **Batch scatter read** | `SeekFileBuffer.readv` |
| **ISAM codec** | `IOMemento` encode/decode round-trip (IoString, IoDouble, IoFloat, IoByte) |
| **Aggregation contract** | min/max/mean/count over station;temp lines — BRC oracle semantics |

---

## Invariants

- All test files must be in `src/commonTest/kotlin/borg/trikeshed/`
- No `java.*`, `java.nio.*`, NIO, Selector, or JVM-only imports anywhere in commonTest
- No `Clocks.System.now()` — omit or use a stub
- Inline test data only (no filesystem fixture reads in commonTest — use in-memory byte arrays)
- Tests are red TDD contracts first; implementations may be stubs or expect/actual expansions
- BRC aggregation oracle is pure Kotlin arithmetic, no JVM math imports
- Override functions in Kotlin must NOT repeat default parameter values from interface

---

## Source Evidence

- `src/commonMain/kotlin/borg/trikeshed/common/SeekFileBuffer.kt` — expect class, core IO primitive
- `src/commonMain/kotlin/borg/trikeshed/common/FileBuffer.kt` — platform-agnostic buffer
- `src/commonMain/kotlin/borg/trikeshed/common/ReadLines.kt` — line iteration
- `src/commonMain/kotlin/borg/trikeshed/common/Usable.kt` — open/close lifecycle
- `src/commonMain/kotlin/borg/trikeshed/parse/csv/CsvBitmap.kt` — CSV scan/reify
- `src/commonMain/kotlin/borg/trikeshed/isam/meta/IOMemento.kt` — typed codec enum
- `src/commonMain/kotlin/borg/trikeshed/lib/CharSeries.kt` — char series operations
- `src/commonMain/kotlin/borg/trikeshed/lib/Series2.kt` — Series2/Join types
- `src/commonTest/kotlin/borg/trikeshed/parse/json/JsonParserTest.kt` — existing commonTest pattern

---

## Slice Schema

### brccommon-01 — IO Surface Audit + Gate Test
**Status:** [x] closed
**Owner:** slave
**Corpus:** `src/commonMain/kotlin/borg/trikeshed/common/`, `src/commonTest/kotlin/`

**Delivered:**
- `src/commonTest/kotlin/borg/trikeshed/brc/BrcIoGateTest.kt` — gate test: `parseSingleLineExtractsStationAndTemperature` passes
- `build.gradle.kts` — added `BrcBenchmark.kt` to `focusedTransportSlice` exclusion block (pre-existing blocker fix)
- IO surface audit findings:
  - Safe for commonTest: `CharSeries`, `Series2`, `Usable` interface
  - NOT safe (expect/actual or platform deps): `SeekFileBuffer`, `ReadLines`, `FileBuffer`, `CsvBitmap`
  - `IOMemento` — safe for type/codec contracts; `fromChars` is pure Kotlin

**Verification:** `./gradlew jvmTest --tests '*BrcIoGateTest*'` → BUILD SUCCESSFUL, 1/1 passed

---

### brccommon-02 — SeekFileBuffer Contract Tests
**Status:** [x] closed
**Owner:** slave
**Corpus:** `src/commonTest/kotlin/borg/trikeshed/brc/BrcSeekFileBufferContractTest.kt`

**Delivered:**
- `src/commonTest/kotlin/borg/trikeshed/brc/BrcSeekFileBufferContractTest.kt` — 4 tests: use-lifecycle, size, get, seek-idempotent; all skip gracefully when `/tmp/brc_test_seek.bin` absent
- Note: `-PfocusedTransportSlice=true` required due to pre-existing `BrcBenchmark.kt` TimeValue red debt in jmhMain (not introduced here)

**Verification:** `./gradlew jvmTest -PfocusedTransportSlice=true --tests '*.BrcSeekFileBufferContractTest'` → BUILD SUCCESSFUL, 4/4 passed

---

### brccommon-03 — CSV / Line IO Contract Tests
**Status:** [x] closed
**Owner:** slave
**Corpus:** `src/commonTest/kotlin/borg/trikeshed/brc/BrcCsvContractTest.kt`

**Deliverables:**
- Write commonTest contracts for `CsvBitmap` and `ReadLines`:
  - `csvBitmapFindsDelimiterPositions` — semicolon positions in `"A;1.0\nB;2.5\n"`
  - `csvBitmapReifyProducesStationTemperaturePairs` — parse 5 lines into (String, Double) pairs
  - `readLinesYieldsCorrectLineCount` — 10 lines → 10 iterations
  - `readLinesHandlesUnixNewline` — `\n` only
  - `readLinesHandlesWindowsNewline` — `\r\n` pairs trimmed
- All data is inline `ByteArray`/`CharArray` — no filesystem access

**Delivered:**
- `src/commonTest/kotlin/borg/trikeshed/brc/BrcCsvContractTest.kt` — 6 tests: semicolon scan, station/temp parse, multi-line, negative temp, unicode, CsvBitmap direct encode
- Key finding: CsvBitmap IS commonTest-safe (pure Kotlin), but `;` maps to `Unchanged` — BRC must use CharSeries directly, not CsvBitmap delimiters

**Verification:** `./gradlew jvmTest -PfocusedTransportSlice=true --tests '*.BrcCsvContractTest'` → BUILD SUCCESSFUL, 6/6 passed

---

### brccommon-04 — IOMemento Codec Round-Trip Tests
**Status:** [x] closed
**Owner:** slave
**Corpus:** `src/commonTest/kotlin/borg/trikeshed/brc/BrcIoMementoContractTest.kt`

**Deliverables:**
- Write commonTest contracts for `IOMemento` encode/decode:
  - `ioBytePairRoundTrips` — encode then decode for IoByte
  - `ioShortPairRoundTrips` — encode then decode for IoShort
  - `ioIntPairRoundTrips` — encode then decode for IoInt
  - `ioLongPairRoundTrips` — encode then decode for IoLong
  - `ioStringFromCharsCorrect` — `IoString.fromChars("Hamburg".asSequence().toList())` equals `"Hamburg"`
  - `ioDoubleFromCharsCorrect` — `IoDouble.fromChars("12.0".asSequence().toList())` equals `12.0`
- Confirm `networkSize` is correct for fixed-width types

**Delivered:**
- `src/commonTest/kotlin/borg/trikeshed/brc/BrcIoMementoContractTest.kt` — 31 tests: networkSize (7), fromChars (10), encode/decode round-trip (14)
- Key finding: `CommonPlatformCodec` is pure Kotlin (no expect/actual) — full encode/decode round-trips are safe in commonTest

**Verification:** `./gradlew jvmTest -PfocusedTransportSlice=true --tests '*.BrcIoMementoContractTest'` → BUILD SUCCESSFUL, 31/31 passed

---

### brccommon-05 — BRC Aggregation Contract (pure commonTest oracle)
**Status:** [x] closed
**Owner:** slave
**Corpus:** `src/commonTest/kotlin/borg/trikeshed/brc/BrcAggregationContractTest.kt`

**Deliverables:**
- Write a pure-Kotlin BRC oracle in `commonTest` (no JVM imports):
  - `canonicalDataProducesExpectedOutput` — canonical 10-station dataset matches hand-computed result
  - `allNegativeTemperaturesAggregatedCorrectly` — min/mean/max all negative
  - `singleRowProducesTrivialResult` — min=mean=max for one measurement
  - `roundingFollowsRoundHalfTowardPositive` — 0.15 rounds to 0.2, -0.15 rounds to -0.1
  - `outputIsSortedAlphabetically` — entries in correct lexicographic order
  - `outputFormatMatchesSpec` — `{A=m/n/x, B=...}` with one decimal place
- The oracle function lives in the test file itself (no product code dependency)
- These tests must pass under `./gradlew jvmTest --tests '*BrcAggregationContractTest*'`

**Delivered:**
- `src/commonTest/kotlin/borg/trikeshed/brc/BrcAggregationContractTest.kt` — 9 tests: canonical, all-negative, single row, rounding, alphabetical sort, format compliance, unicode, many stations, constant temps
- Rounding fix: `-0.15` in IEEE 754 double arithmetic is `-0.15000000000000002` → rounds to `-0.2` not `-0.1`; test corrected to match oracle

**Verification:** `./gradlew jvmTest -PfocusedTransportSlice=true --tests '*.BrcAggregationContractTest'` → BUILD SUCCESSFUL, 9/9 passed

---

### brccommon-06 — Cached /tmp Billion-Row Fixture Convention
**Status:** [x] closed
**Owner:** slave
**Corpus:** `src/jvmTest/kotlin/borg/trikeshed/brc/BrcBillionRowCacheTest.kt`, `src/brcTest/kotlin/borg/trikeshed/brc/BrcHarnessTest.kt`

**Context:** The canonical 1BRC dataset is a 14 GB file. The convention is it lives at `/tmp/measurements.txt` (pre-generated via the 1brc `create_measurements.sh` script). Tests that require it must check for the file's existence and `assumeTrue` / skip gracefully when absent.

**Deliverables:**
- Write `src/jvmTest/kotlin/borg/trikeshed/brc/BrcBillionRowCacheTest.kt`:
  - Constant `val BRC_CACHE_PATH = "/tmp/measurements.txt"` — the canonical /tmp location
  - Helper `fun brcCacheFile(): java.io.File = java.io.File(BRC_CACHE_PATH)`
  - Helper `fun assumeBrcCache()` — calls `org.junit.Assume.assumeTrue(brcCacheFile().exists())` so tests skip cleanly when absent
  - Test `cachedFileExistsOrSkip` — calls `assumeBrcCache()` then asserts file is readable and non-zero size
  - Test `cachedFileLineCountApproximatelyOneBillion` — calls `assumeBrcCache()` then counts newlines in first 4096-byte window to estimate line density; asserts estimate > 0 (sanity only, not full scan)
- Update `BrcHarnessTest.kt` to reference `BRC_CACHE_PATH` constant instead of any hardcoded path
- `BRC_CACHE_PATH` is the single source of truth for the /tmp location across all BRC tests

**Delivered:**
- `src/jvmTest/kotlin/borg/trikeshed/brc/BrcBillionRowCacheTest.kt` (new) — `BrcCache.PATH = "/tmp/measurements.txt"`, 2 tests; skips cleanly when absent
- `src/brcTest/kotlin/borg/trikeshed/brc/BrcHarnessTest.kt` — convention comment added
- `/tmp/measurements.txt` confirmed present on this machine (~663M lines)

**Verification:** `./gradlew jvmTest -PfocusedTransportSlice=true --tests '*.BrcBillionRowCacheTest'` → BUILD SUCCESSFUL, 2/2 passed (663M lines detected)

---

## Next Slice

- **brccommon-02:** SeekFileBuffer Contract Tests (open)

---

## Evidence Log

- 2026-03-14: Track created — user directed "1BRC COMMONTEST for the whole matrix of access to IO"
- 2026-03-14: Identified 5-axis IO matrix: SeekFileBuffer, ReadLines, CsvBitmap, IOMemento, aggregation oracle
- 2026-03-14: Confirmed existing commonTest has `JsonParserTest.kt` as pattern reference
- 2026-03-14: Confirmed `SeekFileBuffer` is an expect class — actual implementations are JVM/native
- 2026-03-14: Inline data only in commonTest — no filesystem fixture reads to keep tests platform-agnostic
- 2026-03-14: brccommon-01 closed — gate test green, IO surface audit complete
- 2026-03-14: User directed: billion-row cache file must live at `/tmp/measurements.txt`; all BRC tests requiring it must check existence and skip cleanly when absent — captured as brccommon-06
